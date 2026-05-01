package com.tradesync.reconciliation;

import com.tradesync.model.LedgerPosition;
import com.tradesync.orderbook.OrderBook;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Derives the internal ledger from the live OrderBook.
 * Each call to getPositions() takes a consistent read-lock snapshot.
 */
public class InternalBook {

    private final OrderBook orderBook;

    public InternalBook(OrderBook orderBook) {
        this.orderBook = orderBook;
    }

    /**
     * Returns a symbol → LedgerPosition map derived from the current
     * state of the order book. Uses the book's read-locked getters.
     */
    public Map<String, LedgerPosition> getPositions() {
        Map<String, LedgerPosition> positions = new HashMap<>();
        for (String symbol : orderBook.getSymbols()) {
            long netQty       = orderBook.getNetQuantity(symbol);
            BigDecimal avgPrice = orderBook.getAvgBuyPrice(symbol);
            if (netQty != 0 || !avgPrice.equals(BigDecimal.ZERO)) {
                positions.put(symbol, new LedgerPosition("INTERNAL", symbol, netQty, avgPrice));
            }
        }
        return positions;
    }
}
