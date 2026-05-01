package com.tradesync.reconciliation;

import com.tradesync.model.LedgerPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Simulates an external trade ledger loaded from a CSV file.
 * The CSV format: symbol,quantity,avg_price
 *
 * The external book is reloaded from the classpath resource on each
 * reconciliation cycle, simulating a file feed that gets refreshed
 * by an external counterparty.
 */
public class ExternalBook {

    private static final Logger log = LoggerFactory.getLogger(ExternalBook.class);
    private static final String CSV_RESOURCE = "external_positions.csv";

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private Map<String, LedgerPosition> positions = new HashMap<>();

    public ExternalBook() {
        reload();
    }

    /**
     * Re-reads the CSV file and replaces the internal position map.
     * Thread-safe — acquires write lock during swap.
     */
    public void reload() {
        Map<String, LedgerPosition> loaded = new HashMap<>();
        try (var is = getClass().getClassLoader().getResourceAsStream(CSV_RESOURCE);
             var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) { first = false; continue; } // skip header
                line = line.trim();
                if (line.isBlank()) continue;

                String[] parts = line.split(",");
                if (parts.length < 3) continue;

                String symbol     = parts[0].trim();
                long quantity     = Long.parseLong(parts[1].trim());
                BigDecimal price  = new BigDecimal(parts[2].trim());
                loaded.put(symbol, new LedgerPosition("EXTERNAL", symbol, quantity, price));
            }

            lock.writeLock().lock();
            try {
                this.positions = loaded;
            } finally {
                lock.writeLock().unlock();
            }
            log.debug("ExternalBook reloaded: {} positions", loaded.size());

        } catch (Exception e) {
            log.error("Failed to reload external positions from {}", CSV_RESOURCE, e);
        }
    }

    /** Returns a snapshot of all external positions. */
    public Map<String, LedgerPosition> getPositions() {
        lock.readLock().lock();
        try {
            return Map.copyOf(positions);
        } finally {
            lock.readLock().unlock();
        }
    }
}
