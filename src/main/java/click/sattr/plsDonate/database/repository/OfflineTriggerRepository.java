package click.sattr.plsDonate.database.repository;

import click.sattr.plsDonate.PlsDonate;
import click.sattr.plsDonate.database.DatabaseManager;
import click.sattr.plsDonate.util.PluginLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class OfflineTriggerRepository {

    private final PlsDonate plugin;
    private final DatabaseManager databaseManager;

    public OfflineTriggerRepository(PlsDonate plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    public void insertOfflineTrigger(String player, String command) {
        CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO offline_triggers (player, command) VALUES (?, ?)";
            try (Connection conn = databaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, player.toLowerCase());
                ps.setString(2, command);
                ps.executeUpdate();
            } catch (SQLException e) {
                PluginLogger.severe("Failed to insert offline trigger for " + player + ": " + e.getMessage());
            }
        });
    }

    public List<String> getAndRemoveOfflineTriggers(String player) {
        List<String> commands = new ArrayList<>();
        String selectSql = "SELECT id, command FROM offline_triggers WHERE player = ?";
        String deleteSql = "DELETE FROM offline_triggers WHERE id = ?";

        try (Connection conn = databaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement psSelect = conn.prepareStatement(selectSql)) {
                psSelect.setString(1, player.toLowerCase());
                try (ResultSet rs = psSelect.executeQuery()) {
                    while (rs.next()) {
                        commands.add(rs.getString("command"));
                        try (PreparedStatement psDelete = conn.prepareStatement(deleteSql)) {
                            psDelete.setInt(1, rs.getInt("id"));
                            psDelete.executeUpdate();
                        }
                    }
                }
            }
            conn.commit();
        } catch (SQLException e) {
            PluginLogger.severe("Error retrieving/removing offline triggers for " + player + ": " + e.getMessage());
        }
        return commands;
    }
}
