package com.tradesync.db;

import com.tradesync.config.AppConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;

/**
 * Manages the HikariCP connection pool and initializes the schema on startup.
 */
public class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
    private static DatabaseManager instance;
    private final HikariDataSource dataSource;

    private DatabaseManager(AppConfig cfg) {
        HikariConfig hk = new HikariConfig();
        hk.setJdbcUrl(cfg.getString("db.url"));
        hk.setUsername(cfg.getString("db.username"));
        hk.setPassword(cfg.getString("db.password"));
        hk.setMaximumPoolSize(cfg.getInt("db.pool.size", 20));
        hk.setMinimumIdle(5);
        hk.setConnectionTimeout(30_000);
        hk.setIdleTimeout(600_000);
        hk.setMaxLifetime(1_800_000);
        hk.setPoolName("TradeSync-Pool");
        hk.setAutoCommit(true);
        // Validation query
        hk.setConnectionTestQuery("SELECT 1");

        dataSource = new HikariDataSource(hk);
        log.info("HikariCP pool initialized (maxSize={})", cfg.getInt("db.pool.size", 20));
    }

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager(AppConfig.get());
        }
        return instance;
    }

    /** Returns a connection from the pool. Caller must close it. */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /** Executes schema.sql DDL on startup — idempotent (IF NOT EXISTS). */
    public void initSchema() {
        String sql;
        try (var is = getClass().getClassLoader().getResourceAsStream("schema.sql")) {
            if (is == null) throw new RuntimeException("schema.sql not found on classpath");
            sql = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read schema.sql", e);
        }

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            log.info("Database schema initialized successfully");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize schema", e);
        }
    }

    /** Checks DB connectivity — used by /health endpoint. */
    public boolean isHealthy() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
            return true;
        } catch (SQLException e) {
            log.error("DB health check failed", e);
            return false;
        }
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("HikariCP pool closed");
        }
    }
}
