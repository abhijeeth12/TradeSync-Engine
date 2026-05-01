package com.tradesync.model;

import com.tradesync.exception.TradeStateException;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Enum-based Finite State Machine for trade lifecycle.
 *
 * <pre>
 *   RECEIVED → VALIDATED → MATCHED → SETTLED
 *       ↘                           ↗
 *                  FAILED
 * </pre>
 *
 * FAILED is a terminal absorbing state (no transitions out).
 */
public enum TradeState {

    RECEIVED {
        @Override
        public Set<TradeState> allowedTransitions() {
            return EnumSet.of(VALIDATED, FAILED);
        }
    },
    VALIDATED {
        @Override
        public Set<TradeState> allowedTransitions() {
            return EnumSet.of(MATCHED, FAILED);
        }
    },
    MATCHED {
        @Override
        public Set<TradeState> allowedTransitions() {
            return EnumSet.of(SETTLED, FAILED);
        }
    },
    SETTLED {
        @Override
        public Set<TradeState> allowedTransitions() {
            return EnumSet.noneOf(TradeState.class); // terminal
        }
    },
    FAILED {
        @Override
        public Set<TradeState> allowedTransitions() {
            return EnumSet.noneOf(TradeState.class); // terminal
        }
    };

    /**
     * Returns the set of states this state may legally transition to.
     */
    public abstract Set<TradeState> allowedTransitions();

    /**
     * Validates and returns the target state.
     *
     * @throws TradeStateException if the transition is not allowed
     */
    public TradeState transitionTo(TradeState target) throws TradeStateException {
        if (!allowedTransitions().contains(target)) {
            throw new TradeStateException(
                String.format("Illegal state transition: %s → %s. Allowed: %s",
                              this, target, allowedTransitions())
            );
        }
        return target;
    }
}
