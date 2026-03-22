package click.sattr.plsDonate.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import click.sattr.plsDonate.PlsDonate;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {

    private final PlsDonate plugin;
    private HikariDataSource dataSource;
    private final File databaseFile;

    public DatabaseManager(PlsDonate plugin) {
        this.plugin = plugin;
        this.databaseFile = new File(plugin.getDataFolder(), "database.db");
        setupPool();
        initTables();
    }

    private void setupPool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setPoolName("plsDonate-Pool");
        
        // SQLite specific optimizations
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setIdleTimeout(300000); // 5 minutes
        config.setMaxLifetime(600000); // 10 minutes
        config.setConnectionTimeout(5000); // 5 seconds

        this.dataSource = new HikariDataSource(config);
    }

    private void initTables() {
        try (Connection conn = getConnection(); Statement s = conn.createStatement()) {
            // Transactions Table
            s.execute("CREATE TABLE IF NOT EXISTS transactions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "tx_id TEXT UNIQUE, " +
                    "amount REAL, " +
                    "donor_name TEXT, " +
                    "checksum TEXT, " +
                    "status TEXT, " +
                    "timestamp INTEGER, " +
                    "completed_at INTEGER, " +
                    "is_sandbox INTEGER DEFAULT 0)");

            // Offline Triggers Table
            s.execute("CREATE TABLE IF NOT EXISTS offline_triggers (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "player TEXT, " +
                    "command TEXT)");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize database tables: " + e.getMessage());
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
