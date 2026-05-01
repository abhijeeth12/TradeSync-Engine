package com.tradesync.orderbook;

import com.tradesync.model.TradeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe in-memory order book protected by a ReentrantReadWriteLock.
 *
 * Multiple reader threads never block each other — only writes acquire
 * the exclusive write lock. This allows high-throughput concurrent reads
 * of position data while ingestion writers update the book.
 *
 * Internal data structure: symbol → list of settled trade events.
 */
public class OrderBook {

    private static final Logger log = LoggerFactory.getLogger(OrderBook.class);

    /** Guards all access to the book map. */
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    /** symbol → list of TradeEvents in insertion order */
    private final Map<String, List<TradeEvent>> book = new HashMap<>();

    /**
     * Adds a settled trade to the order book.
     * Acquires the write lock — blocks concurrent writes, not reads.
     */
    public void addTrade(TradeEvent trade) {
        rwLock.writeLock().lock();
        try {
            book.computeIfAbsent(trade.getSymbol(), k -> new ArrayList<>())
                .add(trade);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Returns a snapshot of all trades for a symbol.
     * Acquires the read lock — multiple threads can do this concurrently.
     */
    public List<TradeEvent> getTradesForSymbol(String symbol) {
        rwLock.readLock().lock();
        try {
            List<TradeEvent> trades = book.get(symbol);
            return trades == null ? List.of() : List.copyOf(trades);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Returns aggregated net quantity for a symbol (BUY adds, SELL subtracts).
     */
    public long getNetQuantity(String symbol) {
        rwLock.readLock().lock();
        try {
            List<TradeEvent> trades = book.getOrDefault(symbol, List.of());
            long net = 0;
            for (TradeEvent t : trades) {
                net += switch (t.getSide()) {
                    case BUY  ->  t.getQuantity();
                    case SELL -> -t.getQuantity();
                };
            }
            return net;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Returns the weighted average price of BUY trades for a symbol.
     */
    public BigDecimal getAvgBuyPrice(String symbol) {
        rwLock.readLock().lock();
        try {
            List<TradeEvent> trades = book.getOrDefault(symbol, List.of());
            long totalQty = 0;
            BigDecimal totalValue = BigDecimal.ZERO;
            for (TradeEvent t : trades) {
                if (t.getSide() == com.tradesync.model.TradeSide.BUY) {
                    totalQty += t.getQuantity();
                    totalValue = totalValue.add(t.getPrice().multiply(BigDecimal.valueOf(t.getQuantity())));
                }
            }
            if (totalQty == 0) return BigDecimal.ZERO;
            return totalValue.divide(BigDecimal.valueOf(totalQty), 6, RoundingMode.HALF_UP);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** Returns an unmodifiable snapshot of all symbols currently in the book. */
    public Set<String> getSymbols() {
        rwLock.readLock().lock();
        try {
            return Set.copyOf(book.keySet());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** Returns the total number of trades across all symbols. */
    public int totalTrades() {
        rwLock.readLock().lock();
        try {
            return book.values().stream().mapToInt(List::size).sum();
        } finally {
            rwLock.readLock().unlock();
        }
    }
}
