package com.tradesync.api.handlers;

import com.tradesync.db.ReconciliationRepository;
import com.tradesync.model.ReconciliationReport;
import com.tradesync.reconciliation.ReconciliationJob;
import io.javalin.http.Context;

import java.util.Map;

/**
 * GET /reconciliation/latest
 * Returns the most recent reconciliation report, first from the in-memory
 * AtomicReference (fast path), falling back to the DB.
 */
public class ReconciliationHandler {

    private final ReconciliationJob reconJob;
    private final ReconciliationRepository reconRepo;

    public ReconciliationHandler(ReconciliationJob reconJob,
                                 ReconciliationRepository reconRepo) {
        this.reconJob  = reconJob;
        this.reconRepo = reconRepo;
    }

    public void getLatest(Context ctx) {
        // Fast path: in-memory latest report
        ReconciliationReport report = reconJob.getLatestReport();
        if (report != null) {
            ctx.json(Map.of(
                "runAt",   report.getRunAt().toString(),
                "matched", report.getMatched(),
                "breaks",  report.getBreaks(),
                "missing", report.getMissing(),
                "details", report.getDetails()
            ));
            return;
        }

        // Fallback: DB
        reconRepo.findLatestJson().ifPresentOrElse(
            json -> ctx.contentType("application/json").result(json),
            ()   -> ctx.status(404).json(Map.of(
                        "message", "No reconciliation run has completed yet"))
        );
    }
}
