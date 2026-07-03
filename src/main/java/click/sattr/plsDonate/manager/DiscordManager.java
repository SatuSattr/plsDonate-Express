package click.sattr.plsDonate.manager;

import click.sattr.plsDonate.PlsDonate;
import click.sattr.plsDonate.util.MessageUtils;
import click.sattr.plsDonate.util.PluginLogger;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.skinsrestorer.api.PropertyUtils;
import net.skinsrestorer.api.SkinsRestorerProvider;
import net.skinsrestorer.api.property.SkinProperty;
import net.skinsrestorer.api.storage.PlayerStorage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends a customizable Discord embed via webhook URL when a live donation is fulfilled.
 *
 * <p>Two distinct injection surfaces are handled separately. Text fields (title, description,
 * fields, footer) are emitted through Gson's {@link JsonObject}, so any quotes or braces a donor
 * types survive as escaped DATA and can never break out into new JSON keys. URL fields (thumbnail,
 * image, author/footer icon) percent-encode every player-derived value before substitution, so a donor who
 * renames themselves into something like {@code x/../?to=evil} can only fill a single path segment —
 * they cannot inject a new host or scheme. {@code {PLAYER_HEAD}} and {@code {PLAYER_HEAD_SKIN_RESTORER}}
 * are pre-encoded and substituted verbatim so they are never double-encoded.
 *
 * <p>{@code {PLAYER_HEAD_SKIN_RESTORER}} requires SkinsRestorer to be installed on the server.
 * If SkinsRestorer is absent or the player has no skin data, it falls back to {@code {PLAYER_HEAD}}.
 *
 * <p>{@code {SKIN_TEXTURE_ID_SKIN_RESTORER}} returns the raw Mojang texture hash (e.g. {@code a3b4c5d6...}) as
 * resolved by SkinsRestorer. Returns an empty string if SkinsRestorer is not installed, the player
 * is offline, or no skin data is available. Unlike {@code {PLAYER_HEAD_SKIN_RESTORER}}, this
 * placeholder does NOT produce a URL — it only exposes the bare texture ID for custom use.
 */
public class DiscordManager {

    private static final String VZGE_URL_BASE = "https://vzge.me/face/";
    private static final int VZGE_DEFAULT_SIZE = 256;
    private static final String EMBED_BASE = "discord.embed.";

    // Matches {PLAYER_HEAD_<digits>} e.g. {PLAYER_HEAD_180}
    private static final Pattern PLAYER_HEAD_SIZE_PATTERN =
            Pattern.compile("\\{PLAYER_HEAD_(\\d+)}");
    // Matches {PLAYER_HEAD_SKIN_RESTORER_<digits>} e.g. {PLAYER_HEAD_SKIN_RESTORER_180}
    private static final Pattern PLAYER_HEAD_SR_SIZE_PATTERN =
            Pattern.compile("\\{PLAYER_HEAD_SKIN_RESTORER_(\\d+)}");

    private final PlsDonate plugin;
    private final HttpClient httpClient;

