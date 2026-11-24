package org.spring.shopmessaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Separate bounded relay lanes keep broker timeouts off the order and payment recovery scheduler. */
public final class OutboxWorkers implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxWorkers.class);
    private final JdbcMessageOutbox outbox;
    private final JdbcMessageOutbox.Sender sender;
    private final int workers;
    private final int pollMs;
    private final AtomicLong lastWarningMs = new AtomicLong();
    private volatile ScheduledThreadPoolExecutor executor;

    public OutboxWorkers(JdbcMessageOutbox outbox, JdbcMessageOutbox.Sender sender, int workers, int pollMs) {
        if (workers < 1 || workers > 8 || pollMs < 50 || pollMs > 5000) {
            throw new IllegalArgumentException("Outbox needs 1–8 workers and a 50–5000 ms polling interval");
        }
        this.outbox = outbox;
        this.sender = sender;
        this.workers = workers;
        this.pollMs = pollMs;
    }

    @Override
    public synchronized void start() {
        if (executor != null) return;
        var threadIds = new AtomicInteger();
        executor = new ScheduledThreadPoolExecutor(workers, task -> {
            Thread thread = new Thread(task, "trade-outbox-" + threadIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        for (int lane = 0; lane < workers; lane++) {
            executor.scheduleWithFixedDelay(this::relay, lane * pollMs / workers, pollMs, TimeUnit.MILLISECONDS);
        }
    }

    private void relay() {
        try {
            outbox.relay(sender);
        } catch (RuntimeException failure) {
            long previous = lastWarningMs.get();
            long now = System.currentTimeMillis();
            if (now - previous >= 60_000 && lastWarningMs.compareAndSet(previous, now)) {
                LOG.warn("event=OUTBOX_WORKER_FAILED errorType={}", failure.getClass().getSimpleName());
            }
        }
    }

    @Override
    public synchronized void stop() {
        if (executor == null) return;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(6, TimeUnit.SECONDS)) LOG.warn("event=OUTBOX_WORKER_STOP_TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            executor = null;
        }
    }

    @Override
    public boolean isRunning() { return executor != null; }
}
