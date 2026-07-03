package click.sattr.plsDonate.webhook;

import click.sattr.plsDonate.PlsDonate;
import click.sattr.plsDonate.util.MessageUtils;
import click.sattr.plsDonate.util.PluginLogger;
import click.sattr.plsDonate.platform.DonationPlatform;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
public class WebhookManager {

    private final PlsDonate plugin;
    private HttpServer server;
    private static final int MAX_BODY_SIZE = 1024 * 64; // 64KB Max

    public WebhookManager(PlsDonate plugin) {
        this.plugin = plugin;
    }

    public boolean start(int port, String path) {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            if (!path.startsWith("/")) path = "/" + path;
            
            server.createContext(path, new WebhookHandler());
            server.setExecutor(null); 
            server.start();
            return true;
        } catch (IOException e) {
            PluginLogger.severe("Could not start webhook listener: " + e.getMessage());
            return false;
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            PluginLogger.info("Webhook listener stopped.");
        }
    }

    private class WebhookHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            try {
                String body;
                try (InputStream is = exchange.getRequestBody()) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    int total = 0;
                    int n;
                    while ((n = is.read(chunk)) != -1) {
                        total += n;
                        if (total > MAX_BODY_SIZE) {
                            sendResponse(exchange, 413, "Payload Too Large");
                            return;
                        }
                        baos.write(chunk, 0, n);
                    }
                    if (total == 0) {
                        sendResponse(exchange, 400, "Bad Request - Empty Body");
                        return;
                    }
                    body = baos.toString(StandardCharsets.UTF_8);
                }

                // Call active platform to parse and verify the webhook
                DonationPlatform.WebhookResult result = plugin.getDonationPlatform().parseWebhook(body, exchange.getRequestHeaders());

                if (!result.valid()) {
                    PluginLogger.warn("Webhook Validation Failed: " + result.errorMessage());
                    sendResponse(exchange, 400, "Bad Request");
                    return;
                }

                String transactionId = result.transactionId();

                // Integrity check: transaction exists, is still PENDING, and the checksum
                // (amount + donor recorded at request time) matches.
                if (!plugin.getTransactionRepository().isTransactionValid(transactionId, result.amount(), result.donorName())) {
                    PluginLogger.warn("Received potential replay attack or unrecorded transaction: " + transactionId + " from " + result.donorName());
                    sendResponse(exchange, 403, "Forbidden - Transaction used or invalid");
                    return;
                }

                // Atomically claim the PENDING transaction. Only the single caller that
                // transitions it to COMPLETED proceeds; concurrent duplicate webhooks lose
                // the claim, closing the check-then-act race that allowed double fulfillment.
                if (!plugin.getTransactionRepository().claimTransaction(transactionId)) {
                    PluginLogger.warn("Transaction already claimed (concurrent/replay webhook): " + transactionId);
                    sendResponse(exchange, 403, "Forbidden - Transaction already processed");
                    return;
                }

                // Success - Verification Passed
                PluginLogger.info("Verified donation: " + result.donorName() + " donated " + MessageUtils.formatAmount(plugin, result.amount()) + " (tx: " + transactionId + ")");

                // Determine if it's sandbox (usually false for webhooks, but safety first)
                boolean isSandbox = plugin.getTransactionRepository().isSandboxTransaction(transactionId);

                // Process Rewards and Notifications
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getDonationService().fulfillDonation(
                        result.donorName(),
                        result.amount(),
                        result.donorEmail(),
                        result.paymentMethod(),
                        result.message(),
                        transactionId,
                        isSandbox
                    );
                });

                sendResponse(exchange, 200, "OK");

            } catch (Exception e) {
                PluginLogger.severe("Error handling webhook request.");
                e.printStackTrace();
                sendResponse(exchange, 500, "Internal Server Error");
            }
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