    public DiscordManager(PlsDonate plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record EmbedField(String name, String value, boolean inline) {}

    public record EmbedSpec(String color, String authorName, String authorIconUrl, String title,
                            String description, String thumbnail, String image, List<EmbedField> fields,
                            String footerText, String footerIconUrl, boolean timestamp) {}

    private record Ctx(String headUrl, String headUrlSr, String srTextureId, String player, String message,
                       String amount, String amountFormatted, String method, String id, String date) {}

    public void sendDonation(String playerName, double amount, String message, String method, String txId) {
        if (!plugin.getConfig().getBoolean("discord.enabled", false)) return;
        dispatch(playerName, amount, message, method, txId);
    }

    /**
     * Sends a sample embed using the admin's configured layout, bypassing the
     * {@code discord.enabled} flag so the setup can be verified before going live.
     * Returns the number of valid webhooks dispatched to.
     */
    public int sendTest(String playerName) {
        return dispatch(playerName, 50000, "This is a test donation message!", "qris",
                "TEST-" + System.currentTimeMillis());
    }

    private int dispatch(String playerName, double amount, String message, String method, String txId) {
        List<String> webhooks = plugin.getConfig().getStringList("discord.webhooks");
        if (webhooks.isEmpty()) return 0;

        EmbedSpec spec = readSpec();
        String amountStr = String.valueOf((long) amount);
        String amountFormatted = MessageUtils.formatAmount(plugin, amount);
        String friendlyMethod = MessageUtils.friendlyMethod(plugin.getLangConfig(), method);
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String msg = (message == null) ? "" : message;

        String headUrlSr = resolveSkinsRestorerHeadUrl(playerName);
        String srTextureId = resolveSkinsRestorerTextureId(playerName);
        String payload = buildPayload(spec, playerName, headUrlSr, srTextureId, msg, amountStr, amountFormatted,
                friendlyMethod, txId, date, Instant.now());

        int sent = 0;
        for (String url : webhooks) {
            if (url == null || url.isBlank() || !url.startsWith("http")) continue;
            send(url, payload);
            sent++;
        }
        return sent;
    }

    private EmbedSpec readSpec() {
        FileConfiguration c = plugin.getConfig();
        List<EmbedField> fields = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList(EMBED_BASE + "fields")) {
            Object n = m.get("name");
            Object v = m.get("value");
            Object inl = m.get("inline");
            String name = n == null ? "" : String.valueOf(n);
            String value = v == null ? "" : String.valueOf(v);
            boolean inline = inl instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(inl));
            fields.add(new EmbedField(name, value, inline));
        }

