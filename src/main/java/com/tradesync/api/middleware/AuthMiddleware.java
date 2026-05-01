package com.tradesync.api.middleware;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Before-handler that enforces API key authentication via the X-API-Key header.
 * Returns 401 if the header is absent or does not match the configured key.
 */
public class AuthMiddleware implements Handler {

    private static final Logger log = LoggerFactory.getLogger(AuthMiddleware.class);
    private final String requiredKey;

    public AuthMiddleware(String requiredKey) {
        this.requiredKey = requiredKey;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        // Allow health endpoint without auth
        if (ctx.path().equals("/health")) return;

        String key = ctx.header("X-API-Key");
        if (key == null || !key.equals(requiredKey)) {
            log.warn("Unauthorized request to {} from {}", ctx.path(), ctx.ip());
            ctx.status(401).json(java.util.Map.of(
                "error",   "Unauthorized",
                "message", "Missing or invalid X-API-Key header"
            ));
            ctx.skipRemainingHandlers(); // stop processing
        }
    }
}
