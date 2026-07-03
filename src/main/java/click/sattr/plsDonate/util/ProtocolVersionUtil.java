package click.sattr.plsDonate.util;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Utility for detecting per-player client protocol versions.
 * Uses ViaVersion API when available; falls back to assuming same version as server.
 */
public final class ProtocolVersionUtil {

    // Minecraft 1.21.6 protocol version number
    private static final int PROTOCOL_1_21_6 = 771;

    private static boolean viaVersionPresent = false;
    private static boolean initialized = false;

    private ProtocolVersionUtil() {}

    /**
     * Call once during onEnable() to detect ViaVersion.
     */
    public static void init(Plugin plugin) {
        if (initialized) return;
        initialized = true;
        viaVersionPresent = plugin.getServer().getPluginManager().getPlugin("ViaVersion") != null;
        // ViaVersion logging is handled in PlsDonate.onEnable via PluginLogger after langConfig is ready
    }

    /**
     * Returns the client protocol version for the given player.
     * If ViaVersion is present, returns the actual client version;
     * otherwise assumes the player matches the server version.
     */
    public static int getPlayerProtocolVersion(Player player) {
        if (viaVersionPresent) {
            try {
                return com.viaversion.viaversion.api.Via.getAPI().getPlayerVersion(player.getUniqueId());
            } catch (Throwable t) {
                // ViaVersion present but API call failed — assume latest protocol
                return Integer.MAX_VALUE;
            }
        }
        // No ViaVersion: assume player protocol matches server (dialog-capable)
        return PROTOCOL_1_21_6;
    }

    /**
     * Returns true if this player's client supports Java Edition dialogs (1.21.6+ / protocol 771+).
     */
    public static boolean supportsDialogs(Player player) {
        return getPlayerProtocolVersion(player) >= PROTOCOL_1_21_6;
    }
}
