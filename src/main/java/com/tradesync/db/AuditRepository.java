package com.tradesync.db;

import com.tradesync.model.TradeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes and reads the trade_audit table.
 * Every state transition is persisted here for a full audit trail.
 */
public class AuditRepository {

    private static final Logger log = LoggerFactory.getLogger(AuditRepository.class);
    private final DatabaseManager db;

    public AuditRepository(DatabaseManager db) {
        this.db = db;
    }

    /** Records a state transition in the audit log. */
    public void record(UUID tradeId, TradeState from, TradeState to, String reason) {
        String sql = """
                INSERT INTO trade_audit (trade_id, from_state, to_state, ts, reason)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, tradeId);
            ps.setString(2, from == null ? null : from.name());
            ps.setString(3, to.name());
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.setString(5, reason);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to write audit record for trade {}", tradeId, e);
            throw new RuntimeException(e);
        }
    }

    /** Returns the full audit trail for a given trade, ordered by ts ASC. */
    public List<Map<String, Object>> getAuditTrail(UUID tradeId) {
        String sql = """
                SELECT from_state, to_state, ts, reason
                FROM trade_audit
                WHERE trade_id = ?
                ORDER BY ts ASC
                """;
        List<Map<String, Object>> trail = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, tradeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    trail.add(Map.of(
                        "fromState", rs.getString("from_state") == null ? "" : rs.getString("from_state"),
                        "toState",   rs.getString("to_state"),
                        "ts",        rs.getTimestamp("ts").toInstant().toString(),
                        "reason",    rs.getString("reason") == null ? "" : rs.getString("reason")
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to fetch audit trail for {}", tradeId, e);
            throw new RuntimeException(e);
        }
        return trail;
    }
}
