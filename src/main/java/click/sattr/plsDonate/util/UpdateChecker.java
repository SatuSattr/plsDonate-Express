package click.sattr.plsDonate.util;

import click.sattr.plsDonate.PlsDonate;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public final class UpdateChecker {

    private static final String GITHUB_API = "https://api.github.com/repos/SatuSattr/plsDonate/releases/latest";
    private static final String GITHUB_RELEASES = "https://github.com/SatuSattr/plsDonate/releases/tag/";
    private static final long CHECK_INTERVAL_MS = 3_600_000;

    private static @Nullable String latestVersion;
    private static @Nullable String updateUrl;
    private static long lastCheckTimestamp;
    private static boolean checkFailed;

    private UpdateChecker() {}

    /**
     * Checks GitHub Releases API for newer plugin versions. Only fetches if
     * at least 60 minutes have passed since the last fetch.
     * <p>
     * Safe to call from any thread (blocking HTTP call). Callers should wrap
     * in {@code Bukkit.getScheduler().runTaskAsynchronously()} for non-blocking usage.
     *
     * @param plugin the plugin instance
     * @param sender optional CommandSender to notify inline; null for console-only
     */
    public static void checkUpdate(Plugin plugin, @Nullable CommandSender sender) {
        long now = System.currentTimeMillis();
        if (lastCheckTimestamp != 0 && (now - lastCheckTimestamp) < CHECK_INTERVAL_MS) {
            if (sender != null) {
                Map<String, String> p = new HashMap<>();
                p.put(Constants.PREFIX, ((PlsDonate) plugin).getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                MessageUtils.sendLangMessage(sender, (PlsDonate) plugin, "update-skip", p);
            }
            return;
        }

        lastCheckTimestamp = now;
        checkFailed = false;

        if (sender != null) {
            Map<String, String> p = new HashMap<>();
            p.put(Constants.PREFIX, ((PlsDonate) plugin).getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
            MessageUtils.sendLangMessage(sender, (PlsDonate) plugin, "update-checking", p);
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GITHUB_API))
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "plsDonate/" + plugin.getPluginMeta().getVersion())
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                checkFailed = true;
                if (sender != null) {
                    Map<String, String> p = new HashMap<>();
                    p.put(Constants.PREFIX, ((PlsDonate) plugin).getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                    MessageUtils.sendLangMessage(sender, (PlsDonate) plugin, "update-fail", p);
                }
                PluginLogger.warn("Update check failed: HTTP " + response.statusCode());
                return;
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            String tagName = json.get("tag_name").getAsString();
            if (tagName.startsWith("v")) tagName = tagName.substring(1);

            latestVersion = tagName;
            updateUrl = GITHUB_RELEASES + json.get("tag_name").getAsString();

            String currentVersion = plugin.getPluginMeta().getVersion();
            boolean isUpdate = isNewerVersion(latestVersion, currentVersion);

            Map<String, String> placeholders = new HashMap<>();
            String prefix = ((PlsDonate) plugin).getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX);
            placeholders.put(Constants.VERSION, currentVersion);
            placeholders.put(Constants.NEW_VERSION, latestVersion);
            placeholders.put(Constants.URL, updateUrl);

            if (isUpdate) {
                String msgRaw = ((PlsDonate) plugin).getLangConfig().getString("update-available",
                        "{PREFIX} <yellow>Update available: <click:open_url:\"{URL}\"><hover:show_text:\"<gray>Click to download\">{NEW_VERSION}</hover></click> <gray>(current: {VERSION})");
                String msg = resolvePrefix(msgRaw, prefix, placeholders);
                Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage(msg));
                if (sender != null && sender instanceof Player) {
                    sender.sendMessage(MessageUtils.parseMessage(msg));
                }
            } else {
                String msgRaw = ((PlsDonate) plugin).getLangConfig().getString("update-current",
                        "{PREFIX} <green>You are running the latest version ({VERSION}).");
                String msg = resolvePrefix(msgRaw, prefix, placeholders);
                PluginLogger.info(MessageUtils.toLegacy(msg));
                if (sender != null) {
                    sender.sendMessage(MessageUtils.parseMessage(msg));
                }
            }
        } catch (Exception e) {
            checkFailed = true;
            if (sender != null) {
                Map<String, String> p = new HashMap<>();
                p.put(Constants.PREFIX, ((PlsDonate) plugin).getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                MessageUtils.sendLangMessage(sender, (PlsDonate) plugin, "update-fail", p);
            }
            PluginLogger.warn("Update check failed: " + e.getMessage());
        }
    }

    public static @Nullable String getLatestVersion() {
        return latestVersion;
    }

    public static @Nullable String getUpdateUrl() {
        return updateUrl;
    }

    public static boolean hasChecked() {
        return lastCheckTimestamp != 0 && !checkFailed;
    }

    public static boolean isUpdateAvailable(String currentVersion) {
        if (latestVersion == null) return false;
        return isNewerVersion(latestVersion, currentVersion);
    }

    private static boolean isNewerVersion(String remote, String current) {
        try {
            String[] remoteParts = remote.split("-")[0].split("\\.");
            String[] currentParts = current.split("-")[0].split("\\.");

            int maxLen = Math.max(remoteParts.length, currentParts.length);
            for (int i = 0; i < maxLen; i++) {
                int r = i < remoteParts.length ? Integer.parseInt(remoteParts[i]) : 0;
                int c = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                if (r > c) return true;
                if (r < c) return false;
            }

            // Same numeric version, compare prerelease
            String remotePrerelease = remote.contains("-") ? remote.substring(remote.indexOf("-")) : "";
            String currentPrerelease = current.contains("-") ? current.substring(current.indexOf("-")) : "";

            if (remotePrerelease.isEmpty() && !currentPrerelease.isEmpty()) return true;
            if (!remotePrerelease.isEmpty() && currentPrerelease.isEmpty()) return false;
            return remotePrerelease.compareTo(currentPrerelease) > 0;
        } catch (NumberFormatException e) {
            return remote.compareTo(current) > 0;
        }
    }

    private static String resolvePrefix(String raw, String prefix, Map<String, String> placeholders) {
        String result = raw;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        result = result.replace(Constants.PREFIX, prefix);
        return result;
    }
}
