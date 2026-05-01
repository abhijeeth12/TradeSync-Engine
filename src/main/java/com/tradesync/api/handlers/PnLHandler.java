package com.tradesync.api.handlers;

import com.tradesync.pnl.PnLCalculator;
import io.javalin.http.Context;

import java.util.Map;

/**
 * GET /pnl/{symbol}
 * Returns realized, unrealized, and total P&L for a symbol,
 * plus the current simulated market price.
 */
public class PnLHandler {

    private final PnLCalculator pnlCalculator;

    public PnLHandler(PnLCalculator pnlCalculator) {
        this.pnlCalculator = pnlCalculator;
    }

    public void getPnL(Context ctx) {
        String symbol = ctx.pathParam("symbol").toUpperCase();

        if (!pnlCalculator.getTrackedSymbols().contains(symbol)) {
            ctx.status(404).json(Map.of(
                "error",  "Symbol not found",
                "symbol", symbol,
                "hint",   "No settled trades exist for this symbol yet"
            ));
            return;
        }

        ctx.json(Map.of(
            "symbol",        symbol,
            "realized",      pnlCalculator.getRealizedPnL(symbol),
            "unrealized",    pnlCalculator.getUnrealizedPnL(symbol),
            "total",         pnlCalculator.getTotalPnL(symbol),
            "marketPrice",   pnlCalculator.getCurrentMarketPrice(symbol)
        ));
    }
}
