package com.tradesync.ingestion;

import com.tradesync.model.TradeEvent;
import com.tradesync.model.TradeSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;

/**
 * Simulates an external trade feed by generating random TradeEvent objects
 * and pushing them into the shared blocking queue.
 *
 * Each producer thread is independent and uses its own Random instance
 * (no shared mutable state → zero data races at the producer level).
 */
public class TradeProducer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TradeProducer.class);

    private static final List<String> SYMBOLS = List.of(
        "AAPL", "MSFT", "GOOGL", "AMZN", "NVDA",
        "TSLA", "META", "JPM", "BAC", "GS",
        "MS", "WFC", "XOM", "CVX", "JNJ",
        "PFE", "UNH", "V", "MA", "BRK"
    );

    private final BlockingQueue<TradeEvent> queue;
    private final int tradesToProduce;     // -1 = infinite (live mode)
    private final String producerName;
    private volatile boolean running = true;

    /**
     * @param queue           shared ingestion queue
     * @param tradesToProduce number of trades to emit (-1 for continuous)
     * @param producerName    identifier for logging
     */
    public TradeProducer(BlockingQueue<TradeEvent> queue,
                         int tradesToProduce,
                         String producerName) {
        this.queue           = queue;
        this.tradesToProduce = tradesToProduce;
        this.producerName    = producerName;
    }

    @Override
    public void run() {
        Random rng = new Random(); // thread-local Random — no contention
        int produced = 0;
        log.info("[{}] Producer started (target={})", producerName,
                 tradesToProduce == -1 ? "∞" : tradesToProduce);

        try {
            while (running && (tradesToProduce == -1 || produced < tradesToProduce)) {
                TradeEvent event = generateRandom(rng);
                queue.put(event); // blocks if queue is full — natural back-pressure
                produced++;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("[{}] Producer interrupted after {} trades", producerName, produced);
        }
        log.info("[{}] Producer finished. Produced: {}", producerName, produced);
    }

    public void stop() { running = false; }

    // ── Private helpers ──────────────────────────────────────────────────────

    private TradeEvent generateRandom(Random rng) {
        String symbol   = SYMBOLS.get(rng.nextInt(SYMBOLS.size()));
        long   quantity = 100L + rng.nextInt(9901);          // 100–10,000 shares
        double rawPrice = 10.0 + rng.nextDouble() * 990.0;   // $10–$1000
        BigDecimal price= BigDecimal.valueOf(rawPrice).setScale(2, RoundingMode.HALF_UP);
        TradeSide side  = rng.nextBoolean() ? TradeSide.BUY : TradeSide.SELL;
        return TradeEvent.of(symbol, quantity, price, side);
    }
}
