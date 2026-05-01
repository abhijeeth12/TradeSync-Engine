package com.tradesync.model;

import java.time.Instant;
import java.util.List;

/**
 * Output of a reconciliation run comparing InternalBook vs ExternalBook.
 */
public class ReconciliationReport {

    public enum BreakType { MATCHED, BREAK, MISSING }

    public record PositionDiff(
        String symbol,
        BreakType breakType,
        Long internalQty,
        Long externalQty,
        Long qtyDiff
    ) {}

    private final Instant runAt;
    private final int matched;
    private final int breaks;
    private final int missing;
    private final List<PositionDiff> details;

    public ReconciliationReport(Instant runAt, List<PositionDiff> details) {
        this.runAt   = runAt;
        this.details = List.copyOf(details);
        int m = 0, b = 0, miss = 0;
        for (PositionDiff d : details) {
            switch (d.breakType()) {
                case MATCHED -> m++;
                case BREAK   -> b++;
                case MISSING -> miss++;
            }
        }
        this.matched = m;
        this.breaks  = b;
        this.missing = miss;
    }

    public Instant getRunAt()           { return runAt; }
    public int getMatched()             { return matched; }
    public int getBreaks()              { return breaks; }
    public int getMissing()             { return missing; }
    public List<PositionDiff> getDetails() { return details; }

    @Override
    public String toString() {
        return "ReconciliationReport{matched=" + matched +
               ", breaks=" + breaks + ", missing=" + missing + '}';
    }
}
