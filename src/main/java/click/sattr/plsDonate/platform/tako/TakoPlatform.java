package click.sattr.plsDonate.platform.tako;

import click.sattr.plsDonate.PlsDonate;
import click.sattr.plsDonate.platform.DonationPlatform;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class TakoPlatform implements DonationPlatform {

    private final PlsDonate plugin;
    private final HttpClient httpClient;
    private final Gson gson;
    private static final String API_BASE_PATH = "https://tako.id/api/gift/";
    private static final String DEFAULT_CREATOR = "Halcy";

    public TakoPlatform(PlsDonate plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.gson = new Gson();
    }

    @Override
    public int getMaxMessageLength() {
        return 150;
    }



    @Override
    public String getPlatformName() {
        return "Tako.id";
    }

    @Override
    public String getPlatformUrl() {
        return "https://tako.id/";
    }

    @Override
    public CompletableFuture<DonationResponse> createDonation(String name, String email, double amount, String paymentMethod, String message) {
        // Fallback for older configs
        String apiKey = plugin.getConfig().getString("platform.tako.api-key", plugin.getConfig().getString("api.key", ""));
        String creator = plugin.getConfig().getString("platform.tako.creator", plugin.getConfig().getString("api.creator", DEFAULT_CREATOR));

        if (apiKey.isEmpty() || apiKey.equals("your_secret_api_key_here")) {
            return CompletableFuture.completedFuture(new DonationResponse(false, "API Key is not configured properly.", null, null));
        }

        String fullUrl = creator;
        if (!creator.startsWith("http")) {
            if (!creator.contains("/")) {
                fullUrl = API_BASE_PATH + creator;
            } else {
                fullUrl = "https://tako.id" + (creator.startsWith("/") ? "" : "/") + creator;
            }
        }

        JsonObject jsonBody = new JsonObject();
        jsonBody.addProperty("name", name);
        jsonBody.addProperty("email", email);
        jsonBody.addProperty("amount", amount);
        jsonBody.addProperty("paymentMethod", paymentMethod);
        if (message != null && !message.isEmpty()) {
            jsonBody.addProperty("message", message);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody.toString()))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    int statusCode = response.statusCode();
                    if (statusCode == 200 || statusCode == 201 || statusCode == 206 || statusCode == 402) {
                        JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
                        if (jsonResponse.has("result")) {
                            JsonObject result = jsonResponse.getAsJsonObject("result");
                            boolean success = result.has("success") && result.get("success").getAsBoolean();
                            String transactionId = result.has("transactionId") ? result.get("transactionId").getAsString() : null;
                            String paymentUrl = result.has("paymentUrl") ? result.get("paymentUrl").getAsString() : null;
                            return new DonationResponse(success, "Success", transactionId, paymentUrl);
                        }
                    }

                    try {
                        JsonObject errorResponse = gson.fromJson(response.body(), JsonObject.class);
                        String errMsg = errorResponse.has("message") ? errorResponse.get("message").getAsString() : "Unknown API Error (" + statusCode + ")";
                        return new DonationResponse(false, errMsg, null, null);
                    } catch (Exception e) {
                        return new DonationResponse(false, "Failed to parse API response. Status: " + statusCode, null, null);
                    }
                })
                .exceptionally(ex -> {
                    plugin.getLogger().severe("API Request Failed: " + ex.getMessage());
                    return new DonationResponse(false, "Network error: " + ex.getMessage(), null, null);
                });
    }

    @Override
    public WebhookResult parseWebhook(String body, Headers headers) {
        // Fallback for older configs
        String token = plugin.getConfig().getString("platform.tako.webhook-token", plugin.getConfig().getString("webhook.token", ""));

        if (token.isEmpty() || token.equals("your_secret_token_here")) {
            return new WebhookResult(false, "webhook.token is empty/invalid. Setup incomplete.", null, null, null, 0, null, null);
        }

        if (body == null || body.isEmpty()) {
            return new WebhookResult(false, "Missing request body.", null, null, null, 0, null, null);
        }

        List<String> signatures = headers.get("X-Tako-Signature");
        String signature = (signatures != null && !signatures.isEmpty()) ? signatures.get(0) : null;

        if (signature == null || signature.isEmpty()) {
            return new WebhookResult(false, "Missing X-Tako-Signature header.", null, null, null, 0, null, null);
        }

        if (!verifySignature(body, token, signature)) {
            return new WebhookResult(false, "Invalid signature. Potential spoofing attempt.", null, null, null, 0, null, null);
        }

        try {
            JsonObject payload = new JsonParser().parse(body).getAsJsonObject();

            if (!payload.has("id")) {
                return new WebhookResult(false, "Valid signature, but missing 'id' field in payload.", null, null, null, 0, null, null);
            }

            String transactionId = payload.get("id").getAsString();
            String donorName = payload.has("name") ? payload.get("name").getAsString() : "Anonymous";
            String donorEmail = payload.has("email") ? payload.get("email").getAsString() : "N/A";
            double amount = payload.has("amount") ? payload.get("amount").getAsDouble() : 0.0;
            String message = payload.has("message") ? payload.get("message").getAsString() : "";
            String paymentMethod = payload.has("paymentMethod") ? payload.get("paymentMethod").getAsString() : "unknown";

            return new WebhookResult(true, null, transactionId, donorName, donorEmail, amount, message, paymentMethod);

        } catch (Exception e) {
            return new WebhookResult(false, "Failed to parse verified webhook JSON: " + e.getMessage(), null, null, null, 0, null, null);
        }
    }

    private boolean verifySignature(String body, String token, String receivedSignature) {
        try {
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256_HMAC.init(secret_key);

            byte[] hash = sha256_HMAC.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            String calculatedSignature = hexString.toString();
            return calculatedSignature.equalsIgnoreCase(receivedSignature);
        } catch (Exception e) {
            return false;
        }
    }
}
