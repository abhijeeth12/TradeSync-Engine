package com.tradesync.statemachine;

import com.tradesync.db.AuditRepository;
import com.tradesync.db.TradeRepository;
import com.tradesync.exception.TradeStateException;
import com.tradesync.model.TradeEvent;
import com.tradesync.model.TradeState;
import com.tradesync.model.TradeSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the OrderStateMachine.
 * All DB repositories are mocked — pure business logic testing.
 */
@ExtendWith(MockitoExtension.class)
class OrderStateMachineTest {

    @Mock TradeRepository tradeRepo;
    @Mock AuditRepository auditRepo;

    OrderStateMachine fsm;

    @BeforeEach
    void setUp() {
        fsm = new OrderStateMachine(tradeRepo, auditRepo);
        // Make mock calls no-ops; lenient because some tests throw before reaching them
        lenient().doNothing().when(tradeRepo).updateState(any(), any());
        lenient().doNothing().when(auditRepo).record(any(), any(), any(), any());
    }

    private TradeEvent newTrade() {
        return TradeEvent.of("AAPL", 100, new BigDecimal("150.00"), TradeSide.BUY);
    }

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Happy path: RECEIVED → VALIDATED → MATCHED → SETTLED")
    void happyPath() throws TradeStateException {
        TradeEvent trade = newTrade();
        assertEquals(TradeState.RECEIVED, trade.getState());

        fsm.transition(trade, TradeState.VALIDATED, "test");
        assertEquals(TradeState.VALIDATED, trade.getState());

        fsm.transition(trade, TradeState.MATCHED, "test");
        assertEquals(TradeState.MATCHED, trade.getState());

        fsm.transition(trade, TradeState.SETTLED, "test");
        assertEquals(TradeState.SETTLED, trade.getState());
    }

    @Test
    @DisplayName("processHappyPath transitions trade all the way to SETTLED")
    void processHappyPath_reachesSettled() {
        TradeEvent trade = newTrade();
        fsm.processHappyPath(trade);
        assertEquals(TradeState.SETTLED, trade.getState());
    }

    @Test
    @DisplayName("RECEIVED can transition to FAILED")
    void receivedToFailed() throws TradeStateException {
        TradeEvent trade = newTrade();
        fsm.transition(trade, TradeState.FAILED, "validation error");
        assertEquals(TradeState.FAILED, trade.getState());
    }

    // ── Invalid transitions ──────────────────────────────────────────────────

    @Test
    @DisplayName("MATCHED → RECEIVED is illegal and throws TradeStateException")
    void matchedToReceived_throws() {
        TradeEvent trade = newTrade();
        // Advance to MATCHED
        assertDoesNotThrow(() -> fsm.transition(trade, TradeState.VALIDATED, "ok"));
        assertDoesNotThrow(() -> fsm.transition(trade, TradeState.MATCHED, "ok"));

        assertThrows(TradeStateException.class,
            () -> fsm.transition(trade, TradeState.RECEIVED, "illegal"));
    }

    @Test
    @DisplayName("SETTLED → anything is illegal (terminal state)")
    void settled_isTerminal() {
        TradeEvent trade = newTrade();
        fsm.processHappyPath(trade);
        assertEquals(TradeState.SETTLED, trade.getState());

        assertThrows(TradeStateException.class,
            () -> fsm.transition(trade, TradeState.VALIDATED, "illegal"));
        assertThrows(TradeStateException.class,
            () -> fsm.transition(trade, TradeState.FAILED, "illegal"));
        assertThrows(TradeStateException.class,
            () -> fsm.transition(trade, TradeState.RECEIVED, "illegal"));
    }

    @Test
    @DisplayName("FAILED → anything is illegal (terminal state)")
    void failed_isTerminal() {
        TradeEvent trade = newTrade();
        assertDoesNotThrow(() -> fsm.transition(trade, TradeState.FAILED, "error"));

        assertThrows(TradeStateException.class,
            () -> fsm.transition(trade, TradeState.VALIDATED, "illegal"));
    }

    @Test
    @DisplayName("RECEIVED → MATCHED skips VALIDATED — should throw")
    void receivedToMatched_skipsValidated_throws() {
        TradeEvent trade = newTrade();
        assertThrows(TradeStateException.class,
            () -> fsm.transition(trade, TradeState.MATCHED, "skip validation"));
    }

    // ── Audit trail ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Each valid transition records one audit entry")
    void auditRecordedPerTransition() throws TradeStateException {
        TradeEvent trade = newTrade();
        fsm.transition(trade, TradeState.VALIDATED, "r1");
        fsm.transition(trade, TradeState.MATCHED,   "r2");
        fsm.transition(trade, TradeState.SETTLED,   "r3");

        verify(auditRepo, times(3)).record(any(), any(), any(), any());
        verify(tradeRepo, times(3)).updateState(any(), any());
    }

    @Test
    @DisplayName("fail() on a trade in RECEIVED state succeeds")
    void fail_fromReceived() {
        TradeEvent trade = newTrade();
        fsm.fail(trade, "Deliberate failure");
        assertEquals(TradeState.FAILED, trade.getState());
    }

    @Test
    @DisplayName("fail() on an already-SETTLED trade is a no-op")
    void fail_fromSettled_isNoOp() {
        TradeEvent trade = newTrade();
        fsm.processHappyPath(trade);
        assertEquals(TradeState.SETTLED, trade.getState());
        // Should not throw — just log a warning
        assertDoesNotThrow(() -> fsm.fail(trade, "Too late"));
        assertEquals(TradeState.SETTLED, trade.getState()); // state unchanged
    }
}
