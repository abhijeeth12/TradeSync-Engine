package com.tradesync.db;

import com.tradesync.model.TradeEvent;
import com.tradesync.model.TradeSide;
import com.tradesync.model.TradeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Raw JDBC repository for the trades table.
 * All SQL uses parameterized PreparedStatements — no string concatenation.
 */
public class TradeRepository {

    private static final Logger log = LoggerFactory.getLogger(TradeRepository.class);
    private final DatabaseManager db;

    public TradeRepository(DatabaseManager db) {
        this.db = db;
    }

    /** Inserts a new trade record. */
    public void insert(TradeEvent trade) {
        String sql = """
                INSERT INTO trades (id, symbol, quantity, price, side, state, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, trade.getTradeId());
            ps.setString(2, trade.getSymbol());
            ps.setLong(3, trade.getQuantity());
            ps.setBigDecimal(4, trade.getPrice());
            ps.setString(5, trade.getSide().name());
            ps.setString(6, trade.getState().name());
            ps.setTimestamp(7, Timestamp.from(trade.getTimestamp()));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to insert trade {}", trade.getTradeId(), e);
            throw new RuntimeException(e);
        }
    }

    /** Updates the state column of a trade row. */
    public void updateState(UUID tradeId, TradeState state) {
        String sql = "UPDATE trades SET state = ? WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state.name());
            ps.setObject(2, tradeId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update state for trade {}", tradeId, e);
            throw new RuntimeException(e);
        }
    }

    /** Fetches a paginated list of trades ordered by created_at DESC. */
    public List<TradeEvent> findAll(int page, int size) {
        String sql = """
                SELECT id, symbol, quantity, price, side, state, created_at
                FROM trades
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """;
        List<TradeEvent> results = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, size);
            ps.setInt(2, page * size);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to fetch trades", e);
            throw new RuntimeException(e);
        }
        return results;
    }

    /** Finds a single trade by its UUID. */
    public Optional<TradeEvent> findById(UUID id) {
        String sql = """
                SELECT id, symbol, quantity, price, side, state, created_at
                FROM trades WHERE id = ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to find trade {}", id, e);
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    public long count() {
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM trades")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private TradeEvent mapRow(ResultSet rs) throws SQLException {
        UUID id         = rs.getObject("id", UUID.class);
        String symbol   = rs.getString("symbol");
        long qty        = rs.getLong("quantity");
        BigDecimal price= rs.getBigDecimal("price");
        TradeSide side  = TradeSide.valueOf(rs.getString("side"));
        TradeState state= TradeState.valueOf(rs.getString("state"));
        Instant ts      = rs.getTimestamp("created_at").toInstant();

        TradeEvent t = new TradeEvent(id, symbol, qty, price, side, ts);
        t.setState(state);
        return t;
    }
}
