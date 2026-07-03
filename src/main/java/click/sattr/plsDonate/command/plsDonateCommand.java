package click.sattr.plsDonate.command;

import click.sattr.plsDonate.PlsDonate;
import click.sattr.plsDonate.database.repository.TransactionRepository;
import click.sattr.plsDonate.util.Constants;
import click.sattr.plsDonate.util.DonationValidator;
import click.sattr.plsDonate.util.HashUtils;
import click.sattr.plsDonate.util.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class plsDonateCommand implements CommandExecutor, TabCompleter {

    private final PlsDonate plugin;
    private final Map<String, DonationSimulationRequest> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingOperations = new ConcurrentHashMap<>();
    
    public plsDonateCommand(PlsDonate plugin) {
        this.plugin = plugin;
    }

    public record DonationSimulationRequest(UUID playerUuid, double amount, String email, String method, String message, boolean isSandbox) {}

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // Admin-only command - require permission
        if (!sender.hasPermission(Constants.PERM_ADMIN_HELP)) {
            MessageUtils.sendLangMessage(sender, plugin, "no-permission", null);
            return true;
        }

        if (args.length == 0) {
            Map<String, String> p = new HashMap<>();
            p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
            p.put(Constants.COMMAND, label);
            MessageUtils.sendLangMessage(sender, plugin, "invalid-usage", p);
            return true;
        }

        if (args[0].equalsIgnoreCase("help")) {
            Map<String, String> p = new HashMap<>();
            p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
            sender.sendMessage(MessageUtils.parseMessage("<gray>------ <green>plsDonate Help <gray>------<newline>", p));
            sender.sendMessage(MessageUtils.parseMessage("    <yellow>/pdn help <gray>- Show this help message", p));
            sender.sendMessage(MessageUtils.parseMessage("    <yellow>/pdn leaderboard <gray>(or <yellow>top<gray>) - Show top donators", p));
            sender.sendMessage(MessageUtils.parseMessage("    <yellow>/pdn milestone <gray>- Show donation goal", p));
            if (sender.hasPermission(Constants.PERM_ADMIN_HELP)) {
                sender.sendMessage(MessageUtils.parseMessage("    <yellow>/pdn transaction <gray>- Manage transactions", p));
                sender.sendMessage(MessageUtils.parseMessage("    <yellow>/pdn fakedonate <amount> <email> <method> [msg] <gray>- Simulate a sandbox donation (Hidden from stats)", p));
                sender.sendMessage(MessageUtils.parseMessage("    <yellow>/pdn pushdonate <amount> <email> <method> [msg] <gray>- Simulate a real donation (Included in stats)", p));
                sender.sendMessage(MessageUtils.parseMessage("    <yellow>/pdn testdiscord <gray>- Send a test Discord webhook embed", p));
                sender.sendMessage(MessageUtils.parseMessage("    <yellow>/pdn reload <gray>- Reload configuration", p));
            }
            sender.sendMessage(MessageUtils.parseMessage("<newline><gray>----------------------------", p));
            return true;
        }

        if (args[0].equalsIgnoreCase("leaderboard") || args[0].equalsIgnoreCase("top")) {
            if (!sender.hasPermission(Constants.PERM_DONATE_TOP)) {
                MessageUtils.sendLangMessage(sender, plugin, "no-permission", null);
                return true;
            }
            int page = 1;
            if (args.length >= 2) {
                try {
                    page = Integer.parseInt(args[1]);
                    if (page < 1) page = 1;
                } catch (NumberFormatException ignored) {}
            }
            plugin.getStatsManager().displayLeaderboard(sender, page, "/" + label + " " + args[0].toLowerCase());
            return true;
        }

        if (args[0].equalsIgnoreCase("milestone")) {
            if (!sender.hasPermission(Constants.PERM_DONATE_MILESTONE)) {
                MessageUtils.sendLangMessage(sender, plugin, "no-permission", null);
                return true;
            }
            plugin.getStatsManager().displayMilestone(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("transaction") && sender.hasPermission(Constants.PERM_ADMIN_TRANSACTION)) {
            if (args.length == 1) {
                Map<String, String> p = new HashMap<>();
                p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-help-header", "<gray>------ <gold>Transaction Management <gray>------"), p));
                sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-help-list", "  <yellow>/pdn transaction list [page] <gray>- List transactions"), p));
                sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-help-info", "  <yellow>/pdn transaction info <id> <gray>- Detailed info"), p));
                sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-help-delete", "  <yellow>/pdn transaction delete <id> <gray>- Delete record"), p));
                sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-help-setstatus", "  <yellow>/pdn transaction setstatus <id> <status> <gray>- Force status"), p));
                sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-help-clear", "  <yellow>/pdn transaction clear <player|all> <gray>- Mass delete"), p));
                sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-help-footer", "<gray>----------------------------"), p));
                return true;
            }

            String sub = args[1].toLowerCase();
            Map<String, String> p = new HashMap<>();
            p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
            p.put(Constants.COMMAND, label);

            switch (sub) {
                case "list":
                    int page = 1;
                    if (args.length >= 3) {
                        try { page = Integer.parseInt(args[2]); if (page < 1) page = 1; } catch (NumberFormatException ignored) {}
                    }
                    displayTransactionList(sender, page);
                    break;
                case "info":
                    if (args.length < 3) { sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("invalid-usage", "{PREFIX} <red>Invalid usage."), p)); return true; }
                    try { displayTransactionInfo(sender, Integer.parseInt(args[2])); } catch (NumberFormatException e) { sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-invalid-id", "{PREFIX} <red>Invalid ID."), p)); }
                    break;
                case "delete":
                    if (args.length < 3) { sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("invalid-usage", "{PREFIX} <red>Invalid usage."), p)); return true; }
                    try {
                        int id = Integer.parseInt(args[2]);
                        if (sender instanceof Player player) {
                            if (plugin.getBedrockFormHandler() != null && plugin.getBedrockFormHandler().isBedrockPlayer(player)) {
                                plugin.getBedrockFormHandler().sendDeleteConfirmationForm(player, id);
                                return true;
                            }

                            if (isOperationConfirmed(player, "delete", String.valueOf(id))) {
                                if (plugin.getTransactionRepository().deleteTransaction(id)) {
                                    plugin.getStatsManager().refresh();
                                    p.put(Constants.ID, String.valueOf(id));
                                    sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-deleted", "{PREFIX} <green>Transaction #{ID} deleted."), p));
                                } else {
                                    sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-not-found", "{PREFIX} <red>Transaction not found."), p));
                                }
                            } else {
                                requestOperationConfirmation(player, "delete", String.valueOf(id));
                                String cmd = "/" + label + " transaction delete " + id;

                                List<String> lines = plugin.getLangConfig().getStringList("transaction-delete-confirmation-java");
                                if (lines.isEmpty()) {
                                    lines = List.of(
                                        "",
                                        "{PREFIX} <yellow>Are you sure you want to delete transaction <red>#{ID}</red>?</yellow>",
                                        "",
                                        "     <click:run_command:\"{COMMAND}\"><green><underlined>[Yes]</underlined></green></click>    <gray>[No]",
                                        ""
                                    );
                                }

                                Map<String, String> confirmationPlaceholders = new HashMap<>(p);
                                confirmationPlaceholders.put(Constants.ID, String.valueOf(id));
                                confirmationPlaceholders.put(Constants.COMMAND, cmd);

                                for (String line : lines) {
                                    String processedLine = line;
                                    for (Map.Entry<String, String> entry : confirmationPlaceholders.entrySet()) {
                                        processedLine = processedLine.replace(entry.getKey(), entry.getValue());
                                    }
                                    sender.sendMessage(MessageUtils.parseMessage(processedLine));
                                }

                                MessageUtils.playConfigSounds(player, plugin, "sound-effects.donation-confirmation");
                            }
                        } else {
                            if (plugin.getTransactionRepository().deleteTransaction(id)) {
                                plugin.getStatsManager().refresh();
                                p.put(Constants.ID, String.valueOf(id));
                                sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-deleted", "{PREFIX} <green>Transaction #{ID} deleted."), p));
                            } else {
                                sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-not-found", "{PREFIX} <red>Transaction not found."), p));
                            }
                        }
                    } catch (NumberFormatException e) {
                        sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-invalid-id", "{PREFIX} <red>Invalid ID."), p));
                    }
                    break;
                case "setstatus":
                    if (args.length < 4) { sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("invalid-usage", "{PREFIX} <red>Invalid usage."), p)); return true; }
                    try {
                        int id = Integer.parseInt(args[2]);
                        String status = args[3].toUpperCase();
                        if (plugin.getTransactionRepository().updateTransactionStatus(id, status)) {
                            plugin.getStatsManager().refresh();
                            p.put(Constants.ID, String.valueOf(id));
                            p.put("{STATUS}", status);
                            sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-status-updated", "{PREFIX} <green>Status of #{ID} updated to {STATUS}."), p));
                        } else sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-not-found", "{PREFIX} <red>Transaction not found."), p));
                    } catch (NumberFormatException e) { sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-invalid-id", "{PREFIX} <red>Invalid ID."), p)); }
                    break;
                case "clear":
                    if (args.length < 3) { sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("invalid-usage", "{PREFIX} <red>Invalid usage."), p)); return true; }
                    String target = args[2];
                    if (sender instanceof Player player) {
                        if (plugin.getBedrockFormHandler() != null && plugin.getBedrockFormHandler().isBedrockPlayer(player)) {
                            plugin.getBedrockFormHandler().sendClearConfirmationForm(player, target);
                            return true;
                        }

                        if (isOperationConfirmed(player, "clear", target)) {
                            plugin.getTransactionRepository().clearTransactions(target);
                            plugin.getStatsManager().refresh();
                            p.put("{TARGET}", target);
                            sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-cleared", "{PREFIX} <green>Cleared transactions for: {TARGET}"), p));
                        } else {
                            requestOperationConfirmation(player, "clear", target);
                            String cmd = "/" + label + " transaction clear " + target;

                            List<String> lines = plugin.getLangConfig().getStringList("transaction-clear-confirmation-java");
                            if (lines.isEmpty()) {
                                lines = List.of(
                                    "",
                                    "{PREFIX} <yellow>Are you sure you want to clear transactions for: <red>{TARGET}</red>?</yellow>",
                                    "",
                                    "     <click:run_command:\"{COMMAND}\"><green><underlined>[Yes]</underlined></green></click>    <gray>[No]",
                                    ""
                                );
                            }

                            Map<String, String> confirmationPlaceholders = new HashMap<>(p);
                            confirmationPlaceholders.put("{TARGET}", target);
                            confirmationPlaceholders.put(Constants.COMMAND, cmd);

                            for (String line : lines) {
                                String processedLine = line;
                                for (Map.Entry<String, String> entry : confirmationPlaceholders.entrySet()) {
                                    processedLine = processedLine.replace(entry.getKey(), entry.getValue());
                                }
                                sender.sendMessage(MessageUtils.parseMessage(processedLine));
                            }

                            MessageUtils.playConfigSounds(player, plugin, "sound-effects.donation-confirmation");
                        }
                    } else {
                        plugin.getTransactionRepository().clearTransactions(target);
                        plugin.getStatsManager().refresh();
                        p.put("{TARGET}", target);
                        sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-cleared", "{PREFIX} <green>Cleared transactions for: {TARGET}"), p));
                    }
                    break;
                default:
                    sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("invalid-usage", "{PREFIX} <red>Invalid usage."), p));
                    break;
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("fakedonate") || args[0].equalsIgnoreCase("pushdonate")) {
            String sub = args[0].toLowerCase();
            boolean isSandbox = sub.equals("fakedonate");

            String requiredPerm = isSandbox ? Constants.PERM_ADMIN_FAKEDONATE : Constants.PERM_ADMIN_PUSHDONATE;
            if (!sender.hasPermission(requiredPerm)) {
                MessageUtils.sendLangMessage(sender, plugin, "no-permission", null);
                return true;
            }
            if (!(sender instanceof Player player)) {
                Map<String, String> pOnly = new HashMap<>();
                pOnly.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("command-only-players", "{PREFIX} <red>Only players can execute this command.</red>"), pOnly));
                return true;
            }

            // Check if it's a confirmation hash
            if (args.length == 2 && DonationValidator.MD5_PATTERN.matcher(args[1]).matches()) {
                String hash = args[1].toLowerCase();
                DonationSimulationRequest request = pendingRequests.get(hash);

                if (request != null && request.playerUuid().equals(player.getUniqueId())) {
                    pendingRequests.remove(hash);
                    executeSimulatedDonation(player, request.amount(), request.email(), request.method(), request.message(), request.isSandbox());
                    return true;
                } else {
                    Map<String, String> p = new HashMap<>();
                    p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                    p.put(Constants.COMMAND, label);
                    player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("invalid-usage", "{PREFIX} <red>Invalid usage. <reset>try to run <yellow>/donate help<reset> for help"), p));
                    return true;
                }
            }

            if (args.length < 4) {
                Map<String, String> p = new HashMap<>();
                p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                p.put(Constants.COMMAND, label);
                player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("invalid-usage", "{PREFIX} <red>Invalid usage. <reset>try to run <yellow>/donate help<reset> for help"), p));
                return true;
            }

            Double parsedAmount = DonationValidator.parseAmount(args[1], player, plugin);
            if (parsedAmount == null) return true;
            double amount = parsedAmount;

            String email = args[2];
            if (!DonationValidator.validateEmail(email, player, plugin)) return true;

            String method = args[3].toLowerCase();
            if (!DonationValidator.validateMethod(method, amount, player, plugin)) return true;

            String message = DonationValidator.buildMessage(args, 4);

            if (plugin.getConfig().getBoolean(Constants.CONF_DONATE_CONFIRMATION, true)) {
                if (plugin.getBedrockFormHandler() != null && plugin.getBedrockFormHandler().isBedrockPlayer(player)) {
                    plugin.getBedrockFormHandler().sendConfirmationForm(player, amount, email, method, message, true, isSandbox);
                    return true;
                }

                // Generate MD5 Hash
                long timestamp = System.currentTimeMillis();
                String rawString = player.getUniqueId().toString() + "-" + timestamp + "-" + amount + "-" + email + "-" + method + "-" + isSandbox;
                String hash = HashUtils.md5(rawString);

                if (hash == null) {
                    MessageUtils.sendLangMessage(player, plugin, "general-error", null);
                    return true;
                }

                // Clear old requests from this admin
                pendingRequests.entrySet().removeIf(entry -> entry.getValue().playerUuid().equals(player.getUniqueId()));
                pendingRequests.put(hash, new DonationSimulationRequest(player.getUniqueId(), amount, email, method, message, isSandbox));

                Map<String, String> p = MessageUtils.getDonationPlaceholders(plugin, amount, player.getName(), email, method, message);
                p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                p.put(Constants.COMMAND, "/pdn " + sub + " " + hash);

                // Java admin with dialog support — show native dialog
                if (plugin.getJavaDialogHandler() != null && click.sattr.plsDonate.util.ProtocolVersionUtil.supportsDialogs(player)) {
                    plugin.getJavaDialogHandler().openAdminConfirmationDialog(player, hash, amount, email, method, message, isSandbox, sub);
                    MessageUtils.playConfigSounds(player, plugin, "sound-effects.donation-confirmation");
                } else {
                    // Chat fallback for older clients or when dialog is unavailable
                    String titleSuffix = isSandbox ? " <dark_gray>(fake)</dark_gray>" : " <dark_gray>(push)</dark_gray>";
                    List<String> lines = plugin.getLangConfig().getStringList("donation-confirmation-java");
                    String prefix = plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX);
                    boolean titleModified = false;
                    for (String line : lines) {
                        String processedLine = line;
                        for (Map.Entry<String, String> entry : p.entrySet()) {
                            processedLine = processedLine.replace(entry.getKey(), entry.getValue());
                        }
                        processedLine = processedLine.replace(Constants.PREFIX, prefix);
                        if (!titleModified && processedLine.contains("Donation")) {
                            processedLine = processedLine.replace("Donation", "Donation" + titleSuffix);
                            titleModified = true;
                        }
                        player.sendMessage(MessageUtils.parseMessage(processedLine));
                    }
                    MessageUtils.playConfigSounds(player, plugin, "sound-effects.donation-confirmation");
                }
            } else {
                executeSimulatedDonation(player, amount, email, method, message, isSandbox);
            }
            return true;
        }


        if (args[0].equalsIgnoreCase("testdiscord")) {
            if (!sender.hasPermission(Constants.PERM_ADMIN_TESTDISCORD)) {
                MessageUtils.sendLangMessage(sender, plugin, "no-permission", null);
                return true;
            }

            Map<String, String> p = new HashMap<>();
            p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));

            if (plugin.getDiscordManager() == null) {
                sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("discord-test-unavailable", "{PREFIX} <red>Discord notifications are not available."), p));
                return true;
            }

            // Use the sender's own name when a player runs it (so {PLAYER_HEAD} shows their head);
            // fall back to a sample name for console. The test bypasses 'discord.enabled' on purpose
            // so admins can verify their webhook + layout before flipping the feature live.
            String testName = (sender instanceof Player player) ? player.getName() : "Notch";
            int sent = plugin.getDiscordManager().sendTest(testName);

            if (sent == 0) {
                sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("discord-test-no-webhooks", "{PREFIX} <red>No valid webhooks set in <yellow>discord.webhooks<red>."), p));
            } else {
                p.put("{COUNT}", String.valueOf(sent));
                sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("discord-test-sent", "{PREFIX} <green>Test embed dispatched to <yellow>{COUNT}<green> webhook(s). If nothing appears, check the console for errors."), p));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(Constants.PERM_ADMIN_RELOAD)) {
                MessageUtils.sendLangMessage(sender, plugin, "no-permission", null);
                return true;
            }
            plugin.reloadPlugin(sender);
            MessageUtils.sendLangMessage(sender, plugin, "reload-success", null);
            return true;
        }

        Map<String, String> p = new HashMap<>();
        p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
        p.put(Constants.COMMAND, label);
        MessageUtils.sendLangMessage(sender, plugin, "invalid-usage", p);
        return true;
    }

    private void displayTransactionList(CommandSender sender, int page) {
        int limit = 10;
        int offset = (page - 1) * limit;
        List<TransactionRepository.TransactionRecord> records = plugin.getTransactionRepository().getTransactions(limit, offset);
        int totalCount = plugin.getTransactionRepository().getTransactionsCount();
        int totalPages = (int) Math.ceil((double) totalCount / limit);

        Map<String, String> p = new HashMap<>();
        p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
        p.put("{PAGE}", String.valueOf(page));
        p.put("{TOTAL_PAGES}", String.valueOf(totalPages));

        sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-list-header", "<gray>------ <gold>Transaction List (Page {PAGE}/{TOTAL_PAGES}) <gray>------"), p));
        if (records.isEmpty()) {
            sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-list-empty", "<gray>No transactions found."), p));
        } else {
            for (TransactionRepository.TransactionRecord record : records) {
                Map<String, String> rp = new HashMap<>(p);
                rp.put(Constants.ID, String.valueOf(record.id()));
                rp.put("{NAME}", record.donorName());
                rp.put(Constants.AMOUNT_FORMATTED, MessageUtils.formatAmount(plugin, record.amount()));
                rp.put("{STATUS_COLORED}", MessageUtils.formatStatus(plugin.getLangConfig(), record.status()));
                
                sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-list-format", "<gray>#{ID} | {NAME} | Rp{AMOUNT_FORMATTED} | {STATUS_COLORED}"), rp));
            }
        }

        if (page < totalPages) {
            String footer = plugin.getLangConfig().getString("transaction-list-footer", "<gray>----------------------------");
            Map<String, String> nextP = new HashMap<>(p);
            nextP.put("{NEXT_PAGE}", String.valueOf(page + 1));
            String nextBtn = plugin.getLangConfig().getString("transaction-list-next-btn", " [Next Page »]");
            sender.sendMessage(MessageUtils.parseMessage(footer + nextBtn, nextP));
        } else {
            sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-list-footer", "<gray>----------------------------"), p));
        }
    }

    private void displayTransactionInfo(CommandSender sender, int id) {
        TransactionRepository.TransactionRecord r = plugin.getTransactionRepository().getTransactionById(id);
        if (r == null) {
            Map<String, String> p = new HashMap<>();
            p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
            sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-not-found", "<red>Transaction not found."), p));
            return;
        }

        Map<String, String> p = new HashMap<>();
        p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
        p.put(Constants.ID, String.valueOf(r.id()));
        p.put("{TX_ID}", r.txId());
        p.put("{NAME}", r.donorName());
        p.put(Constants.AMOUNT_FORMATTED, MessageUtils.formatAmount(plugin, r.amount()));
        p.put("{STATUS_COLORED}", MessageUtils.formatStatus(plugin.getLangConfig(), r.status()));
        p.put("{TYPE_COLORED}", MessageUtils.formatType(plugin.getLangConfig(), r.isSandbox()));
        p.put("{DATE}", formatDate(r.timestamp()));
        p.put("{COMPLETED_AT}", r.completedAt() > 0 ? formatDate(r.completedAt()) : "-");
        p.put("{CHECKSUM}", r.checksum());

        sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-info-header", "<gray>------ <gold>Transaction Info: #{ID} <gray>------"), p));
        sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-info-txid", " <gray>» <white>TX-ID: <yellow>{TX_ID}"), p));
        sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-info-donor", " <gray>» <white>Donor: <yellow>{NAME}"), p));
        sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-info-amount", " <gray>» <white>Amount: <green>Rp{AMOUNT_FORMATTED}"), p));
        sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-info-status", " <gray>» <white>Status: {STATUS_COLORED}"), p));
        sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-info-type", " <gray>» <white>Type: {TYPE_COLORED}"), p));
        sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-info-date", " <gray>» <white>Date: <gray>{DATE}"), p));
        if (r.completedAt() > 0) sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-info-completed", " <gray>» <white>Completed: <gray>{COMPLETED_AT}"), p));
        sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-info-checksum", " <gray>» <white>Checksum: <dark_gray>{CHECKSUM}"), p));
        sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-info-footer", "<gray>----------------------------"), p));
    }

    private String formatDate(long timestamp) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(timestamp * 1000L));
    }

    private void executeSimulatedDonation(Player player, double amount, String email, String method, String message, boolean isSandbox) {
        String txId = (isSandbox ? "FAKETX-" : "PUSHTX-") + System.currentTimeMillis();

        plugin.getDonationService().fulfillSimulatedDonation(player.getName(), player.getUniqueId().toString(), amount, email, method, message, txId, isSandbox);

        Map<String, String> fP = new HashMap<>();
        fP.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
        String langKey = isSandbox ? "fake-donation-triggered" : "push-donation-triggered";
        String defaultMsg = isSandbox ? "{PREFIX} <green>Fake donation successfully triggered (Sandbox Mode)!</green>" : "{PREFIX} <green>Donation successfully pushed (Live Mode)!</green>";
        player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString(langKey, defaultMsg), fP));
    }

    public void clearPendingRequests(UUID uuid) {
        pendingRequests.entrySet().removeIf(entry -> entry.getValue().playerUuid().equals(uuid));
        String prefix = uuid.toString() + ":";
        pendingOperations.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private boolean isOperationConfirmed(Player player, String actionType, String target) {
        String key = player.getUniqueId().toString() + ":" + actionType + ":" + target;
        Long expiry = pendingOperations.get(key);
        if (expiry != null) {
            pendingOperations.remove(key); // Remove immediately to prevent replay
            return System.currentTimeMillis() <= expiry;
        }
        return false;
    }

    private void requestOperationConfirmation(Player player, String actionType, String target) {
        String key = player.getUniqueId().toString() + ":" + actionType + ":" + target;
        pendingOperations.put(key, System.currentTimeMillis() + 30000L); // 30 seconds expiry
    }


    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // Admin-only command — hide completions from players without permission
        if (!sender.hasPermission(Constants.PERM_ADMIN_HELP)) {
            return new ArrayList<>();
        }
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String sub = args[0].toLowerCase();
            if ("fakedonate".startsWith(sub) && sender.hasPermission(Constants.PERM_ADMIN_FAKEDONATE)) completions.add("fakedonate");
            if ("pushdonate".startsWith(sub) && sender.hasPermission(Constants.PERM_ADMIN_PUSHDONATE)) completions.add("pushdonate");
            if ("testdiscord".startsWith(sub) && sender.hasPermission(Constants.PERM_ADMIN_TESTDISCORD)) completions.add("testdiscord");
            if ("reload".startsWith(sub) && sender.hasPermission(Constants.PERM_ADMIN_RELOAD)) completions.add("reload");
            if ("leaderboard".startsWith(sub) && sender.hasPermission(Constants.PERM_DONATE_TOP)) completions.add("leaderboard");
            if ("top".startsWith(sub) && sender.hasPermission(Constants.PERM_DONATE_TOP)) completions.add("top");
            if ("milestone".startsWith(sub) && sender.hasPermission(Constants.PERM_DONATE_MILESTONE)) completions.add("milestone");
            if ("transaction".startsWith(sub) && sender.hasPermission(Constants.PERM_ADMIN_TRANSACTION)) completions.add("transaction");
            if ("help".startsWith(sub)) completions.add("help");
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("leaderboard") || args[0].equalsIgnoreCase("top"))) {
            completions.add("1");
            completions.add("2");
            completions.add("3");
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("transaction") && sender.hasPermission(Constants.PERM_ADMIN_TRANSACTION)) {
            if (args.length == 2) {
                String sub = args[1].toLowerCase();
                List<String> subs = List.of("list", "info", "delete", "setstatus", "clear");
                for (String s : subs) if (s.startsWith(sub)) completions.add(s);
            } else if (args.length == 3) {
                String sub = args[2].toLowerCase();
                if (args[1].equalsIgnoreCase("setstatus") || args[1].equalsIgnoreCase("delete") || args[1].equalsIgnoreCase("info")) {
                    completions.add("[id]");
                } else if (args[1].equalsIgnoreCase("clear")) {
                    completions.add("all");
                    for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(sub)) completions.add(p.getName());
                }
            } else if (args.length == 4 && args[1].equalsIgnoreCase("setstatus")) {
                List<String> stats = List.of("PENDING", "COMPLETED", "VOID");
                for (String s : stats) if (s.toLowerCase().startsWith(args[3].toLowerCase())) completions.add(s);
            }
        } else if (args.length > 1 && (args[0].equalsIgnoreCase("fakedonate") || args[0].equalsIgnoreCase("pushdonate")) && sender.hasPermission(args[0].equalsIgnoreCase("fakedonate") ? Constants.PERM_ADMIN_FAKEDONATE : Constants.PERM_ADMIN_PUSHDONATE)) {
            if (args.length == 2) {
                String sub = args[1].toLowerCase();
                double min = plugin.getConfig().getDouble(Constants.CONF_DONATE_MIN_AMOUNT, 1000);
                long[] suggestions = { (long) min, (long) min + 5000, (long) min + 10000 };
                for (long s : suggestions) {
                    if (String.valueOf(s).startsWith(sub)) completions.add(String.valueOf(s));
                }
            } else if (args.length == 3) {
                String sub = args[2].toLowerCase();
                List<String> emails = plugin.getLangConfig().getStringList("donation-tab-completions-args2");
                if (emails.isEmpty()) emails = List.of("test@gmail.com");
                for (String e : emails) {
                    if (e.toLowerCase().startsWith(sub)) completions.add(e);
                }
            } else if (args.length == 4) {
                String sub = args[3].toLowerCase();
                List<String> methods = List.of("qris", "gopay", "paypal");
                for (String m : methods) {
                    if (m.startsWith(sub)) completions.add(m);
                }
            } else if (args.length == 5) {
                String sub = args[4].toLowerCase();
                List<String> msgs = plugin.getLangConfig().getStringList("donation-tab-completions-args3");
                if (msgs.isEmpty()) msgs = List.of("Simulation");
                for (String m : msgs) {
                    if (m.toLowerCase().startsWith(sub)) completions.add(m);
                }
            }
        }
        return completions;
    }
}
