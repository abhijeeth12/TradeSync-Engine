package com.tradesync.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tradesync.model.ReconciliationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.Optional;

/**
 * JDBC repository for reconciliation_runs.
 */
public class ReconciliationRepository {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRepository.class);
    private final DatabaseManager db;
    private final ObjectMapper mapper;

    public ReconciliationRepository(DatabaseManager db) {
        this.db = db;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
    }

    /** Persists a reconciliation report. */
    public void save(ReconciliationReport report) {
        String sql = """
                INSERT INTO reconciliation_runs (run_at, matched, breaks, missing, report_json)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(report.getRunAt()));
            ps.setInt(2, report.getMatched());
            ps.setInt(3, report.getBreaks());
            ps.setInt(4, report.getMissing());
            ps.setString(5, mapper.writeValueAsString(report));
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("Failed to save reconciliation report", e);
            throw new RuntimeException(e);
        }
    }

    /** Returns the most recent reconciliation report as a JSON string. */
    public Optional<String> findLatestJson() {
        String sql = """
                SELECT report_json FROM reconciliation_runs
                ORDER BY run_at DESC LIMIT 1
                """;
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return Optional.ofNullable(rs.getString("report_json"));
        } catch (SQLException e) {
            log.error("Failed to fetch latest reconciliation report", e);
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    /** Returns count of completed reconciliation runs. */
    public long count() {
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM reconciliation_runs")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
