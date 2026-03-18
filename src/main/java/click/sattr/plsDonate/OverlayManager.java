package click.sattr.plsDonate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class OverlayManager {

    private final PlsDonate plugin;
    private final HttpClient httpClient;
    
    // Cache variables
    private final AtomicReference<List<LeaderboardEntry>> leaderboardCache = new AtomicReference<>(new ArrayList<>());
    private final AtomicReference<MilestoneData> milestoneCache = new AtomicReference<>(null);

    public OverlayManager(PlsDonate plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean isConfigured() {
        String baseUrl = plugin.getConfig().getString("tako.overlay-api.base-url", "");
        String overlayKey = plugin.getConfig().getString("tako.overlay-api.overlay-key", "");
        return !baseUrl.isEmpty() && !overlayKey.isEmpty();
    }

    // Getters for cached data
    public List<LeaderboardEntry> getCachedLeaderboard() {
        return leaderboardCache.get();
    }

    public MilestoneData getCachedMilestone() {
        return milestoneCache.get();
    }

    /**
     * Updates both leaderboard and milestone data asynchronously.
     * @return A CompletableFuture that completes when both are updated.
     */
    public CompletableFuture<Void> updateCacheAsync() {
        if (!isConfigured()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<List<LeaderboardEntry>> lbFuture = fetchLeaderboard();
        CompletableFuture<MilestoneData> msFuture = fetchMilestone();

        return CompletableFuture.allOf(lbFuture, msFuture).thenAccept(v -> {
            try {
                List<LeaderboardEntry> lb = lbFuture.get();
                if (lb != null) leaderboardCache.set(lb);
                
                MilestoneData ms = msFuture.get();
                if (ms != null) milestoneCache.set(ms);
            } catch (Exception e) {
                plugin.getLogger().severe("Error updating overlay cache: " + e.getMessage());
            }
        });
    }

    private CompletableFuture<List<LeaderboardEntry>> fetchLeaderboard() {
        return fetch("leaderboard").thenApply(json -> {
            List<LeaderboardEntry> entries = new ArrayList<>();
            if (json != null && json.has("rankings") && json.get("rankings").isJsonArray()) {
                JsonArray rankings = json.getAsJsonArray("rankings");
                for (JsonElement element : rankings) {
                    JsonObject obj = element.getAsJsonObject();
                    String name = obj.has("name") ? obj.get("name").getAsString() : "Unknown";
                    
                    double amount = 0;
                    if (obj.has("amount")) {
                        try {
                            amount = obj.get("amount").getAsDouble();
                        } catch (Exception ignored) {}
                    }
                    
                    entries.add(new LeaderboardEntry(name, String.valueOf((long)amount), plugin.formatIndonesianNumber(amount)));
                }
            }
            return entries;
        });
    }

    private CompletableFuture<MilestoneData> fetchMilestone() {
        return fetch("milestone").thenApply(json -> {
            if (json != null && json.has("type") && json.get("type").getAsString().equals("milestone")) {
                String title = json.has("title") ? json.get("title").getAsString() : "No Title";
                String currentStr = json.has("current") ? json.get("current").getAsString() : "0";
                String targetStr = json.has("target") ? json.get("target").getAsString() : "0";
                String startDate = json.has("startDate") ? json.get("startDate").getAsString() : "N/A";
                String startTime = json.has("startTime") ? json.get("startTime").getAsString() : "N/A";
                
                double current = 0;
                double target = 0;
                try {
                    current = Double.parseDouble(currentStr.replaceAll("[^0-9.]", ""));
                    target = Double.parseDouble(targetStr.replaceAll("[^0-9.]", ""));
                } catch (Exception ignored) {}

                return new MilestoneData(
                        title,
                        String.valueOf((long)current),
                        String.valueOf((long)target),
                        plugin.formatIndonesianNumber(current),
                        plugin.formatIndonesianNumber(target),
                        current,
                        target,
                        startDate,
                        startTime
                );
            }
            return null;
        });
    }

    private CompletableFuture<JsonObject> fetch(String type) {
        String baseUrl = plugin.getConfig().getString("tako.overlay-api.base-url", "");
        String apiKey = plugin.getConfig().getString("tako.overlay-api.api-key", "");
        String overlayKey = plugin.getConfig().getString("tako.overlay-api.overlay-key", "");

        if (baseUrl.isEmpty() || overlayKey.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        if (!baseUrl.endsWith("/")) baseUrl += "/";
        String url = baseUrl + "api/scrape?type=" + type + "&overlay_key=" + overlayKey;
        if (!apiKey.isEmpty()) {
            url += "&api_key=" + apiKey;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "plsDonate-Express/" + plugin.getPluginMeta().getVersion())
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return JsonParser.parseString(response.body()).getAsJsonObject();
                    } else {
                        plugin.getLogger().warning("Overlay API (" + type + ") returned status code: " + response.statusCode());
                        return null;
                    }
                }).exceptionally(ex -> {
                    plugin.getLogger().severe("Error fetching overlay data (" + type + "): " + ex.getMessage());
                    return null;
                });
    }

    public record LeaderboardEntry(String name, String amount, String amountFormatted) {}
    public record MilestoneData(String title, String current, String target, String currentFormatted, String targetFormatted, double rawCurrent, double rawTarget, String startDate, String startTime) {
        public double getPercentage() {
            if (rawTarget == 0) return 0;
            return (rawCurrent / rawTarget) * 100;
        }
    }
}
