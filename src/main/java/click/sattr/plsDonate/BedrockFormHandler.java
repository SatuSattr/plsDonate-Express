package click.sattr.plsDonate;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BedrockFormHandler {

    private final PlsDonate plugin;

    public BedrockFormHandler(PlsDonate plugin) {
        this.plugin = plugin;
    }

    public boolean isBedrockPlayer(Player player) {
        return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
    }

    public void sendConfirmationForm(Player player, double amount, String email, String method, String message, boolean isSimulation, boolean isSandbox) {
        String title = plugin.getLangConfig().getString("donation-confirmation-bedrock.title", "Confirm Donation");
        String btnYes = plugin.getLangConfig().getString("donation-confirmation-bedrock.btn-yes", "Yes");
        String btnNo = plugin.getLangConfig().getString("donation-confirmation-bedrock.btn-no", "No");

        StringBuilder contentInfo = new StringBuilder();
        net.kyori.adventure.text.minimessage.MiniMessage miniMessage = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer legacySection = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();

        List<String> rawContent = plugin.getLangConfig().getStringList("donation-confirmation-bedrock.content");
        if (rawContent.isEmpty()) {
             rawContent = List.of(
                 "<gray>Are you sure you want to donate?", "",
                 "<gray>Amount: <green>Rp{AMOUNT_FORMATTED}",
                 "<gray>Email: <white>{EMAIL}",
                 "<gray>Method: <yellow>{METHOD}",
                 "<gray>Message: <yellow>{MESSAGE}"
             );
        }

        Map<String, String> p = plugin.getDonationPlaceholders(player.getName(), amount, email, method, message);

        for (String line : rawContent) {
             String processedLine = line;
             for (Map.Entry<String, String> entry : p.entrySet()) {
                 processedLine = processedLine.replace(entry.getKey(), entry.getValue());
             }
             
             net.kyori.adventure.text.Component component = miniMessage.deserialize(processedLine);
             String legacyText = legacySection.serialize(component);
             contentInfo.append(legacyText).append("\n");
        }

        SimpleForm form = SimpleForm.builder()
            .title(title)
            .content(contentInfo.toString())
            .button(btnYes)
            .button(btnNo)
            .validResultHandler(response -> {
                if (response.clickedButtonId() == 0) {
                    if (isSimulation) {
                        processSimulatedDonation(player, amount, email, method, message, isSandbox);
                    } else {
                        processBedrockDonation(player, amount, email, method, message);
                    }
                }
            })
            .build();
        
        FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
        
        // Sound: donation-confirmation
        plugin.playConfigSounds(player, "sound-effects.donation-confirmation");
    }

    private void processSimulatedDonation(Player player, double amount, String email, String method, String message, boolean isSandbox) {
        String formattedAmount = plugin.formatIndonesianNumber(amount);
        String txId = (isSandbox ? "FAKETX-" : "PUSHTX-") + System.currentTimeMillis();

        // 1. Save to Database
        plugin.getStorageManager().createDonationRequest(txId, amount, player.getName(), isSandbox);
        plugin.getStorageManager().markTransactionUsed(txId);

        // 2. Broadcast Notifications
        if (plugin.getConfig().getBoolean("donate.notification", true)) {
            Map<String, String> p = plugin.getDonationPlaceholders(player.getName(), amount, email, method, message);
            p.put("{ID}", txId);
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));

            plugin.sendLangMessageList(Bukkit.getConsoleSender(), "donation-notification", p);
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                plugin.sendLangMessageList(onlinePlayer, "donation-notification", p);
                plugin.playConfigSounds(onlinePlayer, "sound-effects.donation-received");
            }
        }

        // 3. Trigger processing system
        if (plugin.getTriggersManager() != null) {
            plugin.getTriggersManager().processDonation(player.getName(), amount, formattedAmount, message, method, txId);
        }

        Map<String, String> fpP = new HashMap<>();
        fpP.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
        String langKey = isSandbox ? "fake-donation-processed" : "push-donation-processed";
        String defaultMsg = isSandbox ? "{PREFIX} <green>Fake donation form successfully processed!</green>" : "{PREFIX} <green>Donation form successfully pushed!</green>";
        player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString(langKey, defaultMsg), fpP));
    }

    private void processBedrockDonation(Player player, double amount, String email, String method, String message) {
        // Sound: donation-processed
        plugin.playConfigSounds(player, "sound-effects.donation-processed");
        
        Map<String, String> prcP = new HashMap<>();
        prcP.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
        player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("donation-processing", "{PREFIX} <gray>Processing your donation form... Please check your email shortly!</gray>"), prcP));

        plugin.getDonationPlatform().createDonation(player.getName(), email, amount, method, message).thenAccept(response -> {
            if (response.success()) {
                // Log request to ledger
                plugin.getStorageManager().createDonationRequest(response.transactionId(), amount, player.getName(), false);

                // Send Email to Bedrock Player
                plugin.getEmailManager().sendPaymentEmail(
                        player.getName(), 
                        email, 
                        amount, 
                        plugin.formatIndonesianNumber(amount), 
                        method,
                        response.paymentUrl(),
                        message
                );

                Bukkit.getScheduler().runTask(plugin, () -> {
                    Map<String, String> succP = new HashMap<>();
                    succP.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                    player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("donation-email-sent", "{PREFIX} <green>A payment link has been sent to your email!</green>"), succP));
                    // Sound: donation-success
                    plugin.playConfigSounds(player, "sound-effects.donation-success");
                });
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Map<String, String> errP = new HashMap<>();
                    errP.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                    errP.put("{ERROR}", response.message());
                    player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("api-error", "{PREFIX} <red>API Error: {ERROR}</red>"), errP));
                });
            }
        });
    }
}
