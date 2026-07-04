package click.sattr.plsDonate.util;

import click.sattr.plsDonate.PlsDonate;
import org.bukkit.Bukkit;

import java.util.HashMap;
import java.util.Map;

/**
 * Centralized logging utility that routes all plugin log messages through colored
 * MiniMessage console output when langConfig is available, falling back to plain
 * Bukkit logger during pre-init phases.
 * 
 * <p>Call {@link #init(PlsDonate)} as the very first line of {@code onEnable()} before
 * any other initialization. All subsystems can then use the static methods:
 * <ul>
 *   <li>{@link #info(String)} — neutral/gray info messages</li>
 *   <li>{@link #warn(String)} — yellow warning messages</li>
 *   <li>#severe(String)} — red error messages</li>
 * </ul>
 * 
 * <p>The colored prefix is read from {@code langConfig.prefix} at log time, so reloads
 * automatically pick up prefix changes without recreating any managers.
 */
public final class PluginLogger {

    private static PlsDonate plugin;

    private PluginLogger() {}

    /**
     * Initialize the logger with the plugin instance. Call this as the very first
     * line of {@code PlsDonate.onEnable()} before any other subsystems are created.
     */
    public static void init(PlsDonate pluginInstance) {
        plugin = pluginInstance;
    }

    /**
     * Logs an informational message. If langConfig is available, outputs colored
     * MiniMessage to console with plugin prefix. Otherwise falls back to plain logger.
     */
    public static void info(String message) {
        log(message, Level.INFO);
    }

    /**
     * Logs a warning message in yellow. If langConfig is available, outputs colored
     * MiniMessage to console with plugin prefix. Otherwise falls back to plain logger.
     */
    public static void warn(String message) {
        log(message, Level.WARN);
    }

    /**
     * Logs a severe/error message in red. If langConfig is available, outputs colored
     * MiniMessage to console with plugin prefix. Otherwise falls back to plain logger.
     */
    public static void severe(String message) {
        log(message, Level.SEVERE);
    }

    private static void log(String message, Level level) {
        if (plugin == null) {
            // Pre-init fallback — should never happen if init() is called first
            System.err.println("[plsDonate] Logger not initialized: " + message);
            return;
        }

        // Check if langConfig is ready (it's loaded after init in onEnable)
        if (plugin.getLangConfig() != null) {
            try {
                String prefix = plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX);
                String coloredMessage = buildColoredMessage(prefix, message, level);
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put(Constants.PREFIX, prefix);
                Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage(coloredMessage, placeholders));
                return;
            } catch (Exception e) {
                // Fall through to plain logger if MiniMessage parsing fails
            }
        }

        // Fallback: plain Bukkit logger (used during early onEnable before langConfig loads)
        switch (level) {
            case INFO -> plugin.getLogger().info(message);
            case WARN -> plugin.getLogger().warning(message);
            case SEVERE -> plugin.getLogger().severe(message);
        }
    }

    private static String buildColoredMessage(String prefix, String message, Level level) {
        return switch (level) {
            case INFO -> "{PREFIX} <gray>" + message;
            case WARN -> "{PREFIX} <yellow>" + message;
            case SEVERE -> "{PREFIX} <red>" + message;
        };
    }

    private enum Level {
        INFO, WARN, SEVERE
    }
}
