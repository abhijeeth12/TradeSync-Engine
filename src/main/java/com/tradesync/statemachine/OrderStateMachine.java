package com.tradesync.statemachine;

import com.tradesync.db.AuditRepository;
import com.tradesync.db.TradeRepository;
import com.tradesync.exception.TradeStateException;
import com.tradesync.model.TradeEvent;
import com.tradesync.model.TradeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates state transitions on TradeEvent objects.
 *
 * Responsibilities:
 * 1. Validates the transition via the TradeState FSM guard
 * 2. Updates the in-memory state on the TradeEvent
 * 3. Persists the new state to the trades table
 * 4. Appends a record to trade_audit
 */
public class OrderStateMachine {

    private static final Logger log = LoggerFactory.getLogger(OrderStateMachine.class);

    private final TradeRepository tradeRepo;
    private final AuditRepository auditRepo;

    public OrderStateMachine(TradeRepository tradeRepo, AuditRepository auditRepo) {
        this.tradeRepo = tradeRepo;
        this.auditRepo = auditRepo;
    }

    /**
     * Attempts to transition a trade to the given target state.
     *
     * @param trade  the trade to transition
     * @param target desired next state
     * @param reason human-readable reason (stored in audit log)
     * @throws TradeStateException if the transition is not permitted
     */
    public void transition(TradeEvent trade, TradeState target, String reason)
            throws TradeStateException {
        TradeState from = trade.getState();

        // Guard condition — throws TradeStateException on invalid path
        from.transitionTo(target);

        // Apply in-memory
        trade.setState(target);

        // Persist new state
        tradeRepo.updateState(trade.getTradeId(), target);

        // Audit trail
        auditRepo.record(trade.getTradeId(), from, target, reason);

        log.debug("Trade {} transitioned {} → {} ({})",
                  trade.getTradeId(), from, target, reason);
    }

    /**
     * Convenience: marks a trade as FAILED with a given reason.
     * This is a terminal state — no further transitions are possible.
     */
    public void fail(TradeEvent trade, String reason) {
        try {
            transition(trade, TradeState.FAILED, reason);
        } catch (TradeStateException e) {
            // Already in a terminal state — log and move on
            log.warn("Cannot fail trade {} already in state {}: {}",
                     trade.getTradeId(), trade.getState(), e.getMessage());
        }
    }

    /**
     * Runs a trade through the full happy-path pipeline:
     * RECEIVED → VALIDATED → MATCHED → SETTLED
     *
     * Fails the trade on any exception.
     */
    public void processHappyPath(TradeEvent trade) {
        try {
            transition(trade, TradeState.VALIDATED, "Validation passed");
            transition(trade, TradeState.MATCHED,   "Match found");
            transition(trade, TradeState.SETTLED,   "Settlement complete");
        } catch (TradeStateException e) {
            log.error("Unexpected state transition failure for {}: {}",
                      trade.getTradeId(), e.getMessage());
            fail(trade, "Unexpected FSM error: " + e.getMessage());
        }
    }
}
