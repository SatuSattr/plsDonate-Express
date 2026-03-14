package click.sattr.plsDonate.platform;

import com.sun.net.httpserver.Headers;
import java.util.concurrent.CompletableFuture;

public interface DonationPlatform {

    /**
     * Creates a new donation transaction on the specific platform.
     *
     * @param name          Donor's name
     * @param email         Donor's email
     * @param amount        Donation amount
     * @param paymentMethod Preferred payment method
     * @param message       Donation message
     * @return A CompletableFuture wrapping the ApiResponse containing the URL and txId.
     */
    CompletableFuture<DonationResponse> createDonation(String name, String email, double amount, String paymentMethod, String message);

    /**
     * Parses an incoming webhook HTTP request payload to extract verified donation data.
     *
     * @param body    Raw request body (usually JSON)
     * @param headers HTTP headers (used for signatures/authentication verification)
     * @return WebhookResult containing either validation error or parsed donation details.
     */
    WebhookResult parseWebhook(String body, Headers headers);


    /**
     * @return The maximum allowed message length for this specific platform.
     */
    int getMaxMessageLength();



    /**
     * @return Human-readable platform name (e.g. "Tako.id", "Donet.co").
     */
    String getPlatformName();

    /**
     * @return The platform's homepage URL for footer disclaimers.
     */
    String getPlatformUrl();

    record DonationResponse(boolean success, String message, String transactionId, String paymentUrl) {}

    record WebhookResult(
            boolean valid,
            String errorMessage,
            String transactionId,
            String donorName,
            String donorEmail,
            double amount,
            String message,
            String paymentMethod
    ) {}
}
