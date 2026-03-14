package click.sattr.plsDonate.platform.donet;

import click.sattr.plsDonate.PlsDonate;
import click.sattr.plsDonate.platform.DonationPlatform;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class DonetPlatform implements DonationPlatform {

    private final PlsDonate plugin;
    private static final String TX_ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int TX_ID_LENGTH = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    public DonetPlatform(PlsDonate plugin) {
        this.plugin = plugin;
    }

    @Override
    public int getMaxMessageLength() {
        return 190;
    }

    @Override
    public String getButtonImageUrl() {
        return "https://i.imgur.com/BujKuZO.png";
    }

    @Override
    public String getPlatformName() {
        return "Donet.co";
    }

    @Override
    public String getPlatformUrl() {
        return "https://donet.co/";
    }

    @Override
    public CompletableFuture<DonationResponse> createDonation(String name, String email, double amount, String paymentMethod, String message) {
        String creator = plugin.getConfig().getString("platform.donet.creator", "Malik");
        String baseUrl = "https://donet.co/" + creator;

        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
            long priceVal = (long) amount;

            // Generate a 20-char random transaction ID to embed inside the message
            String transactionId = generateTransactionId();

            // Compose the combined message: "{20charTxId}{userMessage}" (no separator needed, fixed length)
            String combinedMessage = transactionId + (message != null ? message.trim() : "");

            StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append("?name=").append(encodedName)
                    .append("&is_anon=1")
                    .append("&price=").append(priceVal)
                    .append("&payment=").append(URLEncoder.encode("qris", StandardCharsets.UTF_8))
                    .append("&submit=true&app=true")
                    .append("&messages=").append(URLEncoder.encode(combinedMessage, StandardCharsets.UTF_8));

            String paymentUrl = baseUrl + queryBuilder.toString();

            return CompletableFuture.completedFuture(new DonationResponse(true, "URL generated", transactionId, paymentUrl));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(new DonationResponse(false, "Failed to encode URL parameters: " + e.getMessage(), null, null));
        }
    }

    private String generateTransactionId() {
        StringBuilder sb = new StringBuilder(TX_ID_LENGTH);
        for (int i = 0; i < TX_ID_LENGTH; i++) {
            sb.append(TX_ID_CHARS.charAt(RANDOM.nextInt(TX_ID_CHARS.length())));
        }
        return sb.toString();
    }

    @Override
    public WebhookResult parseWebhook(String body, Headers headers) {
        String token = plugin.getConfig().getString("platform.donet.webhook-token", "");

        if (token.isEmpty() || token.equals("your_secret_token_here")) {
            return new WebhookResult(false, "platform.donet.webhook-token is empty or invalid. Setup incomplete.", null, null, null, 0, null, null);
        }

        if (body == null || body.isEmpty()) {
            return new WebhookResult(false, "Missing request body.", null, null, null, 0, null, null);
        }

        // Verify Bearer Token in Headers
        List<String> authHeaders = headers.get("Authorization");
        String authHeader = (authHeaders != null && !authHeaders.isEmpty()) ? authHeaders.get(0) : null;

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return new WebhookResult(false, "Missing or invalid Authorization header.", null, null, null, 0, null, null);
        }

        String receivedToken = authHeader.substring(7); // Remove "Bearer " prefix
        if (!receivedToken.equals(token)) {
            return new WebhookResult(false, "Invalid Bearer token. Potential spoofing attempt.", null, null, null, 0, null, null);
        }

        // Parse JSON Payload
        try {
            JsonObject payload = new JsonParser().parse(body).getAsJsonObject();

            if (!payload.has("id")) {
                return new WebhookResult(false, "Valid Bearer token, but missing 'id' field in payload.", null, null, null, 0, null, null);
            }

            String transactionId = null;
            String donorName = payload.has("name") ? payload.get("name").getAsString() : "Anonymous";
            String donorEmail = "N/A"; // Donet payload doesn't provide email
            double amount = payload.has("price") ? payload.get("price").getAsDouble() : 0.0;
            String rawMessages = payload.has("messages") ? payload.get("messages").getAsString() : "";
            String paymentMethod = "qris";

            // Try to extract an embedded transaction ID from the messages field
            // Format: "{20charTxId}{actualMessage}" (no separator, fixed-length ID)
            String actualMessage = rawMessages;
            if (rawMessages.length() >= TX_ID_LENGTH) {
                String potentialTxId = rawMessages.substring(0, TX_ID_LENGTH);
                // Validate it contains only valid ID characters
                if (potentialTxId.matches("[a-z0-9]{" + TX_ID_LENGTH + "}")) {
                    transactionId = potentialTxId;
                    actualMessage = rawMessages.substring(TX_ID_LENGTH); // everything after the 20-char ID
                }
            }

            // If no embedded txId was found, this is a direct platform donation
            // Use the Donet webhook ID as a fallback identifier
            if (transactionId == null) {
                transactionId = payload.has("id") ? payload.get("id").getAsString() : "DONET-EXTERNAL-" + System.currentTimeMillis();
            }

            return new WebhookResult(true, null, transactionId, donorName, donorEmail, amount, actualMessage, paymentMethod);

        } catch (Exception e) {
            return new WebhookResult(false, "Failed to parse verified webhook JSON: " + e.getMessage(), null, null, null, 0, null, null);
        }
    }
}
