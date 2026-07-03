package click.sattr.plsDonate.manager;

import click.sattr.plsDonate.PlsDonate;
import click.sattr.plsDonate.util.Constants;
import click.sattr.plsDonate.util.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;

public class DonationService {

    private final PlsDonate plugin;

    public DonationService(PlsDonate plugin) {
        this.plugin = plugin;
    }

    /**
     * Processes a fulfilled donation (webhook path).
     * The DB row was already created by the player's initial /donate request,
     * and {@link click.sattr.plsDonate.webhook.WebhookManager} already claimed
     * it PENDING → COMPLETED. This method only handles notifications.
     */
    public void fulfillDonation(String playerName, double amount, String email, String method, String message, String transactionId, boolean isSandbox) {
        if (plugin.getStatsManager() != null) {
            plugin.getStatsManager().refresh();
        }
        notifyDonation(playerName, amount, email, method, message, transactionId);
    }

    /**
     * Processes a simulated donation (admin fakedonate/pushdonate path).
     * Creates the DB row, marks it COMPLETED, refreshes stats, and sends notifications.
     */
    public void fulfillSimulatedDonation(String playerName, String donorUuid, double amount, String email, String method, String message, String transactionId, boolean isSandbox) {
        String formattedAmount = MessageUtils.formatAmount(plugin, amount);

        plugin.getTransactionRepository().createDonationRequest(transactionId, amount, playerName, donorUuid, isSandbox)
                .thenCompose(v -> plugin.getTransactionRepository().markTransactionUsed(transactionId))
                .thenRun(() -> {
                    if (plugin.getStatsManager() != null) plugin.getStatsManager().refreshSync();
                });

        notifyDonation(playerName, amount, email, method, message, transactionId);
    }

    private void notifyDonation(String playerName, double amount, String email, String method, String message, String transactionId) {
        String formattedAmount = MessageUtils.formatAmount(plugin, amount);

        if (plugin.getConfig().getBoolean(Constants.CONF_DONATE_NOTIFICATION, true)) {
            Map<String, String> p = MessageUtils.getDonationPlaceholders(plugin, amount, playerName, email, method, message);
            p.put(Constants.ID, transactionId);
            p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));

            MessageUtils.sendLangMessageList(Bukkit.getConsoleSender(), plugin, "donation-notification", p);
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                MessageUtils.sendLangMessageList(onlinePlayer, plugin, "donation-notification", p);
                MessageUtils.playConfigSounds(onlinePlayer, plugin, "sound-effects.donation-received");
            }
        }

        if (plugin.getTriggersManager() != null) {
            plugin.getTriggersManager().processDonation(playerName, amount, formattedAmount, message, method, transactionId);
        }

        if (plugin.getDiscordManager() != null) {
            plugin.getDiscordManager().sendDonation(playerName, amount, message, method, transactionId);
        }
    }
}
