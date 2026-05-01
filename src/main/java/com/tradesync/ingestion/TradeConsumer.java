package com.tradesync.ingestion;

import com.tradesync.db.TradeRepository;
import com.tradesync.metrics.MetricsRegistry;
import com.tradesync.model.TradeEvent;
import com.tradesync.orderbook.OrderBook;
import com.tradesync.pnl.PnLCalculator;
import com.tradesync.statemachine.OrderStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;

/**
 * Consumer thread that pulls TradeEvent objects from the shared queue
 * and drives them through the processing pipeline:
 *
 * 1. Persist to DB (INSERT into trades)
 * 2. Run FSM: RECEIVED → VALIDATED → MATCHED → SETTLED
 * 3. Update the RW-locked OrderBook
 * 4. Update the FIFO PnL calculator
 * 5. Increment metrics counters
 */
public class TradeConsumer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TradeConsumer.class);

    private final BlockingQueue<TradeEvent> queue;
    private final TradeRepository tradeRepo;
    private final OrderStateMachine stateMachine;
    private final OrderBook orderBook;
    private final PnLCalculator pnlCalculator;
    private final MetricsRegistry metrics;
    private final String consumerName;
    private volatile boolean running = true;

    public TradeConsumer(BlockingQueue<TradeEvent> queue,
                         TradeRepository tradeRepo,
                         OrderStateMachine stateMachine,
                         OrderBook orderBook,
                         PnLCalculator pnlCalculator,
                         MetricsRegistry metrics,
                         String consumerName) {
        this.queue         = queue;
        this.tradeRepo     = tradeRepo;
        this.stateMachine  = stateMachine;
        this.orderBook     = orderBook;
        this.pnlCalculator = pnlCalculator;
        this.metrics       = metrics;
        this.consumerName  = consumerName;
    }

    @Override
    public void run() {
        log.info("[{}] Consumer started", consumerName);
        while (running || !queue.isEmpty()) {
            try {
                // poll with timeout to allow clean shutdown
                TradeEvent trade = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (trade == null) continue;

                long start = System.nanoTime();
                process(trade);
                long elapsed = System.nanoTime() - start;

                metrics.recordProcessing(elapsed);
                metrics.incrementTradesProcessed();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[{}] Unexpected error processing trade", consumerName, e);
                metrics.incrementErrors();
            }
        }
        log.info("[{}] Consumer stopped", consumerName);
    }

    public void stop() { running = false; }

    // ── Processing pipeline ──────────────────────────────────────────────────

    private void process(TradeEvent trade) {
        // 1. Persist (state = RECEIVED)
        tradeRepo.insert(trade);

        // 2. Drive through FSM happy-path → SETTLED (or FAILED on error)
        stateMachine.processHappyPath(trade);

        // 3. If settled, add to order book
        if (trade.getState() == com.tradesync.model.TradeState.SETTLED) {
            orderBook.addTrade(trade);
            // 4. Update P&L
            pnlCalculator.processTrade(trade);
        }

        log.debug("[{}] Processed trade {} → {}", consumerName, trade.getTradeId(), trade.getState());
    }
}
