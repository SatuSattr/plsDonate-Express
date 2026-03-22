package click.sattr.plsDonate.database.repository;

import click.sattr.plsDonate.PlsDonate;
import click.sattr.plsDonate.database.DatabaseManager;
import click.sattr.plsDonate.util.MessageUtils;

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

    public void createDonationRequest(String txId, double amount, String name, boolean isSandbox) {
        CompletableFuture.runAsync(() -> {
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
                plugin.getLogger().severe("Failed to create donation request in DB: " + e.getMessage());
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
            plugin.getLogger().severe("Error validating transaction " + txId + ": " + e.getMessage());
        }
        return false;
    }

    public void markTransactionUsed(String txId) {
        CompletableFuture.runAsync(() -> {
            String sql = "UPDATE transactions SET status = 'COMPLETED', completed_at = ? WHERE tx_id = ?";
            try (Connection conn = databaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, System.currentTimeMillis() / 1000L);
                ps.setString(2, txId);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to mark transaction " + txId + " as used: " + e.getMessage());
            }
        });
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
            plugin.getLogger().severe("Failed to fetch leaderboard: " + e.getMessage());
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
            plugin.getLogger().severe("Failed to fetch leaderboard count: " + e.getMessage());
        }
        return 0;
    }

    public List<TransactionRecord> getTransactions(int limit, int offset) {
        List<TransactionRecord> records = new ArrayList<>();
        String sql = "SELECT * FROM transactions ORDER BY id DESC LIMIT ? OFFSET ?";
        try (Connection conn = databaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapResultSetToRecord(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to fetch transactions: " + e.getMessage());
        }
        return records;
    }

    public int getTransactionsCount() {
        String sql = "SELECT COUNT(*) FROM transactions";
        try (Connection conn = databaseManager.getConnection(); Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to count transactions: " + e.getMessage());
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
            plugin.getLogger().severe("Failed to fetch transaction by id: " + e.getMessage());
        }
        return null;
    }

    public boolean deleteTransaction(int id) {
        String sql = "DELETE FROM transactions WHERE id = ?";
        try (Connection conn = databaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to delete transaction: " + e.getMessage());
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
            plugin.getLogger().severe("Failed to update transaction status: " + e.getMessage());
            return false;
        }
    }

    public void clearTransactions(String player) {
        String sql = player.equalsIgnoreCase("all") ? "DELETE FROM transactions" : "DELETE FROM transactions WHERE donor_name = ?";
        try (Connection conn = databaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            if (!player.equalsIgnoreCase("all")) ps.setString(1, player);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to clear transactions: " + e.getMessage());
        }
    }

    private TransactionRecord mapResultSetToRecord(ResultSet rs) throws SQLException {
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
            plugin.getLogger().severe("Failed to fetch total donations: " + e.getMessage());
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
            plugin.getLogger().severe("Error checking sandbox status for transaction " + txId + ": " + e.getMessage());
        }
        return false;
    }

    public double getPlayerTotal(String playerName) {
        String sql = "SELECT SUM(amount) FROM transactions WHERE donor_name = ? AND status = 'COMPLETED' AND is_sandbox = 0";
        try (Connection conn = databaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to get player total: " + e.getMessage());
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
            plugin.getLogger().severe("Failed to get player rank: " + e.getMessage());
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
            plugin.getLogger().severe("Failed to get recent donation: " + e.getMessage());
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
