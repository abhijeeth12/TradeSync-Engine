package com.tradesync.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable core trade event DTO.
 * Produced by TradeProducer and consumed through the processing pipeline.
 */
public class TradeEvent {

    private final UUID tradeId;
    private final String symbol;
    private final long quantity;
    private final BigDecimal price;
    private final TradeSide side;
    private final Instant timestamp;
    private volatile TradeState state;

    public TradeEvent(UUID tradeId, String symbol, long quantity,
                      BigDecimal price, TradeSide side, Instant timestamp) {
        this.tradeId   = tradeId;
        this.symbol    = symbol;
        this.quantity  = quantity;
        this.price     = price;
        this.side      = side;
        this.timestamp = timestamp;
        this.state     = TradeState.RECEIVED;
    }

    /** Factory used by the REST ingest endpoint */
    public static TradeEvent of(String symbol, long quantity, BigDecimal price, TradeSide side) {
        return new TradeEvent(UUID.randomUUID(), symbol, quantity, price, side, Instant.now());
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public UUID getTradeId()      { return tradeId; }
    public String getSymbol()     { return symbol; }
    public long getQuantity()     { return quantity; }
    public BigDecimal getPrice()  { return price; }
    public TradeSide getSide()    { return side; }
    public Instant getTimestamp() { return timestamp; }
    public TradeState getState()  { return state; }

    /** Package-visible setter — only OrderStateMachine should call this */
    public void setState(TradeState state) { this.state = state; }

    @Override
    public String toString() {
        return "TradeEvent{" +
               "id=" + tradeId +
               ", symbol='" + symbol + '\'' +
               ", qty=" + quantity +
               ", price=" + price +
               ", side=" + side +
               ", state=" + state +
               '}';
    }
}
