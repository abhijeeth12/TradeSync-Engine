package com.tradesync.db;

import com.tradesync.model.LedgerPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC repository for the ledger_positions table.
 */
public class LedgerRepository {

    private static final Logger log = LoggerFactory.getLogger(LedgerRepository.class);
    private final DatabaseManager db;

    public LedgerRepository(DatabaseManager db) {
        this.db = db;
    }

    /** Upserts a position for a (book_name, symbol) pair. */
    public void upsert(LedgerPosition pos) {
        String sql = """
                INSERT INTO ledger_positions (book_name, symbol, quantity, avg_price, updated_at)
                VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT (book_name, symbol) DO UPDATE
                  SET quantity  = EXCLUDED.quantity,
                      avg_price = EXCLUDED.avg_price,
                      updated_at= NOW()
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pos.getBookName());
            ps.setString(2, pos.getSymbol());
            ps.setLong(3, pos.getQuantity());
            ps.setBigDecimal(4, pos.getAvgPrice());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to upsert ledger position {}", pos, e);
            throw new RuntimeException(e);
        }
    }

    /** Returns all positions for a given book. */
    public List<LedgerPosition> findByBook(String bookName) {
        String sql = """
                SELECT book_name, symbol, quantity, avg_price
                FROM ledger_positions WHERE book_name = ?
                """;
        List<LedgerPosition> list = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, bookName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new LedgerPosition(
                        rs.getString("book_name"),
                        rs.getString("symbol"),
                        rs.getLong("quantity"),
                        rs.getBigDecimal("avg_price")
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to find ledger positions for book {}", bookName, e);
            throw new RuntimeException(e);
        }
        return list;
    }
}
