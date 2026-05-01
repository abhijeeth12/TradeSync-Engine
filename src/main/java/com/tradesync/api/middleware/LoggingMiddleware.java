package com.tradesync.api.middleware;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Before/after handler pair that logs each request with method, path,
 * status code, and elapsed time.
 */
public class LoggingMiddleware implements Handler {

    private static final Logger log = LoggerFactory.getLogger(LoggingMiddleware.class);
    private static final String ATTR_START = "req.start";

    /** Call this as a beforeHandler to record start time. */
    public static Handler before() {
        return ctx -> ctx.attribute(ATTR_START, System.nanoTime());
    }

    /** Call this as an afterHandler to log the completed request. */
    public static Handler after() {
        return ctx -> {
            Long start = ctx.attribute(ATTR_START);
            long elapsedMs = start == null ? -1 : (System.nanoTime() - start) / 1_000_000;
            log.info("{} {} → {} ({}ms) [{}]",
                     ctx.method(), ctx.path(), ctx.status(), elapsedMs, ctx.ip());
        };
    }

    @Override
    public void handle(Context ctx) throws Exception {
        // no-op — use static factories before() / after()
    }
}
