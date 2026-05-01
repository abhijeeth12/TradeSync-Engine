package com.tradesync;

import com.tradesync.api.ApiServer;
import com.tradesync.api.handlers.*;
import com.tradesync.benchmark.LoadSimulator;
import com.tradesync.config.AppConfig;
import com.tradesync.db.*;
import com.tradesync.ingestion.TradeIngestionService;
import com.tradesync.metrics.MetricsRegistry;
import com.tradesync.orderbook.OrderBook;
import com.tradesync.pnl.PnLCalculator;
import com.tradesync.reconciliation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Application entry point.
 *
 * Modes:
 *   (default)           — Start the full engine (ingestion + API + reconciliation)
 *   --benchmark         — Run the LoadSimulator and exit
 *   --threads=N         — Benchmark threads (default 20)
 *   --trades=M          — Benchmark trades per thread (default 500)
 *   --no-ingestion      — Start API server only (no background producers)
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        List<String> argList = Arrays.asList(args);
        boolean benchmark    = argList.contains("--benchmark");
        boolean noIngestion  = argList.contains("--no-ingestion");

        int benchThreads = parseArg(argList, "--threads", 20);
        int benchTrades  = parseArg(argList, "--trades",  500);

        log.info("╔══════════════════════════════════╗");
        log.info("║   TradeSync Engine v1.0.0        ║");
        log.info("╚══════════════════════════════════╝");

        // ── Infrastructure ─────────────────────────────────────────────────
        AppConfig cfg = AppConfig.get();
        DatabaseManager dbManager = DatabaseManager.getInstance();
        dbManager.initSchema();

        // ── Repositories ───────────────────────────────────────────────────
        TradeRepository          tradeRepo  = new TradeRepository(dbManager);
        AuditRepository          auditRepo  = new AuditRepository(dbManager);
        LedgerRepository         ledgerRepo = new LedgerRepository(dbManager);
        ReconciliationRepository reconRepo  = new ReconciliationRepository(dbManager);

        // ── Core components ────────────────────────────────────────────────
        OrderBook      orderBook     = new OrderBook();
        PnLCalculator  pnlCalc       = new PnLCalculator();
        MetricsRegistry metrics      = new MetricsRegistry();

        // ── Benchmark mode ─────────────────────────────────────────────────
        if (benchmark) {
            log.info("Running in BENCHMARK mode: {} threads × {} trades",
                     benchThreads, benchTrades);
            // Create a temporary ingestion service to get the queue
            TradeIngestionService svc = new TradeIngestionService(
                    tradeRepo, auditRepo, orderBook, pnlCalc, metrics);
            svc.start();
            metrics.setQueue(svc.getQueue());
            new LoadSimulator(svc.getQueue()).run(benchThreads, benchTrades);
            // Wait a moment for consumers to drain
            Thread.sleep(5_000);
            svc.shutdown();
            dbManager.close();
            log.info("Benchmark complete. Exiting.");
            return;
        }

        // ── Reconciliation ─────────────────────────────────────────────────
        InternalBook   internalBook = new InternalBook(orderBook);
        ExternalBook   externalBook = new ExternalBook();
        int reconInterval = cfg.getInt("reconciliation.interval.seconds", 30);

        ReconciliationJob reconJob = new ReconciliationJob(
                internalBook, externalBook, ledgerRepo, reconRepo, reconInterval);
        reconJob.start();

        // ── Ingestion pipeline ─────────────────────────────────────────────
        TradeIngestionService ingestionSvc = null;
        if (!noIngestion) {
            ingestionSvc = new TradeIngestionService(
                    tradeRepo, auditRepo, orderBook, pnlCalc, metrics);
            ingestionSvc.start();
            metrics.setQueue(ingestionSvc.getQueue());
        } else {
            log.info("Ingestion disabled (--no-ingestion flag)");
        }

        // ── REST API ────────────────────────────────────────────────────────
        // For REST-ingested trades, use a shared StateMachine
        com.tradesync.statemachine.OrderStateMachine sharedFsm =
                new com.tradesync.statemachine.OrderStateMachine(tradeRepo, auditRepo);

        TradeHandler          tradeHandler   = new TradeHandler(tradeRepo, auditRepo, sharedFsm);
        ReconciliationHandler reconHandler   = new ReconciliationHandler(reconJob, reconRepo);
        PnLHandler            pnlHandler     = new PnLHandler(pnlCalc);
        MetricsHandler        metricsHandler = new MetricsHandler(metrics);
        HealthHandler         healthHandler  = new HealthHandler(dbManager);

        ApiServer apiServer = new ApiServer(
                tradeHandler, reconHandler, pnlHandler, metricsHandler, healthHandler);
        apiServer.start();

        // ── Shutdown hook ───────────────────────────────────────────────────
        final TradeIngestionService finalIngestion = ingestionSvc;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown initiated...");
            apiServer.stop();
            reconJob.stop();
            if (finalIngestion != null) finalIngestion.shutdown();
            dbManager.close();
            log.info("TradeSync Engine stopped cleanly.");
        }, "shutdown-hook"));

        log.info("TradeSync Engine fully started. Press Ctrl+C to stop.");
    }

    // ── CLI argument parser ─────────────────────────────────────────────────

    private static int parseArg(List<String> args, String prefix, int defaultValue) {
        return args.stream()
                   .filter(a -> a.startsWith(prefix + "="))
                   .map(a -> Integer.parseInt(a.split("=", 2)[1]))
                   .findFirst()
                   .orElse(defaultValue);
    }
}