        return new EmbedSpec(
                c.getString(EMBED_BASE + "color", ""),
                c.getString(EMBED_BASE + "author.name", ""),
                c.getString(EMBED_BASE + "author.icon-url", ""),
                c.getString(EMBED_BASE + "title", ""),
                c.getString(EMBED_BASE + "description", ""),
                c.getString(EMBED_BASE + "thumbnail", ""),
                c.getString(EMBED_BASE + "image", ""),
                fields,
                c.getString(EMBED_BASE + "footer.text", ""),
                c.getString(EMBED_BASE + "footer.icon-url", ""),
                c.getBoolean(EMBED_BASE + "footer.timestamp", true));
    }

    public static String buildPayload(EmbedSpec spec, String player, String headUrlSr, String srTextureId,
                                      String message, String amount,
                                      String amountFormatted, String method, String id, String date,
                                      Instant timestamp) {
        String srUrl = (headUrlSr != null && !headUrlSr.isBlank()) ? headUrlSr
                : VZGE_URL_BASE + VZGE_DEFAULT_SIZE + "/" + enc(player);
        String textureId = (srTextureId != null) ? srTextureId : "";
        Ctx c = new Ctx(VZGE_URL_BASE + VZGE_DEFAULT_SIZE + "/" + enc(player), srUrl, textureId,
                player, message, amount, amountFormatted, method, id, date);

        JsonObject embed = new JsonObject();

        Integer color = parseColor(spec.color());
        if (color != null) embed.addProperty("color", color);

        String title = resolveText(spec.title(), c);
        if (!title.isEmpty()) embed.addProperty("title", title);

        String description = resolveText(spec.description(), c);
        if (!description.isEmpty()) embed.addProperty("description", description);

        String authorName = resolveText(spec.authorName(), c);
        if (!authorName.isEmpty()) {
            JsonObject author = new JsonObject();
            author.addProperty("name", authorName);
            String icon = resolveUrl(spec.authorIconUrl(), c);
            if (isHttpUrl(icon)) author.addProperty("icon_url", icon);
            embed.add("author", author);
        }

        String thumb = resolveUrl(spec.thumbnail(), c);
        if (isHttpUrl(thumb)) {
            JsonObject t = new JsonObject();
            t.addProperty("url", thumb);
            embed.add("thumbnail", t);
        }

        String image = resolveUrl(spec.image(), c);
        if (isHttpUrl(image)) {
            JsonObject img = new JsonObject();
            img.addProperty("url", image);
            embed.add("image", img);
        }

        JsonArray fieldsArr = new JsonArray();
        for (EmbedField f : spec.fields()) {
            String value = resolveText(f.value(), c);
            if (value.isEmpty()) continue;
            JsonObject fo = new JsonObject();
            fo.addProperty("name", resolveText(f.name(), c));
            fo.addProperty("value", value);
            fo.addProperty("inline", f.inline());
            fieldsArr.add(fo);
        }
        if (!fieldsArr.isEmpty()) embed.add("fields", fieldsArr);

        String footerText = resolveText(spec.footerText(), c);
        if (!footerText.isEmpty()) {
            JsonObject footer = new JsonObject();
            footer.addProperty("text", footerText);
            String icon = resolveUrl(spec.footerIconUrl(), c);
            if (isHttpUrl(icon)) footer.addProperty("icon_url", icon);
            embed.add("footer", footer);
        }

        if (spec.timestamp()) embed.addProperty("timestamp", timestamp.toString());

        JsonArray embeds = new JsonArray();
        embeds.add(embed);

        JsonObject root = new JsonObject();
        root.add("embeds", embeds);

        // Never let donor text ping the server: no content body, and disable all mention parsing.
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        root.add("allowed_mentions", allowedMentions);

        return root.toString();
    }

    private static String resolveText(String template, Ctx c) {
        if (template == null) return "";
        // Handle size variants first (more specific), then fixed placeholders
        String result = resolveSizePattern(template, PLAYER_HEAD_SR_SIZE_PATTERN, c.headUrlSr(), c.player(), false);
        result = resolveSizePattern(result, PLAYER_HEAD_SIZE_PATTERN, c.headUrl(), c.player(), false);
        return result
                .replace("{PLAYER_HEAD_SKIN_RESTORER}", c.headUrlSr())
                .replace("{PLAYER_HEAD}", c.headUrl())
                .replace("{SKIN_TEXTURE_ID_SKIN_RESTORER}", c.srTextureId())
                .replace("{PLAYER}", c.player())
                .replace("{AMOUNT_FORMATTED}", c.amountFormatted())
                .replace("{AMOUNT}", c.amount())
                .replace("{MESSAGE}", c.message())
                .replace("{METHOD}", c.method())
                .replace("{ID}", c.id())
                .replace("{DATE}", c.date());
    }

    private static String resolveUrl(String template, Ctx c) {
        if (template == null) return "";
        // Handle size variants first (more specific), then fixed placeholders
        String result = resolveSizePattern(template, PLAYER_HEAD_SR_SIZE_PATTERN, c.headUrlSr(), c.player(), true);
        result = resolveSizePattern(result, PLAYER_HEAD_SIZE_PATTERN, c.headUrl(), c.player(), true);
        return result
                .replace("{PLAYER_HEAD_SKIN_RESTORER}", c.headUrlSr())
                .replace("{PLAYER_HEAD}", c.headUrl())
                .replace("{SKIN_TEXTURE_ID_SKIN_RESTORER}", enc(c.srTextureId()))
                .replace("{PLAYER}", enc(c.player()))
                .replace("{AMOUNT_FORMATTED}", enc(c.amountFormatted()))
                .replace("{AMOUNT}", enc(c.amount()))
                .replace("{MESSAGE}", enc(c.message()))
                .replace("{METHOD}", enc(c.method()))
                .replace("{ID}", enc(c.id()))
                .replace("{DATE}", enc(c.date()));
    }

    /**
     * Replaces all occurrences of a size-parameterized head placeholder (e.g. {@code {PLAYER_HEAD_128}})
     * with a VZGE URL at the requested size. When a size is given, the base URL's default size segment
     * is replaced with the requested size.
     *
     * @param template    the string to process
     * @param pattern     compiled regex with one capture group for the size digits
     * @param defaultUrl  the pre-built default-size URL for this placeholder type
     * @param playerName  raw player name (used for URL encoding when {@code encode=true})
     * @param encode      whether to URL-encode the player name (true for URL fields)
     */
    private static String resolveSizePattern(String template, Pattern pattern,
                                              String defaultUrl, String playerName, boolean encode) {
        Matcher m = pattern.matcher(template);
        if (!m.find()) return template;
        // Derive the base subject from defaultUrl: everything after the last '/' is either
        // the player name or a texture hash. We just need to swap the size segment.
        // defaultUrl format: https://vzge.me/face/<defaultSize>/<subject>
        String subject = defaultUrl.substring(defaultUrl.lastIndexOf('/') + 1);
        StringBuffer sb = new StringBuffer();
        m.reset();
        while (m.find()) {
            String size = m.group(1);
            String replacement = VZGE_URL_BASE + size + "/" + subject;
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Attempts to resolve the player's current skin texture URL via SkinsRestorer and build
     * a VZGE head render URL from it. Returns the VZGE player-name URL as fallback if SkinsRestorer
     * is not installed, the player is offline, or no skin data is available.
     */
    private String resolveSkinsRestorerHeadUrl(String playerName) {
        String fallback = VZGE_URL_BASE + VZGE_DEFAULT_SIZE + "/" + enc(playerName);
        // Guard: SkinsRestorer must be installed as a plugin
        if (Bukkit.getPluginManager().getPlugin("SkinsRestorer") == null) {
            return fallback;
        }
        try {
            Player player = Bukkit.getPlayerExact(playerName);
            if (player == null) return fallback;

            PlayerStorage playerStorage = SkinsRestorerProvider.get().getPlayerStorage();
            Optional<SkinProperty> skinProperty = playerStorage.getSkinForPlayer(
                    player.getUniqueId(), player.getName());
            if (skinProperty.isEmpty()) return fallback;

            String textureUrl = PropertyUtils.getSkinTextureUrl(skinProperty.get());
            if (textureUrl == null || textureUrl.isBlank()) return fallback;

            // Extract the texture hash from the full Mojang URL
            // e.g. https://textures.minecraft.net/texture/<hash>
            String textureHash = textureUrl.substring(textureUrl.lastIndexOf('/') + 1);
            if (textureHash.isBlank()) return fallback;

            return VZGE_URL_BASE + VZGE_DEFAULT_SIZE + "/" + textureHash;
        } catch (Exception e) {
            PluginLogger.warn("[DiscordManager] Failed to resolve SkinsRestorer skin for "
                    + playerName + ": " + e.getMessage());
            return fallback;
        }
    }

    /**
     * Returns the raw Mojang texture hash for the player's active skin as resolved by SkinsRestorer
     * (e.g. {@code a3b4c5d6...}). Returns an empty string if SkinsRestorer is not installed, the
     * player is offline, or no skin data is available.
     */
    private String resolveSkinsRestorerTextureId(String playerName) {
        if (Bukkit.getPluginManager().getPlugin("SkinsRestorer") == null) return "";
        try {
            Player player = Bukkit.getPlayerExact(playerName);
            if (player == null) return "";

            PlayerStorage playerStorage = SkinsRestorerProvider.get().getPlayerStorage();
            Optional<SkinProperty> skinProperty = playerStorage.getSkinForPlayer(
                    player.getUniqueId(), player.getName());
            if (skinProperty.isEmpty()) return "";

            String textureUrl = PropertyUtils.getSkinTextureUrl(skinProperty.get());
            if (textureUrl == null || textureUrl.isBlank()) return "";

            String textureHash = textureUrl.substring(textureUrl.lastIndexOf('/') + 1);
            return textureHash.isBlank() ? "" : textureHash;
        } catch (Exception e) {
            PluginLogger.warn("[DiscordManager] Failed to resolve SkinsRestorer texture ID for "
                    + playerName + ": " + e.getMessage());
            return "";
        }
    }

    /** RFC 3986 path-segment encoding: form-encode then turn '+' into %20 so spaces aren't '+'. */
    private static String enc(String s) {
        if (s == null) return "";
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean isHttpUrl(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    private static Integer parseColor(String hex) {
        if (hex == null) return null;
        hex = hex.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.isEmpty()) return null;
        try {
            int v = Integer.parseInt(hex, 16);
            if (v < 0 || v > 0xFFFFFF) return null;
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void send(String url, String payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        int sc = response.statusCode();
                        if (sc < 200 || sc >= 300) {
                            PluginLogger.warn("Discord webhook returned HTTP " + sc + ": " + response.body());
                        }
                    })
                    .exceptionally(ex -> {
                        PluginLogger.warn("Failed to send Discord webhook: " + ex.getMessage());
                        return null;
                    });
        } catch (IllegalArgumentException e) {
            PluginLogger.warn("Invalid Discord webhook URL configured: " + e.getMessage());
        }
    }
}
