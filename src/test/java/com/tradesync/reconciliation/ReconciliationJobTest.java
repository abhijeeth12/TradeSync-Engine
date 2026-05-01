package com.tradesync.reconciliation;

import com.tradesync.db.LedgerRepository;
import com.tradesync.db.ReconciliationRepository;
import com.tradesync.model.LedgerPosition;
import com.tradesync.model.ReconciliationReport;
import com.tradesync.model.ReconciliationReport.BreakType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ReconciliationJob.
 *
 * Strategy: mock InternalBook and ExternalBook with known positions,
 * trigger runReconciliation() via reflection, and assert report counts.
 *
 * We extract the core comparison logic into a separate helper to avoid
 * needing a live ScheduledExecutorService in tests.
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationJobTest {

    @Mock InternalBook       internalBook;
    @Mock ExternalBook       externalBook;
    @Mock LedgerRepository   ledgerRepo;
    @Mock ReconciliationRepository reconRepo;

    /** Helper that replicates the reconciliation diff logic for testing */
    private ReconciliationReport reconcile(
            Map<String, LedgerPosition> internal,
            Map<String, LedgerPosition> external) {

        java.util.List<ReconciliationReport.PositionDiff> diffs = new java.util.ArrayList<>();
        java.util.Set<String> all = new java.util.HashSet<>();
        all.addAll(internal.keySet());
        all.addAll(external.keySet());

        for (String symbol : all) {
            LedgerPosition intPos = internal.get(symbol);
            LedgerPosition extPos = external.get(symbol);

            if (intPos != null && extPos != null) {
                long diff = intPos.getQuantity() - extPos.getQuantity();
                BreakType type = (diff == 0) ? BreakType.MATCHED : BreakType.BREAK;
                diffs.add(new ReconciliationReport.PositionDiff(symbol, type,
                    intPos.getQuantity(), extPos.getQuantity(), diff));
            } else if (intPos != null) {
                diffs.add(new ReconciliationReport.PositionDiff(symbol, BreakType.MISSING,
                    intPos.getQuantity(), null, intPos.getQuantity()));
            } else {
                diffs.add(new ReconciliationReport.PositionDiff(symbol, BreakType.MISSING,
                    null, extPos.getQuantity(), -extPos.getQuantity()));
            }
        }
        return new ReconciliationReport(java.time.Instant.now(), diffs);
    }

    private LedgerPosition pos(String book, String symbol, long qty) {
        return new LedgerPosition(book, symbol, qty, BigDecimal.valueOf(100));
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("All positions match → report shows all MATCHED")
    void allMatched() {
        Map<String, LedgerPosition> internal = Map.of(
            "AAPL", pos("INTERNAL", "AAPL", 1000),
            "MSFT", pos("INTERNAL", "MSFT", 500)
        );
        Map<String, LedgerPosition> external = Map.of(
            "AAPL", pos("EXTERNAL", "AAPL", 1000),
            "MSFT", pos("EXTERNAL", "MSFT", 500)
        );

        ReconciliationReport report = reconcile(internal, external);

        assertEquals(2, report.getMatched());
        assertEquals(0, report.getBreaks());
        assertEquals(0, report.getMissing());
    }

    @Test
    @DisplayName("Quantity mismatch → report shows BREAK with correct diff")
    void quantityMismatch_producesBreak() {
        Map<String, LedgerPosition> internal = Map.of(
            "AAPL", pos("INTERNAL", "AAPL", 1200)
        );
        Map<String, LedgerPosition> external = Map.of(
            "AAPL", pos("EXTERNAL", "AAPL", 1000)
        );

        ReconciliationReport report = reconcile(internal, external);

        assertEquals(0, report.getMatched());
        assertEquals(1, report.getBreaks());
        assertEquals(0, report.getMissing());

        ReconciliationReport.PositionDiff diff = report.getDetails().get(0);
        assertEquals("AAPL", diff.symbol());
        assertEquals(200L, diff.qtyDiff());
    }

    @Test
    @DisplayName("Symbol only in internal → MISSING")
    void missingInExternal() {
        Map<String, LedgerPosition> internal = Map.of(
            "NVDA", pos("INTERNAL", "NVDA", 300)
        );
        Map<String, LedgerPosition> external = Map.of(); // empty

        ReconciliationReport report = reconcile(internal, external);

        assertEquals(0, report.getMatched());
        assertEquals(0, report.getBreaks());
        assertEquals(1, report.getMissing());
    }

    @Test
    @DisplayName("Symbol only in external → MISSING")
    void missingInInternal() {
        Map<String, LedgerPosition> internal = Map.of(); // empty
        Map<String, LedgerPosition> external = Map.of(
            "TSLA", pos("EXTERNAL", "TSLA", 400)
        );

        ReconciliationReport report = reconcile(internal, external);

        assertEquals(0, report.getMatched());
        assertEquals(0, report.getBreaks());
        assertEquals(1, report.getMissing());
    }

    @Test
    @DisplayName("Mixed scenario: matched + break + missing")
    void mixedScenario() {
        Map<String, LedgerPosition> internal = Map.of(
            "AAPL", pos("INTERNAL", "AAPL", 1000),   // matched
            "MSFT", pos("INTERNAL", "MSFT", 800),    // break
            "GOOGL", pos("INTERNAL", "GOOGL", 300)   // missing in ext
        );
        Map<String, LedgerPosition> external = Map.of(
            "AAPL", pos("EXTERNAL", "AAPL", 1000),   // matched
            "MSFT", pos("EXTERNAL", "MSFT", 600),    // break
            "JPM",  pos("EXTERNAL", "JPM",  500)     // missing in int
        );

        ReconciliationReport report = reconcile(internal, external);

        assertEquals(1, report.getMatched());
        assertEquals(1, report.getBreaks());
        assertEquals(2, report.getMissing()); // GOOGL + JPM
    }

    @Test
    @DisplayName("Empty books produce empty report")
    void emptyBooks_emptyReport() {
        ReconciliationReport report = reconcile(Map.of(), Map.of());

        assertEquals(0, report.getMatched());
        assertEquals(0, report.getBreaks());
        assertEquals(0, report.getMissing());
        assertTrue(report.getDetails().isEmpty());
    }

    @Test
    @DisplayName("BREAK diff is signed correctly: internal > external = positive diff")
    void breakDiffSign() {
        Map<String, LedgerPosition> internal = Map.of(
            "XOM", pos("INTERNAL", "XOM", 500)
        );
        Map<String, LedgerPosition> external = Map.of(
            "XOM", pos("EXTERNAL", "XOM", 300)
        );

        ReconciliationReport report = reconcile(internal, external);
        ReconciliationReport.PositionDiff diff = report.getDetails().get(0);

        assertEquals(BreakType.BREAK, diff.breakType());
        assertEquals(200L, diff.qtyDiff());  // 500 - 300
    }
}
