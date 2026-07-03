package click.sattr.plsDonate.manager;

import click.sattr.plsDonate.PlsDonate;
import click.sattr.plsDonate.util.Constants;
import click.sattr.plsDonate.util.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
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

    public void openDonationForm(Player player) {
        final List<String> methodIds = List.of("qris", "gopay", "paypal");

        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer legacySection =
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();

        List<String> methodDisplayNames = new ArrayList<>();
        for (String id : methodIds) {
            String raw = plugin.getLangConfig().getString("donation-form-bedrock.method-" + id, id.toUpperCase());
            methodDisplayNames.add(legacySection.serialize(MessageUtils.parseMessage(raw)));
        }

        String title             = legacySection.serialize(MessageUtils.parseMessage(plugin.getLangConfig().getString("donation-form-bedrock.title", "Donation Form")));
        String amountLabel       = legacySection.serialize(MessageUtils.parseMessage(plugin.getLangConfig().getString("donation-form-bedrock.amount-label", "Amount")));
        String amountPlaceholder = plugin.getLangConfig().getString("donation-form-bedrock.amount-placeholder", "Enter amount...");
        String emailLabel        = legacySection.serialize(MessageUtils.parseMessage(plugin.getLangConfig().getString("donation-form-bedrock.email-label", "Email")));
        String emailPlaceholder  = plugin.getLangConfig().getString("donation-form-bedrock.email-placeholder", "your@email.com");
        String methodLabel       = legacySection.serialize(MessageUtils.parseMessage(plugin.getLangConfig().getString("donation-form-bedrock.method-label", "Payment Method")));
        String messageLabel      = legacySection.serialize(MessageUtils.parseMessage(plugin.getLangConfig().getString("donation-form-bedrock.message-label", "Message (optional)")));
        String messagePlaceholder = plugin.getLangConfig().getString("donation-form-bedrock.message-placeholder", "Your message...");

        CustomForm form = CustomForm.builder().title(title)
                .input(amountLabel, amountPlaceholder, "")
                .input(emailLabel, emailPlaceholder, "")
                .dropdown(methodLabel, methodDisplayNames)
                .input(messageLabel, messagePlaceholder, "")
                .validResultHandler((ff, response) -> {
                    String amountStr   = response.asInput(0).trim();
                    String email       = response.asInput(1).trim();
                    int    methodIdx   = response.asDropdown(2);
                    String formMessage = response.asInput(3).trim();

                    String method = methodIds.get(methodIdx);

                    // Build the /donate command and dispatch it — all validation lives in DonateCommand
                    String cmd = "donate " + amountStr + " " + email + " " + method
                            + (formMessage.isEmpty() ? "" : " " + formMessage);

                    Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(player, cmd));
                }).build();

        FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
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
                 "<gray>Amount: <green>{AMOUNT_FORMATTED}",
                 "<gray>Email: <white>{EMAIL}",
                 "<gray>Method: <yellow>{METHOD}",
                 "<gray>Message: <yellow>{MESSAGE}"
             );
        }

        Map<String, String> p = MessageUtils.getDonationPlaceholders(plugin, amount, player.getName(), email, method, message);

        for (String line : rawContent) {
             String processedLine = line;
             for (Map.Entry<String, String> entry : p.entrySet()) {
                 processedLine = processedLine.replace(entry.getKey(), entry.getValue());
             }
             
             net.kyori.adventure.text.Component component = MessageUtils.parseMessage(processedLine);
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
        MessageUtils.playConfigSounds(player, plugin, "sound-effects.donation-confirmation");
    }

    private void processSimulatedDonation(Player player, double amount, String email, String method, String message, boolean isSandbox) {
        String txId = (isSandbox ? "FAKETX-" : "PUSHTX-") + System.currentTimeMillis();

        plugin.getDonationService().fulfillDonation(player.getName(), amount, email, method, message, txId, isSandbox);

        Map<String, String> fpP = new HashMap<>();
        fpP.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
        String langKey = isSandbox ? "fake-donation-processed" : "push-donation-processed";
        String defaultMsg = isSandbox ? "{PREFIX} <green>Fake donation form successfully processed!</green>" : "{PREFIX} <green>Donation form successfully pushed!</green>";
        player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString(langKey, defaultMsg), fpP));
    }

    private void processBedrockDonation(Player player, double amount, String email, String method, String message) {
        // Sound: donation-processed
        MessageUtils.playConfigSounds(player, plugin, "sound-effects.donation-processed");

        // Set cooldown here — Bedrock confirmation form was accepted
        if (plugin.getDonateCommand() != null) {
            plugin.getDonateCommand().setCooldown(player.getUniqueId());
        }
        
        Map<String, String> prcP = new HashMap<>();
        prcP.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
        player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("donation-processing", "{PREFIX} <gray>Processing your donation form... Please check your email shortly!</gray>"), prcP));

        plugin.getDonationPlatform().createDonation(player.getName(), email, amount, method, message).thenAccept(response -> {
            if (response.success()) {
                // Log request to ledger
                if (response.transactionId() != null) {
                    plugin.getTransactionRepository().createDonationRequest(response.transactionId(), amount, player.getName(), false);
                }

                // Send Email to Bedrock Player
                plugin.getEmailManager().sendPaymentEmail(
                        player.getName(), 
                        email, 
                        amount, 
                        MessageUtils.formatAmount(plugin, amount), 
                        method,
                        response.paymentUrl(),
                        message
                );

                Bukkit.getScheduler().runTask(plugin, () -> {
                    Map<String, String> succP = new HashMap<>();
                    succP.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                    player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("donation-email-sent", "{PREFIX} <green>A payment link has been sent to your email!</green>"), succP));
                    // Sound: donation-success
                    MessageUtils.playConfigSounds(player, plugin, "sound-effects.donation-success");
                });
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Map<String, String> errP = new HashMap<>();
                    errP.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                    errP.put(Constants.ERROR, response.message());
                    player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("api-error", "{PREFIX} <red>API Error: {ERROR}</red>"), errP));
                });
            }
        });
    }

    public void sendClearConfirmationForm(Player player, String target) {
        String title = plugin.getLangConfig().getString("transaction-clear-confirmation-bedrock.title", "Confirm Clear Transactions");
        String btnYes = plugin.getLangConfig().getString("transaction-clear-confirmation-bedrock.btn-yes", "Yes, Clear");
        String btnNo = plugin.getLangConfig().getString("transaction-clear-confirmation-bedrock.btn-no", "Cancel");

        StringBuilder contentInfo = new StringBuilder();
        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer legacySection = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();

        List<String> rawContent = plugin.getLangConfig().getStringList("transaction-clear-confirmation-bedrock.content");
        if (rawContent.isEmpty()) {
             rawContent = List.of(
                 "§cAre you sure you want to clear transactions for: §f" + target + "?",
                 "",
                 "§7This action cannot be undone."
             );
        }

        for (String line : rawContent) {
             String processedLine = line.replace("{TARGET}", target);
             net.kyori.adventure.text.Component component = MessageUtils.parseMessage(processedLine);
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
                    plugin.getTransactionRepository().clearTransactions(target);
                    Map<String, String> p = new HashMap<>();
                    p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                    p.put("{TARGET}", target);
                    player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-cleared", "{PREFIX} <green>Cleared transactions for: {TARGET}"), p));
                }
            })
            .build();
        
        FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
        MessageUtils.playConfigSounds(player, plugin, "sound-effects.donation-confirmation");
    }

    public void sendDeleteConfirmationForm(Player player, int id) {
        String title = plugin.getLangConfig().getString("transaction-delete-confirmation-bedrock.title", "Confirm Delete Transaction");
        String btnYes = plugin.getLangConfig().getString("transaction-delete-confirmation-bedrock.btn-yes", "Yes, Delete");
        String btnNo = plugin.getLangConfig().getString("transaction-delete-confirmation-bedrock.btn-no", "Cancel");

        StringBuilder contentInfo = new StringBuilder();
        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer legacySection = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();

        List<String> rawContent = plugin.getLangConfig().getStringList("transaction-delete-confirmation-bedrock.content");
        if (rawContent.isEmpty()) {
             rawContent = List.of(
                 "§cAre you sure you want to delete transaction §f#" + id + "?",
                 "",
                 "§7This action cannot be undone."
             );
        }

        for (String line : rawContent) {
             String processedLine = line.replace("{ID}", String.valueOf(id));
             net.kyori.adventure.text.Component component = MessageUtils.parseMessage(processedLine);
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
                    if (plugin.getTransactionRepository().deleteTransaction(id)) {
                        Map<String, String> p = new HashMap<>();
                        p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                        p.put(Constants.ID, String.valueOf(id));
                        player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-deleted", "{PREFIX} <green>Transaction #{ID} deleted."), p));
                    } else {
                        Map<String, String> p = new HashMap<>();
                        p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                        player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-not-found", "{PREFIX} <red>Transaction not found."), p));
                    }
                }
            })
            .build();
        
        FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
        MessageUtils.playConfigSounds(player, plugin, "sound-effects.donation-confirmation");
    }
}
