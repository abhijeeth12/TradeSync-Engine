package com.tradesync.reconciliation;

import com.tradesync.db.LedgerRepository;
import com.tradesync.db.ReconciliationRepository;
import com.tradesync.model.LedgerPosition;
import com.tradesync.model.ReconciliationReport;
import com.tradesync.model.ReconciliationReport.BreakType;
import com.tradesync.model.ReconciliationReport.PositionDiff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scheduled job that compares InternalBook vs ExternalBook positions
 * and produces a ReconciliationReport with MATCHED / BREAK / MISSING entries.
 *
 * Runs every N seconds on a single-threaded ScheduledExecutorService.
 * The latest report is held in an AtomicReference for zero-copy REST reads.
 */
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final InternalBook internalBook;
    private final ExternalBook externalBook;
    private final LedgerRepository ledgerRepo;
    private final ReconciliationRepository reconRepo;
    private final int intervalSeconds;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "reconciliation-scheduler"));

    private final AtomicReference<ReconciliationReport> latestReport =
            new AtomicReference<>();

    public ReconciliationJob(InternalBook internalBook,
                             ExternalBook externalBook,
                             LedgerRepository ledgerRepo,
                             ReconciliationRepository reconRepo,
                             int intervalSeconds) {
        this.internalBook    = internalBook;
        this.externalBook    = externalBook;
        this.ledgerRepo      = ledgerRepo;
        this.reconRepo       = reconRepo;
        this.intervalSeconds = intervalSeconds;
    }

    public void start() {
        // Run immediately and then every N seconds
        scheduler.scheduleAtFixedRate(this::runReconciliation,
                0, intervalSeconds, TimeUnit.SECONDS);
        log.info("ReconciliationJob scheduled every {}s", intervalSeconds);
    }

    public void stop() {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Returns the most recently produced report (may be null before first run). */
    public ReconciliationReport getLatestReport() {
        return latestReport.get();
    }

    // ── Core reconciliation logic ─────────────────────────────────────────────

    private void runReconciliation() {
        log.info("Starting reconciliation run...");
        try {
            externalBook.reload();

            Map<String, LedgerPosition> internal = internalBook.getPositions();
            Map<String, LedgerPosition> external = externalBook.getPositions();

            List<PositionDiff> diffs = new ArrayList<>();
            Set<String> allSymbols  = new HashSet<>();
            allSymbols.addAll(internal.keySet());
            allSymbols.addAll(external.keySet());

            for (String symbol : allSymbols) {
                LedgerPosition intPos = internal.get(symbol);
                LedgerPosition extPos = external.get(symbol);

                if (intPos != null && extPos != null) {
                    long diff = intPos.getQuantity() - extPos.getQuantity();
                    BreakType type = (diff == 0) ? BreakType.MATCHED : BreakType.BREAK;
                    diffs.add(new PositionDiff(symbol, type,
                                               intPos.getQuantity(),
                                               extPos.getQuantity(),
                                               diff));
                } else if (intPos != null) {
                    // Exists internally but not in external
                    diffs.add(new PositionDiff(symbol, BreakType.MISSING,
                                               intPos.getQuantity(), null,
                                               intPos.getQuantity()));
                } else {
                    // Exists externally but not internally
                    diffs.add(new PositionDiff(symbol, BreakType.MISSING,
                                               null, extPos.getQuantity(),
                                               -extPos.getQuantity()));
                }
            }

            ReconciliationReport report = new ReconciliationReport(Instant.now(), diffs);
            latestReport.set(report);

            // Persist to DB
            reconRepo.save(report);

            // Sync internal ledger positions to DB
            for (LedgerPosition pos : internal.values()) {
                ledgerRepo.upsert(pos);
            }

            log.info("Reconciliation complete: matched={}, breaks={}, missing={}",
                     report.getMatched(), report.getBreaks(), report.getMissing());

        } catch (Exception e) {
            log.error("Reconciliation run failed", e);
        }
    }
}
