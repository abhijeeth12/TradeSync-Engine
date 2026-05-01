package com.tradesync.api.handlers;

import com.tradesync.metrics.MetricsRegistry;
import io.javalin.http.Context;

/**
 * GET /metrics
 * Returns operational counters: queue depth, trades processed,
 * average processing time, error count, and uptime.
 */
public class MetricsHandler {

    private final MetricsRegistry metrics;

    public MetricsHandler(MetricsRegistry metrics) {
        this.metrics = metrics;
    }

    public void getMetrics(Context ctx) {
        ctx.json(metrics.snapshot());
    }
}
