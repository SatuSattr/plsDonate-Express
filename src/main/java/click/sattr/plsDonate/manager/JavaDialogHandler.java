package click.sattr.plsDonate.manager;

import click.sattr.plsDonate.PlsDonate;
import click.sattr.plsDonate.util.Constants;
import click.sattr.plsDonate.util.MessageUtils;
import click.sattr.plsDonate.util.PluginLogger;
import io.github.projectunified.unidialog.paper.PaperDialogManager;
import io.github.projectunified.unidialog.paper.dialog.PaperMultiActionDialog;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public class JavaDialogHandler {

    private final PlsDonate plugin;
    private final PaperDialogManager dialogManager;

    public JavaDialogHandler(PlsDonate plugin) {
        this.plugin = plugin;
        this.dialogManager = new PaperDialogManager(plugin);
    }

    /**
     * Returns true if this server supports Java Edition custom dialogs (1.21.6+).
     * Checks for the Paper dialog API class and verifies the server version string.
     */
    public static boolean isServerSupported() {
        try {
            Class.forName("io.papermc.paper.registry.data.dialog.type.DialogType");
            String version = org.bukkit.Bukkit.getServer().getBukkitVersion();
            // version string like "1.21.6-R0.1-SNAPSHOT" or "1.22.0-R0.1-SNAPSHOT"
            // Extract major.minor.patch from the version string
            String[] parts = version.split("[.-]");
            if (parts.length < 2) return false;
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = parts.length > 2 ? parseIntSafe(parts[2]) : 0;
            // Require 1.21.6 or higher
            if (major > 1) return true;
            if (major < 1) return false;
            if (minor > 21) return true;
            if (minor < 21) return false;
            return patch >= 6;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Opens the multi-field donation form dialog for a Java Edition player.
     *
     * @return true if the dialog was opened successfully, false on error
     */
    public boolean openDonationForm(Player player) {
        try {
            final List<String> methodIds = List.of("qris", "gopay", "paypal");

            Component title = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("donation-form-java.title", "Donation"));
            Component amountLabel = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("donation-form-java.amount-label", "Amount"));
            Component emailLabel = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("donation-form-java.email-label", "Email"));
            Component methodLabel = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("donation-form-java.method-label", "Payment Method"));
            Component messageLabel = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("donation-form-java.message-label", "Message (optional)"));

            Component submitLabel = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("donation-form-java.submit-label", "Submit"));
            Component cancelLabel = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("donation-form-java.cancel-label", "Cancel"));

            PaperMultiActionDialog dialog = dialogManager.createMultiActionDialog()
                    .title(title)
                    .afterAction(io.github.projectunified.unidialog.core.dialog.Dialog.AfterAction.CLOSE)
                    .input("amount", b -> b.textInput()
                            .label(amountLabel)
                            .maxLength(10))
                    .input("email", b -> b.textInput()
                            .label(emailLabel)
                            .maxLength(100))
                    .input("method", b -> {
                        var soi = b.singleOptionInput().label(methodLabel);
                        boolean first = true;
                        for (String id : methodIds) {
                            Component displayName = MessageUtils.parseMessage(
                                    plugin.getLangConfig().getString("donation-form-java.method-" + id, id.toUpperCase()));
                            soi.option(id, displayName, first);
                            first = false;
                        }
                    })
                    .input("message", b -> b.textInput()
                            .label(messageLabel)
                            .maxLength(100))
                    // Submit button — fires "/donate $(amount) $(email) $(method) $(message)"
                    .action(a -> a
                            .label(submitLabel)
                            .dynamicRunCommand("donate $(amount) $(email) $(method) $(message)"))
                    // Cancel button — closes the dialog
                    .exitAction(a -> a.label(cancelLabel));

            // Add optional item body
            String displayItemStr = plugin.getLangConfig().getString("donation-form-java.display-item", "");
            if (!displayItemStr.isEmpty()) {
                org.bukkit.Material mat = org.bukkit.Material.matchMaterial(displayItemStr);
                if (mat != null) {
                    org.bukkit.inventory.ItemStack displayItem = new org.bukkit.inventory.ItemStack(mat);
                    int itemWidth = plugin.getLangConfig().getInt("donation-form-java.display-item-width", 64);
                    int itemHeight = plugin.getLangConfig().getInt("donation-form-java.display-item-height", 64);
                    dialog.body(b -> b.item()
                            .item(displayItem)
                            .width(itemWidth)
                            .height(itemHeight)
                            .showDecorations(false)
                            .showTooltip(false));
                }
            }

            // Add optional header text
            String headerStr = plugin.getLangConfig().getString("donation-form-java.header-text", "");
            if (!headerStr.isEmpty()) {
                Component headerComponent = MessageUtils.parseMessage(headerStr);
                dialog.body(b -> b.text().text(headerComponent));
            }

            dialog.opener().open(player);
            return true;
        } catch (Throwable t) {
            PluginLogger.warn("JavaDialogHandler: failed to open donation form for "
                    + player.getName() + ": " + t);
            return false;
        }
    }

    /**
     * Opens a confirmation dialog for a pending donation using multi_action type.
     * Yes button appears in the scrollable body area (near header-text),
     * Cancel button appears in the footer via exitAction.
     *
     * @return true if the dialog was opened successfully, false on error
     */
    public boolean openConfirmationDialog(Player player, String hash, double amount,
                                          String email, String method, String message) {
        try {
            Component title = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("donation-confirmation-java-dialog.title", "Confirm Your Donation Request"));

            // Build header-text with donation placeholders
            Map<String, String> p = MessageUtils.getDonationPlaceholders(
                    plugin, amount, player.getName(), email, method, message);
            p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));

            String headerTemplate = plugin.getLangConfig().getString(
                    "donation-confirmation-java-dialog.header-text",
                    "<white>Amount: <yellow>{AMOUNT_FORMATTED}\n<white>Email: <yellow>{EMAIL}\n<white>Method: <yellow>{METHOD}\n<white>Message: <gray>{MESSAGE}");
            String headerText = headerTemplate;
            for (Map.Entry<String, String> entry : p.entrySet()) {
                headerText = headerText.replace(entry.getKey(), entry.getValue());
            }
            Component headerComponent = MessageUtils.parseMessage(headerText);

            Component yesLabel = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("donation-confirmation-java-dialog.yes-label", "Yes"));
            Component noLabel = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("donation-confirmation-java-dialog.no-label", "Cancel"));

            final String confirmCommand = "donate " + hash;

            PaperMultiActionDialog dialog = dialogManager.createMultiActionDialog()
                    .title(title)
                    .afterAction(io.github.projectunified.unidialog.core.dialog.Dialog.AfterAction.CLOSE)
                    .action(a -> a
                            .label(yesLabel)
                            .runCommand(confirmCommand))
                    .exitAction(a -> a
                            .label(noLabel));

            // Add optional item body first (appears above header-text)
            String displayItemStr = plugin.getLangConfig().getString("donation-confirmation-java-dialog.display-item", "");
            if (!displayItemStr.isEmpty()) {
                org.bukkit.Material mat = org.bukkit.Material.matchMaterial(displayItemStr);
                if (mat != null) {
                    org.bukkit.inventory.ItemStack displayItem = new org.bukkit.inventory.ItemStack(mat);
                    int itemWidth = plugin.getLangConfig().getInt("donation-confirmation-java-dialog.display-item-width", 16);
                    int itemHeight = plugin.getLangConfig().getInt("donation-confirmation-java-dialog.display-item-height", 16);
                    dialog.body(b -> b.item()
                            .item(displayItem)
                            .width(itemWidth)
                            .height(itemHeight)
                            .showDecorations(false)
                            .showTooltip(false));
                }
            }

            // Header-text appears below item
            dialog.body(b -> b.text().text(headerComponent));

            dialog.opener().open(player);
            return true;
        } catch (Throwable t) {
            PluginLogger.warn("JavaDialogHandler: failed to open confirmation dialog for "
                    + player.getName() + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * Opens admin confirmation dialog for fakedonate/pushdonate commands.
     * Similar to openConfirmationDialog but dispatches the admin command on accept.
     *
     * @return true if the dialog was opened successfully, false on error
     */
    public boolean openAdminConfirmationDialog(Player player, String hash, double amount,
                                                String email, String method, String message,
                                                boolean isSandbox, String subCommand) {
        try {
            String typeLabel = isSandbox ? "Sandbox" : "Live";
            Component title = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("admin-donation-confirmation-java-dialog.title",
                            "Admin Donation Confirmation"));

            // Build header text with donation details
            Map<String, String> p = MessageUtils.getDonationPlaceholders(
                    plugin, amount, player.getName(), email, method, message);
            p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
            p.put("{TYPE}", typeLabel);

            String headerTemplate = plugin.getLangConfig().getString(
                    "admin-donation-confirmation-java-dialog.header-text",
                    "<yellow>Type: {TYPE}\n<white>Amount: <yellow>{AMOUNT_FORMATTED}\n<white>Email: <yellow>{EMAIL}\n<white>Method: <yellow>{METHOD}\n<white>Message: <gray>{MESSAGE}");
            String headerText = headerTemplate;
            for (Map.Entry<String, String> entry : p.entrySet()) {
                headerText = headerText.replace(entry.getKey(), entry.getValue());
            }
            Component headerComponent = MessageUtils.parseMessage(headerText);

            Component yesLabel = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("admin-donation-confirmation-java-dialog.yes-label", "Confirm"));
            Component noLabel = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("admin-donation-confirmation-java-dialog.no-label", "Cancel"));

            final String confirmCommand = "pdn " + subCommand + " " + hash;

            PaperMultiActionDialog dialog = dialogManager.createMultiActionDialog()
                    .title(title)
                    .afterAction(io.github.projectunified.unidialog.core.dialog.Dialog.AfterAction.CLOSE)
                    .action(a -> a
                            .label(yesLabel)
                            .runCommand(confirmCommand))
                    .exitAction(a -> a
                            .label(noLabel));

            dialog.body(b -> b.text().text(headerComponent));
            dialog.opener().open(player);
            return true;
        } catch (Throwable t) {
            PluginLogger.warn("JavaDialogHandler: failed to open admin confirmation dialog for "
                    + player.getName() + ": " + t.getMessage());
            return false;
        }
    }
}
