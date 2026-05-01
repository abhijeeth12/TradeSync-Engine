package com.tradesync.metrics;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight metrics registry using lock-free atomic counters.
 *
 * Counters:
 *   tradesProcessed  — total trades through the consumer pipeline
 *   errors           — processing errors
 *   totalNanos       — sum of processing times (for avg calculation)
 *   processingCount  — number of timing samples
 *
 * Exposed via the /metrics REST endpoint.
 */
public class MetricsRegistry {

    private final LongAdder tradesProcessed  = new LongAdder();
    private final LongAdder errors           = new LongAdder();
    private final LongAdder totalNanos       = new LongAdder();
    private final LongAdder processingCount  = new LongAdder();
    private final long startTimeMs           = System.currentTimeMillis();

    private BlockingQueue<?> queue;  // injected after construction

    public void setQueue(BlockingQueue<?> queue) { this.queue = queue; }

    public void incrementTradesProcessed() { tradesProcessed.increment(); }
    public void incrementErrors()          { errors.increment(); }

    public void recordProcessing(long nanos) {
        totalNanos.add(nanos);
        processingCount.increment();
    }

    public long getTradesProcessed() { return tradesProcessed.sum(); }
    public long getErrors()          { return errors.sum(); }

    public long getQueueDepth() {
        return queue == null ? -1 : queue.size();
    }

    /** Average processing time in microseconds. */
    public double getAvgProcessingMicros() {
        long count = processingCount.sum();
        if (count == 0) return 0;
        return totalNanos.sum() / (double) count / 1_000.0;
    }

    /** Uptime in seconds. */
    public long getUptimeSeconds() {
        return (System.currentTimeMillis() - startTimeMs) / 1000;
    }

    /** Returns a snapshot map suitable for JSON serialization. */
    public java.util.Map<String, Object> snapshot() {
        return java.util.Map.of(
            "tradesProcessed",     getTradesProcessed(),
            "errors",              getErrors(),
            "queueDepth",          getQueueDepth(),
            "avgProcessingMicros", getAvgProcessingMicros(),
            "uptimeSeconds",       getUptimeSeconds()
        );
    }
}
