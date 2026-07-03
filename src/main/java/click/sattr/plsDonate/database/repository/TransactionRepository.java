package click.sattr.plsDonate.database.repository;

import click.sattr.plsDonate.PlsDonate;
import click.sattr.plsDonate.database.DatabaseManager;
import click.sattr.plsDonate.util.MessageUtils;
import click.sattr.plsDonate.util.PluginLogger;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class TransactionRepository {

    private final PlsDonate plugin;
    private final DatabaseManager databaseManager;

    public TransactionRepository(PlsDonate plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    public java.util.concurrent.CompletableFuture<Void> createDonationRequest(String txId, double amount, String name, boolean isSandbox) {
        return java.util.concurrent.CompletableFuture.runAsync(() -> {
            String sql = "INSERT OR IGNORE INTO transactions (tx_id, amount, donor_name, checksum, status, timestamp, is_sandbox) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (Connection conn = databaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, txId);
                ps.setDouble(2, amount);
                ps.setString(3, name);
                ps.setString(4, calculateMD5(txId + amount + name));
                ps.setString(5, "PENDING");
                ps.setLong(6, System.currentTimeMillis() / 1000L);
                ps.setInt(7, isSandbox ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException e) {
                PluginLogger.severe("Failed to create donation request in DB: " + e.getMessage());
            }
        });
    }

    public boolean isTransactionValid(String txId, double amount, String name) {
        String sql = "SELECT status, checksum FROM transactions WHERE tx_id = ?";
        try (Connection conn = databaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
            PluginLogger.severe("Error validating transaction " + txId + ": " + e.getMessage());
        }
        return false;
    }

    public java.util.concurrent.CompletableFuture<Void> markTransactionUsed(String txId) {
        return java.util.concurrent.CompletableFuture.runAsync(() -> {
            try (Connection conn = databaseManager.getConnection()) {
                claimPending(conn, txId);
            } catch (SQLException e) {
                PluginLogger.severe("Failed to mark transaction " + txId + " as used: " + e.getMessage());
            }
        });
    }

    /**
     * Synchronously claims a PENDING transaction for fulfillment. Returns true only for the
     * single caller that transitions the row from PENDING to COMPLETED, so concurrent
     * duplicate webhooks for the same transaction cannot both be fulfilled (replay guard).
     */
    public boolean claimTransaction(String txId) {
        try (Connection conn = databaseManager.getConnection()) {
            return claimPending(conn, txId) == 1;
        } catch (SQLException e) {
            PluginLogger.severe("Failed to claim transaction " + txId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Atomic compare-and-set: marks the transaction COMPLETED only if it is currently PENDING.
     * Returns the number of rows transitioned (1 if claimed by this call, 0 otherwise).
     */
    static int claimPending(Connection conn, String txId) throws SQLException {
        String sql = "UPDATE transactions SET status = 'COMPLETED', completed_at = ? WHERE tx_id = ? AND status = 'PENDING'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis() / 1000L);
            ps.setString(2, txId);
            return ps.executeUpdate();
        }
    }

    public List<LeaderboardEntry> getLeaderboard(int limit) {
        return getLeaderboard(limit, 0);
    }

    public List<LeaderboardEntry> getLeaderboard(int limit, int offset) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        String sql = "SELECT donor_name, SUM(amount) as total_amount FROM transactions " +
                     "WHERE status = 'COMPLETED' AND is_sandbox = 0 " +
                     "GROUP BY donor_name ORDER BY total_amount DESC LIMIT ? OFFSET ?";
        try (Connection conn = databaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("donor_name");
                    double amount = rs.getDouble("total_amount");
                    entries.add(new LeaderboardEntry(name, amount, MessageUtils.formatAmount(plugin, amount)));
                }
            }
        } catch (SQLException e) {
            PluginLogger.severe("Failed to fetch leaderboard: " + e.getMessage());
        }
        return entries;
    }

    public int getLeaderboardCount() {
        String sql = "SELECT COUNT(DISTINCT donor_name) FROM transactions WHERE status = 'COMPLETED' AND is_sandbox = 0";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            PluginLogger.severe("Failed to fetch leaderboard count: " + e.getMessage());
        }
        return 0;
    }

    public List<TransactionRecord> getTransactions(int limit, int offset) {
        List<TransactionRecord> records = new ArrayList<>();
        String sql = "SELECT * FROM transactions WHERE is_sandbox = 0 ORDER BY id DESC LIMIT ? OFFSET ?";
        try (Connection conn = databaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapResultSetToRecord(rs));
                }
            }
        } catch (SQLException e) {
            PluginLogger.severe("Failed to fetch transactions: " + e.getMessage());
        }
        return records;
    }

    public int getTransactionsCount() {
        String sql = "SELECT COUNT(*) FROM transactions WHERE is_sandbox = 0";
        try (Connection conn = databaseManager.getConnection(); Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            PluginLogger.severe("Failed to count transactions: " + e.getMessage());
        }
        return 0;
    }

    public TransactionRecord getTransactionById(int id) {
        String sql = "SELECT * FROM transactions WHERE id = ?";
        try (Connection conn = databaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapResultSetToRecord(rs);
            }
        } catch (SQLException e) {
            PluginLogger.severe("Failed to fetch transaction by id: " + e.getMessage());
        }
        return null;
    }

    public boolean deleteTransaction(int id) {
        String sql = "DELETE FROM transactions WHERE id = ?";
        try (Connection conn = databaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            PluginLogger.severe("Failed to delete transaction: " + e.getMessage());
            return false;
        }
    }

    public boolean updateTransactionStatus(int id, String status) {
        String sql = "UPDATE transactions SET status = ?, completed_at = ? WHERE id = ?";
        try (Connection conn = databaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, "COMPLETED".equalsIgnoreCase(status) ? System.currentTimeMillis() / 1000L : 0);
            ps.setInt(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            PluginLogger.severe("Failed to update transaction status: " + e.getMessage());
            return false;
        }
    }

    public void clearTransactions(String player) {
        String sql = player.equalsIgnoreCase("all") ? "DELETE FROM transactions" : "DELETE FROM transactions WHERE donor_name = ?";
        try (Connection conn = databaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            if (!player.equalsIgnoreCase("all")) ps.setString(1, player);
            ps.executeUpdate();
        } catch (SQLException e) {
            PluginLogger.severe("Failed to clear transactions: " + e.getMessage());
        }
    }

    private static TransactionRecord mapResultSetToRecord(ResultSet rs) throws SQLException {
        return new TransactionRecord(
                rs.getInt("id"),
                rs.getString("tx_id"),
                rs.getDouble("amount"),
                rs.getString("donor_name"),
                rs.getString("checksum"),
                rs.getString("status"),
                rs.getLong("timestamp"),
                rs.getLong("completed_at"),
                rs.getInt("is_sandbox") == 1
        );
    }

    public double getTotalDonations() {
        String sql = "SELECT SUM(amount) FROM transactions WHERE status = 'COMPLETED' AND is_sandbox = 0";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getDouble(1);
            }
        } catch (SQLException e) {
            PluginLogger.severe("Failed to fetch total donations: " + e.getMessage());
        }
        return 0;
    }

    public boolean isSandboxTransaction(String txId) {
        String sql = "SELECT is_sandbox FROM transactions WHERE tx_id = ?";
        try (Connection conn = databaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, txId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("is_sandbox") == 1;
                }
            }
        } catch (SQLException e) {
            PluginLogger.severe("Error checking sandbox status for transaction " + txId + ": " + e.getMessage());
        }
        return false;
    }

    public double getPlayerTotal(String playerName) {
        String sql = "SELECT SUM(amount) FROM transactions WHERE donor_name = ? COLLATE NOCASE AND status = 'COMPLETED' AND is_sandbox = 0";
        try (Connection conn = databaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble(1);
            }
        } catch (SQLException e) {
            PluginLogger.severe("Failed to get player total: " + e.getMessage());
        }
        return 0;
    }

    public int getPlayerRank(String playerName) {
        String sql = "SELECT donor_name, SUM(amount) as total FROM transactions WHERE status = 'COMPLETED' AND is_sandbox = 0 GROUP BY donor_name ORDER BY total DESC";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            int rank = 1;
            while (rs.next()) {
                if (rs.getString("donor_name").equalsIgnoreCase(playerName)) return rank;
                rank++;
            }
        } catch (SQLException e) {
            PluginLogger.severe("Failed to get player rank: " + e.getMessage());
        }
        return 0;
    }

    /** Paginated donation history for a single player. Excludes sandbox and only shows
     *  payable states (COMPLETED + PENDING); VOID is hidden. Most recent first. */
    public List<TransactionRecord> getPlayerHistory(String playerName, int limit, int offset) {
        try (Connection conn = databaseManager.getConnection()) {
            return queryPlayerHistory(conn, playerName, limit, offset);
        } catch (SQLException e) {
            PluginLogger.severe("Failed to fetch player history: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public int getPlayerHistoryCount(String playerName) {
        try (Connection conn = databaseManager.getConnection()) {
            return queryPlayerHistoryCount(conn, playerName);
        } catch (SQLException e) {
            PluginLogger.severe("Failed to count player history: " + e.getMessage());
            return 0;
        }
    }

    static List<TransactionRecord> queryPlayerHistory(Connection conn, String playerName, int limit, int offset) throws SQLException {
        List<TransactionRecord> records = new ArrayList<>();
        String sql = "SELECT * FROM transactions " +
                "WHERE donor_name = ? COLLATE NOCASE AND is_sandbox = 0 " +
                "AND status IN ('COMPLETED', 'PENDING') " +
                "ORDER BY timestamp DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerName);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapResultSetToRecord(rs));
                }
            }
        }
        return records;
    }

    static int queryPlayerHistoryCount(Connection conn, String playerName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM transactions " +
                "WHERE donor_name = ? COLLATE NOCASE AND is_sandbox = 0 " +
                "AND status IN ('COMPLETED', 'PENDING')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 0;
    }

    public LeaderboardEntry getRecentDonation() {
        String sql = "SELECT donor_name, amount FROM transactions WHERE status = 'COMPLETED' AND is_sandbox = 0 ORDER BY completed_at DESC LIMIT 1";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                double amount = rs.getDouble("amount");
                return new LeaderboardEntry(rs.getString("donor_name"), amount, MessageUtils.formatAmount(plugin, amount));
            }
        } catch (SQLException e) {
            PluginLogger.severe("Failed to get recent donation: " + e.getMessage());
        }
        return null;
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
    public record TransactionRecord(int id, String txId, double amount, String donorName, String checksum, String status, long timestamp, long completedAt, boolean isSandbox) {}
}
