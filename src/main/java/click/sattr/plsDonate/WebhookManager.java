package click.sattr.plsDonate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
            plugin.getLogger().info("Webhook listener started on port " + port + " at path " + path);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Could not start webhook listener: " + e.getMessage());
            return false;
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            plugin.getLogger().info("Webhook listener stopped.");
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
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                    char[] buffer = new char[MAX_BODY_SIZE + 1];
                    int read = reader.read(buffer, 0, MAX_BODY_SIZE + 1);
                    if (read > MAX_BODY_SIZE) {
                        sendResponse(exchange, 413, "Payload Too Large");
                        return;
                    }
                    body = new String(buffer, 0, read);
                }

                // Call active platform to parse and verify the webhook
                click.sattr.plsDonate.platform.DonationPlatform.WebhookResult result = plugin.getDonationPlatform().parseWebhook(body, exchange.getRequestHeaders());

                if (!result.valid()) {
                    plugin.getLogger().warning("Webhook Validation Failed: " + result.errorMessage());
                    sendResponse(exchange, 400, "Bad Request");
                    return;
                }

                String transactionId = result.transactionId();

                // Validate against Ledger to prevent replay attacks
                if (!plugin.getStorageManager().isTransactionValid(transactionId, result.amount(), result.donorName())) {
                    plugin.getLogger().warning("Received potential replay attack or unrecorded transaction: " + transactionId + " from " + result.donorName());
                    sendResponse(exchange, 403, "Forbidden - Transaction used or invalid");
                    return;
                }

                // Success - Verification Passed
                plugin.getLogger().info("Verified donation: " + result.donorName() + " donated Rp" + plugin.formatIndonesianNumber(result.amount()) + " (tx: " + transactionId + ")");
                
                // Mark as completed in Ledger
                plugin.getStorageManager().markTransactionUsed(transactionId);

                sendResponse(exchange, 200, "OK");

            } catch (Exception e) {
                plugin.getLogger().severe("Error handling webhook request.");
                e.printStackTrace();
                sendResponse(exchange, 500, "Internal Server Error");
            }
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
            exchange.sendResponseHeaders(statusCode, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }
}
