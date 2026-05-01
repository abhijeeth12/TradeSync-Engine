package com.tradesync.model;

import java.math.BigDecimal;

/**
 * Represents an aggregated position for a symbol within a ledger book.
 */
public class LedgerPosition {

    private final String bookName;
    private final String symbol;
    private long quantity;
    private BigDecimal avgPrice;

    public LedgerPosition(String bookName, String symbol, long quantity, BigDecimal avgPrice) {
        this.bookName = bookName;
        this.symbol   = symbol;
        this.quantity = quantity;
        this.avgPrice = avgPrice;
    }

    public String getBookName()     { return bookName; }
    public String getSymbol()       { return symbol; }
    public long getQuantity()       { return quantity; }
    public BigDecimal getAvgPrice() { return avgPrice; }

    public void setQuantity(long quantity)       { this.quantity = quantity; }
    public void setAvgPrice(BigDecimal avgPrice) { this.avgPrice = avgPrice; }

    @Override
    public String toString() {
        return "LedgerPosition{" + bookName + " | " + symbol +
               " qty=" + quantity + " avg=" + avgPrice + '}';
    }
}
