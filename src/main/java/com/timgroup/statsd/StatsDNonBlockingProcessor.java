package com.timgroup.statsd;

import com.timgroup.statsd.Message;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;


public class StatsDNonBlockingProcessor extends StatsDProcessor {

    private final Queue<Message> messages;
    private final AtomicInteger qsize;  // qSize will not reflect actual size, but a close estimate.

    private class ProcessingTask extends StatsDProcessor.ProcessingTask {

        @Override
        public void run() {
            boolean empty;
            ByteBuffer sendBuffer;

            try {
                sendBuffer = bufferPool.borrow();
            } catch (final InterruptedException e) {
                handler.handle(e);
                return;
            }

            while (!((empty = messages.isEmpty()) && shutdown)) {

                try {
                    if (empty) {
                        Thread.sleep(WAIT_SLEEP_MS);
                        continue;
                    }

                    if (Thread.interrupted()) {
                        return;
                    }
                    final Message message = messages.poll();
                    if (message != null) {

                        qsize.decrementAndGet();
                        builder.setLength(0);

                        message.writeTo(builder);
                        int lowerBoundSize = builder.length();


                        if (sendBuffer.capacity() < lowerBoundSize) {
                            throw new InvalidMessageException(MESSAGE_TOO_LONG, builder.toString());
                        }

                        if (sendBuffer.remaining() < (lowerBoundSize + 1)) {
                            outboundQueue.put(sendBuffer);
                            sendBuffer = bufferPool.borrow();
                        }

                        sendBuffer.mark();
                        if (sendBuffer.position() > 0) {
                            sendBuffer.put((byte) '\n');
                        }

                        try {
                            writeBuilderToSendBuffer(sendBuffer);
                        } catch (BufferOverflowException boe) {
                            outboundQueue.put(sendBuffer);
                            sendBuffer = bufferPool.borrow();
                            writeBuilderToSendBuffer(sendBuffer);
                        }

                        if (null == messages.peek()) {
                            outboundQueue.put(sendBuffer);
                            sendBuffer = bufferPool.borrow();
                        }
                    }
                } catch (final InterruptedException e) {
                    if (shutdown) {
                        endSignal.countDown();
                        return;
                    }
                } catch (final Exception e) {
                    handler.handle(e);
                }
            }

            builder.setLength(0);
            builder.trimToSize();
            endSignal.countDown();
        }
    }

    StatsDNonBlockingProcessor(final int queueSize, final StatsDClientErrorHandler handler,
            final int maxPacketSizeBytes, final int poolSize, final int workers)
            throws Exception {

        super(queueSize, handler, maxPacketSizeBytes, poolSize, workers);
        this.qsize = new AtomicInteger(0);
        this.messages = new ConcurrentLinkedQueue<>();
    }

    @Override
    protected ProcessingTask createProcessingTask() {
        return new ProcessingTask();
    }

    StatsDNonBlockingProcessor(final StatsDNonBlockingProcessor processor) throws Exception {
        super(processor);
        this.qsize = new AtomicInteger(0);
        this.messages = new ConcurrentLinkedQueue<>();
    }

    @Override
    protected boolean send(final Message message) {
        if (!shutdown) {
            if (qsize.get() < qcapacity) {
                messages.offer(message);
                qsize.incrementAndGet();
                return true;
            }
        }

        return false;
    }

    boolean isShutdown() {
        return shutdown;
    }

    void shutdown() {
        shutdown = true;
    }
}
