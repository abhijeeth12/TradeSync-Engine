package com.tradesync.pnl;

import com.tradesync.model.TradeEvent;
import com.tradesync.model.TradeSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * FIFO-based cost basis tracker for computing realized and unrealized P&L.
 *
 * On BUY:  push a Lot (qty, costBasis) to the back of the per-symbol deque.
 * On SELL: pop Lots from the front (FIFO), computing realized P&L per lot.
 *
 * Unrealized P&L uses a mock market price that performs a bounded random walk
 * seeded by the symbol to keep prices deterministic across calls.
 *
 * Thread-safety: per-symbol synchronized blocks on the deque object.
 */
public class PnLCalculator {

    private static final Logger log = LoggerFactory.getLogger(PnLCalculator.class);

    /** A single purchase lot with remaining quantity and cost basis. */
    private record Lot(long qty, BigDecimal costBasis) {}

    /** symbol → FIFO deque of remaining BUY lots */
    private final ConcurrentHashMap<String, Deque<Lot>> lots = new ConcurrentHashMap<>();

    /** symbol → cumulative realized P&L */
    private final ConcurrentHashMap<String, BigDecimal> realizedPnL = new ConcurrentHashMap<>();

    /** symbol → simulated current market price (random walk state) */
    private final ConcurrentHashMap<String, BigDecimal> marketPrices = new ConcurrentHashMap<>();

    /**
     * Processes a settled trade — updates lots and P&L.
     */
    public void processTrade(TradeEvent trade) {
        String symbol = trade.getSymbol();
        if (trade.getSide() == TradeSide.BUY) {
            addLot(symbol, trade.getQuantity(), trade.getPrice());
        } else {
            realizeSell(symbol, trade.getQuantity(), trade.getPrice());
        }
        // Advance market price simulation
        updateMarketPrice(symbol, trade.getPrice());
    }

    /** Returns realized P&L for a symbol (0 if no sells yet). */
    public BigDecimal getRealizedPnL(String symbol) {
        return realizedPnL.getOrDefault(symbol, BigDecimal.ZERO);
    }

    /** Returns unrealized P&L = Σ remaining lots × (currentPrice - costBasis). */
    public BigDecimal getUnrealizedPnL(String symbol) {
        BigDecimal currentPrice = getCurrentMarketPrice(symbol);
        Deque<Lot> symbolLots  = lots.getOrDefault(symbol, new ArrayDeque<>());
        BigDecimal unrealized  = BigDecimal.ZERO;
        synchronized (symbolLots) {
            for (Lot lot : symbolLots) {
                BigDecimal gain = currentPrice.subtract(lot.costBasis())
                                             .multiply(BigDecimal.valueOf(lot.qty()));
                unrealized = unrealized.add(gain);
            }
        }
        return unrealized.setScale(2, RoundingMode.HALF_UP);
    }

    /** Returns realized + unrealized P&L. */
    public BigDecimal getTotalPnL(String symbol) {
        return getRealizedPnL(symbol).add(getUnrealizedPnL(symbol));
    }

    /**
     * Returns the current simulated market price for a symbol.
     * Initialized to the first trade price, then random-walked.
     */
    public BigDecimal getCurrentMarketPrice(String symbol) {
        return marketPrices.getOrDefault(symbol, BigDecimal.ZERO);
    }

    /** Returns all symbols with tracked P&L. */
    public Set<String> getTrackedSymbols() {
        return Collections.unmodifiableSet(lots.keySet());
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void addLot(String symbol, long qty, BigDecimal price) {
        lots.computeIfAbsent(symbol, k -> new ArrayDeque<>());
        Deque<Lot> symbolLots = lots.get(symbol);
        synchronized (symbolLots) {
            symbolLots.addLast(new Lot(qty, price));
        }
    }

    private void realizeSell(String symbol, long sellQty, BigDecimal sellPrice) {
        Deque<Lot> symbolLots = lots.computeIfAbsent(symbol, k -> new ArrayDeque<>());
        BigDecimal realized = BigDecimal.ZERO;
        long remaining = sellQty;

        synchronized (symbolLots) {
            while (remaining > 0 && !symbolLots.isEmpty()) {
                Lot front = symbolLots.peekFirst();
                long matched = Math.min(front.qty(), remaining);
                BigDecimal pnl = sellPrice.subtract(front.costBasis())
                                         .multiply(BigDecimal.valueOf(matched));
                realized  = realized.add(pnl);
                remaining -= matched;

                if (matched == front.qty()) {
                    symbolLots.pollFirst(); // lot fully consumed
                } else {
                    // Partially consumed — replace with reduced lot
                    symbolLots.pollFirst();
                    symbolLots.addFirst(new Lot(front.qty() - matched, front.costBasis()));
                }
            }
        }

        if (remaining > 0) {
            log.warn("Short sell detected for {}: {} shares have no cost basis", symbol, remaining);
        }

        realizedPnL.merge(symbol, realized.setScale(2, RoundingMode.HALF_UP), BigDecimal::add);
    }

    /**
     * Advances the market price using a ±1% bounded random walk.
     * Initialized to the trade price on first encounter.
     */
    private void updateMarketPrice(String symbol, BigDecimal seedPrice) {
        marketPrices.compute(symbol, (k, current) -> {
            if (current == null) return seedPrice;
            double change = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * 0.01;
            BigDecimal factor = BigDecimal.ONE.add(BigDecimal.valueOf(change))
                                              .setScale(6, RoundingMode.HALF_UP);
            return current.multiply(factor).setScale(2, RoundingMode.HALF_UP)
                          .max(BigDecimal.valueOf(0.01)); // floor at $0.01
        });
    }
}
