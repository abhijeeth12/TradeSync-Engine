package com.tradesync.ingestion;

import com.tradesync.config.AppConfig;
import com.tradesync.db.AuditRepository;
import com.tradesync.db.TradeRepository;
import com.tradesync.metrics.MetricsRegistry;
import com.tradesync.model.TradeEvent;
import com.tradesync.orderbook.OrderBook;
import com.tradesync.pnl.PnLCalculator;
import com.tradesync.statemachine.OrderStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Orchestrates the producer-consumer ingestion pipeline.
 *
 * Architecture:
 *   N producer threads → LinkedBlockingQueue → M consumer threads
 *
 * Lifecycle: start() / shutdown()
 */
public class TradeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TradeIngestionService.class);

    private final int numProducers;
    private final int numConsumers;
    private final int queueCapacity;

    private final LinkedBlockingQueue<TradeEvent> queue;
    private final ExecutorService producerPool;
    private final ExecutorService consumerPool;

    private final List<TradeProducer> producers = new ArrayList<>();
    private final List<TradeConsumer> consumers  = new ArrayList<>();

    private final TradeRepository tradeRepo;
    private final OrderStateMachine stateMachine;
    private final OrderBook orderBook;
    private final PnLCalculator pnlCalculator;
    private final MetricsRegistry metrics;

    public TradeIngestionService(TradeRepository tradeRepo,
                                 AuditRepository auditRepo,
                                 OrderBook orderBook,
                                 PnLCalculator pnlCalculator,
                                 MetricsRegistry metrics) {
        AppConfig cfg     = AppConfig.get();
        this.numProducers = cfg.getInt("ingestion.producers", 4);
        this.numConsumers = cfg.getInt("ingestion.consumers", 8);
        this.queueCapacity= cfg.getInt("ingestion.queue.capacity", 50000);

        this.queue         = new LinkedBlockingQueue<>(queueCapacity);
        this.tradeRepo     = tradeRepo;
        this.stateMachine  = new OrderStateMachine(tradeRepo, auditRepo);
        this.orderBook     = orderBook;
        this.pnlCalculator = pnlCalculator;
        this.metrics       = metrics;

        // Named thread pools for observability
        this.producerPool = Executors.newFixedThreadPool(numProducers,
            r -> new Thread(r, "trade-producer-" + Thread.currentThread().threadId()));
        this.consumerPool = Executors.newFixedThreadPool(numConsumers,
            r -> new Thread(r, "trade-consumer-" + Thread.currentThread().threadId()));
    }

    /**
     * Starts N producer and M consumer threads (continuous live mode).
     */
    public void start() {
        log.info("Starting TradeIngestionService: {} producers, {} consumers, queue cap={}",
                 numProducers, numConsumers, queueCapacity);

        for (int i = 0; i < numProducers; i++) {
            TradeProducer p = new TradeProducer(queue, -1, "producer-" + i);
            producers.add(p);
            producerPool.submit(p);
        }

        for (int i = 0; i < numConsumers; i++) {
            TradeConsumer c = new TradeConsumer(queue, tradeRepo, stateMachine,
                                                orderBook, pnlCalculator, metrics,
                                                "consumer-" + i);
            consumers.add(c);
            consumerPool.submit(c);
        }
        log.info("TradeIngestionService started");
    }

    /**
     * Gracefully drains the queue and shuts down all threads.
     */
    public void shutdown() {
        log.info("Shutting down TradeIngestionService...");
        producers.forEach(TradeProducer::stop);
        producerPool.shutdown();

        // Let consumers drain the remaining queue
        try {
            if (!producerPool.awaitTermination(10, TimeUnit.SECONDS))
                producerPool.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        consumers.forEach(TradeConsumer::stop);
        consumerPool.shutdown();
        try {
            if (!consumerPool.awaitTermination(15, TimeUnit.SECONDS))
                consumerPool.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("TradeIngestionService stopped. Queue remaining: {}", queue.size());
    }

    /** Returns the underlying queue — used by MetricsRegistry for depth. */
    public BlockingQueue<TradeEvent> getQueue() { return queue; }

    /** Returns the OrderStateMachine — exposed for REST ingest endpoint. */
    public OrderStateMachine getStateMachine() { return stateMachine; }

    public int getActiveProducers() {
        return (int) producers.stream().count();
    }
}
