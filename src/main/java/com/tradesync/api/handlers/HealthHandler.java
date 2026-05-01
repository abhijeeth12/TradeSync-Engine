package com.tradesync.api.handlers;

import com.tradesync.db.DatabaseManager;
import io.javalin.http.Context;

import java.util.Map;

/**
 * GET /health
 * Returns application health status including DB connectivity and uptime.
 * Does not require API key authentication.
 */
public class HealthHandler {

    private final DatabaseManager dbManager;
    private final long startTime = System.currentTimeMillis();

    public HealthHandler(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void getHealth(Context ctx) {
        boolean dbOk  = dbManager.isHealthy();
        long uptime   = (System.currentTimeMillis() - startTime) / 1000;
        String status = dbOk ? "UP" : "DEGRADED";

        ctx.status(dbOk ? 200 : 503).json(Map.of(
            "status",         status,
            "database",       dbOk ? "UP" : "DOWN",
            "uptimeSeconds",  uptime,
            "version",        "1.0.0"
        ));
    }
}
