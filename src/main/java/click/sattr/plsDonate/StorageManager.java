package click.sattr.plsDonate;

import java.io.File;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class StorageManager {
    private final PlsDonate plugin;
    private Connection connection;
    private final File databaseFile;

    public StorageManager(PlsDonate plugin) {
        this.plugin = plugin;
        this.databaseFile = new File(plugin.getDataFolder(), "database.db");
        initDatabase();
    }

    private synchronized void initDatabase() {
        try {
            if (connection != null && !connection.isClosed()) return;
            
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());

            try (Statement s = connection.createStatement()) {
                // Transactions Table
                s.execute("CREATE TABLE IF NOT EXISTS transactions (" +
                        "id TEXT PRIMARY KEY, " +
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
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize SQLite database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // --- Transactions Methods ---

    public void createDonationRequest(String txId, double amount, String name, boolean isSandbox) {
        CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO transactions (id, amount, donor_name, checksum, status, timestamp, is_sandbox) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, txId);
                ps.setDouble(2, amount);
                ps.setString(3, name);
                ps.setString(4, calculateMD5(txId + amount + name));
                ps.setString(5, "PENDING");
                ps.setLong(6, System.currentTimeMillis() / 1000L);
                ps.setInt(7, isSandbox ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to create donation request in DB: " + e.getMessage());
            }
        });
    }

    public synchronized boolean isTransactionValid(String txId, double amount, String name) {
        String sql = "SELECT status, checksum FROM transactions WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, txId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String status = rs.getString("status");
                    if (!"PENDING".equals(status)) return false;

                    String storedChecksum = rs.getString("checksum");
                    String currentChecksum = calculateMD5(txId + amount + name);
                    return currentChecksum.equals(storedChecksum);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public void markTransactionUsed(String txId) {
        CompletableFuture.runAsync(() -> {
            String sql = "UPDATE transactions SET status = 'COMPLETED', completed_at = ? WHERE id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, System.currentTimeMillis() / 1000L);
                ps.setString(2, txId);
                ps.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });
    }

    // --- Offline Triggers Methods ---

    public void insertOfflineTrigger(String player, String command) {
        CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO offline_triggers (player, command) VALUES (?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, player.toLowerCase());
                ps.setString(2, command);
                ps.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });
    }

    public synchronized List<String> getAndRemoveOfflineTriggers(String player) {
        List<String> commands = new ArrayList<>();
        String selectSql = "SELECT id, command FROM offline_triggers WHERE player = ?";
        String deleteSql = "DELETE FROM offline_triggers WHERE id = ?";

        try (PreparedStatement psSelect = connection.prepareStatement(selectSql)) {
            psSelect.setString(1, player.toLowerCase());
            try (ResultSet rs = psSelect.executeQuery()) {
                while (rs.next()) {
                    commands.add(rs.getString("command"));
                    try (PreparedStatement psDelete = connection.prepareStatement(deleteSql)) {
                        psDelete.setInt(1, rs.getInt("id"));
                        psDelete.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return commands;
    }

    // --- Leaderboard & Milestone (Local Calculations) ---

    public synchronized List<LeaderboardEntry> getLeaderboard(int limit) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        String sql = "SELECT donor_name, SUM(amount) as total_amount FROM transactions " +
                     "WHERE status = 'COMPLETED' AND is_sandbox = 0 " +
                     "GROUP BY donor_name ORDER BY total_amount DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("donor_name");
                    double amount = rs.getDouble("total_amount");
                    entries.add(new LeaderboardEntry(name, amount, plugin.formatIndonesianNumber(amount)));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return entries;
    }

    public synchronized double getTotalDonations() {
        String sql = "SELECT SUM(amount) FROM transactions WHERE status = 'COMPLETED' AND is_sandbox = 0";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getDouble(1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private String calculateMD5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(input.getBytes());
            BigInteger no = new BigInteger(1, messageDigest);
            String hashtext = no.toString(16);
            while (hashtext.length() < 32) {
                hashtext = "0" + hashtext;
            }
            return hashtext;
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    public record LeaderboardEntry(String name, double amount, String amountFormatted) {}
}
