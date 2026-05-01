package com.tradesync.benchmark;

import com.tradesync.model.TradeEvent;
import com.tradesync.model.TradeSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Load simulator that measures throughput and P99 latency of the
 * trade ingestion pipeline by bypassing the HTTP layer and pushing
 * directly to the blocking queue.
 *
 * Usage: new LoadSimulator(queue).run(threads=20, tradesPerThread=500)
 *
 * Reports:
 *   - Total trades / elapsed time = throughput (trades/sec)
 *   - Sorted latency array → P50, P99, P99.9
 */
public class LoadSimulator {

    private static final Logger log = LoggerFactory.getLogger(LoadSimulator.class);

    private static final List<String> SYMBOLS = List.of(
        "AAPL", "MSFT", "GOOGL", "AMZN", "NVDA",
        "TSLA", "META", "JPM", "BAC", "GS"
    );

    private final BlockingQueue<TradeEvent> queue;

    public LoadSimulator(BlockingQueue<TradeEvent> queue) {
        this.queue = queue;
    }

    /**
     * Runs the load test synchronously and prints results to the logger.
     *
     * @param numThreads       producer threads
     * @param tradesPerThread  trades each thread will push
     */
    public void run(int numThreads, int tradesPerThread) throws InterruptedException {
        int total = numThreads * tradesPerThread;
        log.info("=== LoadSimulator starting: {} threads × {} trades = {} total ===",
                 numThreads, tradesPerThread, total);

        long[] latenciesNs = new long[total];
        LongAdder index    = new LongAdder();
        CountDownLatch latch = new CountDownLatch(numThreads);

        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        long globalStart = System.nanoTime();

        for (int t = 0; t < numThreads; t++) {
            pool.submit(() -> {
                Random rng = new Random();
                try {
                    for (int i = 0; i < tradesPerThread; i++) {
                        TradeEvent trade = randomTrade(rng);
                        long start = System.nanoTime();
                        queue.put(trade);
                        long latency = System.nanoTime() - start;
                        int idx = (int) index.sumThenReset(); // approximate slot
                        index.add(1);
                        if (idx < latenciesNs.length) {
                            latenciesNs[idx] = latency;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long globalEnd = System.nanoTime();
        pool.shutdown();

        double elapsedSec = (globalEnd - globalStart) / 1_000_000_000.0;
        double throughput = total / elapsedSec;

        // Sort for percentiles
        Arrays.sort(latenciesNs);
        long p50  = percentile(latenciesNs, 50);
        long p99  = percentile(latenciesNs, 99);
        long p999 = percentile(latenciesNs, 99.9);

        log.info("=== LoadSimulator Results ===");
        log.info("  Threads         : {}", numThreads);
        log.info("  Trades          : {}", total);
        log.info("  Elapsed         : {}s", String.format("%.3f", elapsedSec));
        log.info("  Throughput      : {} trades/sec", String.format("%.0f", throughput));
        log.info("  Enqueue P50     : {}µs", p50 / 1_000);
        log.info("  Enqueue P99     : {}µs", p99 / 1_000);
        log.info("  Enqueue P99.9   : {}µs", p999 / 1_000);
        log.info("  Queue remaining : {}", queue.size());
        log.info("============================");

        System.out.printf("""
            ╔══════════════════════════════════════╗
            ║       TradeSync Load Test Results    ║
            ╠══════════════════════════════════════╣
            ║  Threads:       %-20d  ║
            ║  Total trades:  %-20d  ║
            ║  Elapsed:       %-18.3fs  ║
            ║  Throughput:    %-16.0f t/s  ║
            ║  Enqueue P50:   %-18dµs  ║
            ║  Enqueue P99:   %-18dµs  ║
            ║  Enqueue P99.9: %-18dµs  ║
            ╚══════════════════════════════════════╝
            %n""",
            numThreads, total, elapsedSec, throughput,
            p50 / 1_000, p99 / 1_000, p999 / 1_000);
    }

    private static long percentile(long[] sorted, double pct) {
        int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    private static TradeEvent randomTrade(Random rng) {
        String symbol  = SYMBOLS.get(rng.nextInt(SYMBOLS.size()));
        long qty       = 100L + rng.nextInt(9901);
        BigDecimal price = BigDecimal.valueOf(10 + rng.nextDouble() * 990)
                                     .setScale(2, RoundingMode.HALF_UP);
        TradeSide side = rng.nextBoolean() ? TradeSide.BUY : TradeSide.SELL;
        return TradeEvent.of(symbol, qty, price, side);
    }
}
