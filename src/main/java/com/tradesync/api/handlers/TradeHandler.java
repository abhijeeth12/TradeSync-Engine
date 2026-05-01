package com.tradesync.api.handlers;

import com.tradesync.db.AuditRepository;
import com.tradesync.db.TradeRepository;
import com.tradesync.exception.TradeStateException;
import com.tradesync.model.TradeEvent;
import com.tradesync.model.TradeSide;
import com.tradesync.statemachine.OrderStateMachine;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles:
 *   GET  /trades          — paginated list
 *   GET  /trades/{id}     — single trade + audit trail
 *   POST /trades          — ingest a new trade
 */
public class TradeHandler {

    private static final Logger log = LoggerFactory.getLogger(TradeHandler.class);

    private final TradeRepository tradeRepo;
    private final AuditRepository auditRepo;
    private final OrderStateMachine stateMachine;

    public TradeHandler(TradeRepository tradeRepo,
                        AuditRepository auditRepo,
                        OrderStateMachine stateMachine) {
        this.tradeRepo    = tradeRepo;
        this.auditRepo    = auditRepo;
        this.stateMachine = stateMachine;
    }

    /** GET /trades?page=0&size=20 */
    public void listTrades(Context ctx) {
        int page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(0);
        int size = ctx.queryParamAsClass("size", Integer.class).getOrDefault(20);
        size = Math.min(size, 200); // max 200 per page

        List<TradeEvent> trades = tradeRepo.findAll(page, size);
        long total = tradeRepo.count();

        ctx.json(Map.of(
            "trades", trades.stream().map(this::toMap).toList(),
            "page",   page,
            "size",   size,
            "total",  total
        ));
    }

    /** GET /trades/{id} */
    public void getTrade(Context ctx) {
        String idStr = ctx.pathParam("id");
        UUID id;
        try {
            id = UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", "Invalid UUID: " + idStr));
            return;
        }

        tradeRepo.findById(id).ifPresentOrElse(
            trade -> ctx.json(Map.of(
                "trade",      toMap(trade),
                "auditTrail", auditRepo.getAuditTrail(id)
            )),
            () -> ctx.status(404).json(Map.of("error", "Trade not found: " + id))
        );
    }

    /** POST /trades — body: {symbol, quantity, price, side} */
    public void ingestTrade(Context ctx) {
        Map<String, Object> body;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> b = ctx.bodyAsClass(java.util.HashMap.class);
            body = b;
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON body"));
            return;
        }

        // Validate required fields
        try {
            String symbol  = (String) body.get("symbol");
            long   qty     = ((Number) body.get("quantity")).longValue();
            BigDecimal price = new BigDecimal(body.get("price").toString());
            TradeSide side = TradeSide.valueOf(((String) body.get("side")).toUpperCase());

            if (symbol == null || symbol.isBlank()) {
                ctx.status(400).json(Map.of("error", "symbol is required"));
                return;
            }

            TradeEvent trade = TradeEvent.of(symbol.toUpperCase(), qty, price, side);

            // Persist
            tradeRepo.insert(trade);

            // Audit the initial RECEIVED state
            auditRepo.record(trade.getTradeId(), null,
                             trade.getState(), "Trade ingested via REST API");

            // Drive through FSM on a virtual thread to avoid blocking the handler
            Thread asyncThread = new Thread(() -> {
                try {
                    stateMachine.processHappyPath(trade);
                } catch (Exception e2) {
                    log.error("FSM processing failed for REST-ingested trade", e2);
                }
            });
            asyncThread.setDaemon(true);
            asyncThread.start();

            ctx.status(201).json(Map.of(
                "tradeId", trade.getTradeId().toString(),
                "state",   trade.getState().name(),
                "message", "Trade accepted"
            ));

        } catch (NullPointerException | ClassCastException e) {
            ctx.status(400).json(Map.of("error", "Missing or malformed field: " + e.getMessage()));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", "Invalid side value — must be BUY or SELL"));
        }
    }

    private Map<String, Object> toMap(TradeEvent t) {
        return Map.of(
            "id",        t.getTradeId().toString(),
            "symbol",    t.getSymbol(),
            "quantity",  t.getQuantity(),
            "price",     t.getPrice(),
            "side",      t.getSide().name(),
            "state",     t.getState().name(),
            "timestamp", t.getTimestamp().toString()
        );
    }
}
