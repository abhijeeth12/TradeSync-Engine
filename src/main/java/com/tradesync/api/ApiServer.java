package com.tradesync.api;

import com.tradesync.api.handlers.*;
import com.tradesync.api.middleware.AuthMiddleware;
import com.tradesync.api.middleware.LoggingMiddleware;
import com.tradesync.config.AppConfig;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configures and starts the Javalin HTTP server with all routes,
 * middleware, and error handlers.
 */
public class ApiServer {

    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);

    private final Javalin app;
    private final int port;

    public ApiServer(TradeHandler tradeHandler,
                     ReconciliationHandler reconHandler,
                     PnLHandler pnlHandler,
                     MetricsHandler metricsHandler,
                     HealthHandler healthHandler) {

        AppConfig cfg   = AppConfig.get();
        this.port       = cfg.getInt("api.port", 7070);
        String apiKey   = cfg.getString("api.key");

        // Jackson with Java 8 time support
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());

        this.app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(mapper, true));
            config.showJavalinBanner = false;
        });

        // ── Middleware ────────────────────────────────────────────────────────
        app.before(LoggingMiddleware.before());
        app.before(new AuthMiddleware(apiKey));
        app.after(LoggingMiddleware.after());

        // ── Routes ────────────────────────────────────────────────────────────
        app.get("/health",                   healthHandler::getHealth);
        app.get("/trades",                   tradeHandler::listTrades);
        app.get("/trades/{id}",              tradeHandler::getTrade);
        app.post("/trades",                  tradeHandler::ingestTrade);
        app.get("/reconciliation/latest",    reconHandler::getLatest);
        app.get("/pnl/{symbol}",             pnlHandler::getPnL);
        app.get("/metrics",                  metricsHandler::getMetrics);

        // ── Error handlers ────────────────────────────────────────────────────
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled exception on {} {}", ctx.method(), ctx.path(), e);
            ctx.status(500).json(java.util.Map.of(
                "error",   "Internal Server Error",
                "message", e.getMessage() == null ? "Unknown error" : e.getMessage()
            ));
        });

        app.error(404, ctx -> ctx.json(java.util.Map.of(
            "error", "Not Found",
            "path",  ctx.path()
        )));
    }

    public void start() {
        app.start(port);
        log.info("API server started on http://localhost:{}", port);
        log.info("Endpoints: GET /health, /trades, /trades/{{id}}, " +
                 "POST /trades, GET /reconciliation/latest, /pnl/{{symbol}}, /metrics");
    }

    public void stop() {
        app.stop();
        log.info("API server stopped");
    }
}
