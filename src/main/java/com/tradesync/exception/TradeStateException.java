package com.tradesync.exception;

/**
 * Checked exception thrown when an illegal state transition is attempted
 * on a TradeEvent (e.g., MATCHED → RECEIVED).
 */
public class TradeStateException extends Exception {

    public TradeStateException(String message) {
        super(message);
    }

    public TradeStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
