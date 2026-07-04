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
import click.sattr.plsDonate.util.ProtocolVersionUtil;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class DonateCommand implements CommandExecutor, TabCompleter {

    private final PlsDonate plugin;
    // Map to store pending requests: Map<MD5Hash, DonationRequest>
    private final Map<String, DonationRequest> pendingRequests = new ConcurrentHashMap<>();
    // Map to store cooldowns: Map<PlayerUUID, LastUsageTimestamp>
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    
    public DonateCommand(PlsDonate plugin) {
        this.plugin = plugin;
    }

    public record DonationRequest(UUID playerUuid, double amount, String email, String method, String message) {}

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Map<String, String> pOnly = new HashMap<>();
            pOnly.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
            sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("command-only-players", "{PREFIX} <red>Only players can execute this command.</red>"), pOnly));
            return true;
        }

        // Bedrock donation form (no args) — opens a form for filling amount, email, method, message
        if (args.length == 0
                && plugin.getBedrockFormHandler() != null
                && plugin.getBedrockFormHandler().isBedrockPlayer(player)) {

            if (!player.hasPermission(Constants.PERM_DONATE_REQUEST)) {
                MessageUtils.sendLangMessage(player, plugin, "no-permission", null);
                return true;
            }

            if (!player.hasPermission(Constants.PERM_DONATE_BYPASS_COOLDOWN)) {
                long lastUsage = cooldowns.getOrDefault(player.getUniqueId(), 0L);
                int cooldownSeconds = plugin.getConfig().getInt(Constants.CONF_DONATE_COOLDOWN, 10);
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastUsage < (cooldownSeconds * 1000L)) {
                    long remaining = (cooldownSeconds * 1000L - (currentTime - lastUsage)) / 1000L;
                    Map<String, String> p = new HashMap<>();
                    p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                    p.put(Constants.TIME, String.valueOf(remaining + 1));
                    player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("cooldown-error", "{PREFIX} <white>Sorry, you're still in <yellow>{TIME}s <white>cooldown"), p));
                    return true;
                }
            }

            // Cooldown will be set when form is confirmed, not when opened
            plugin.getBedrockFormHandler().openDonationForm(player);
            return true;
        }

        // Java donation dialog (no args, Java player, client 1.21.6+)
        if (args.length == 0 && plugin.getJavaDialogHandler() != null 
                && ProtocolVersionUtil.supportsDialogs(player)) {
            if (!player.hasPermission(Constants.PERM_DONATE_REQUEST)) {
                MessageUtils.sendLangMessage(player, plugin, "no-permission", null);
                return true;
            }
            if (!player.hasPermission(Constants.PERM_DONATE_BYPASS_COOLDOWN)) {
                long lastUsage = cooldowns.getOrDefault(player.getUniqueId(), 0L);
                int cooldownSeconds = plugin.getConfig().getInt(Constants.CONF_DONATE_COOLDOWN, 10);
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastUsage < (cooldownSeconds * 1000L)) {
                    long remaining = (cooldownSeconds * 1000L - (currentTime - lastUsage)) / 1000L;
                    Map<String, String> p = new HashMap<>();
                    p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                    p.put(Constants.TIME, String.valueOf(remaining + 1));
                    player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("cooldown-error", "{PREFIX} <white>Sorry, you're still in <yellow>{TIME}s <white>cooldown"), p));
                    return true;
                }
            }
            // Cooldown will be set when dialog is confirmed, not when opened
            plugin.getJavaDialogHandler().openDonationForm(player);
            return true;
        }

        // Help: accept any trailing args so "/donate help foo bar" still shows help
        if (args[0].equalsIgnoreCase("help")) {
            if (!player.hasPermission(Constants.PERM_DONATE_HELP)) {
                MessageUtils.sendLangMessage(player, plugin, "no-permission", null);
                return true;
            }
            Map<String, String> p = new HashMap<>();
            p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
            p.put(Constants.COMMAND, label);
            MessageUtils.sendLangMessageList(player, plugin, "donation-help", p);
            return true;
        }

        // Top / leaderboard — viewable by regular players, served from the in-memory cache
        if (args[0].equalsIgnoreCase("top")) {
            if (!player.hasPermission(Constants.PERM_DONATE_TOP)) {
                MessageUtils.sendLangMessage(player, plugin, "no-permission", null);
                return true;
            }
            int page = 1;
            if (args.length >= 2) {
                try {
                    page = Integer.parseInt(args[1]);
                    if (page < 1) page = 1;
                } catch (NumberFormatException ignored) {}
            }
            plugin.getStatsManager().displayLeaderboard(player, page, "/" + label + " " + args[0].toLowerCase());
            return true;
        }

        // Milestone — viewable by regular players, served from the in-memory cache
        if (args[0].equalsIgnoreCase("milestone")) {
            if (!player.hasPermission(Constants.PERM_DONATE_MILESTONE)) {
                MessageUtils.sendLangMessage(player, plugin, "no-permission", null);
                return true;
            }
            plugin.getStatsManager().displayMilestone(player);
            return true;
        }

        // History — a player's own donation records (COMPLETED + PENDING, sandbox excluded).
        // "/donate history [page]" shows your own; "/donate history <player> [page]" needs the
        // .others permission. A numeric first arg is read as a page (own history), otherwise a name.
        if (args[0].equalsIgnoreCase("history")) {
            if (!player.hasPermission(Constants.PERM_DONATE_HISTORY)) {
                MessageUtils.sendLangMessage(player, plugin, "no-permission", null);
                return true;
            }

            String targetName = player.getName();
            int page = 1;

            if (args.length >= 2) {
                if (isNumericAmount(args[1])) {
                    page = parsePage(args[1]);
                } else {
                    if (!player.hasPermission(Constants.PERM_DONATE_HISTORY_OTHERS)) {
                        MessageUtils.sendLangMessage(player, plugin, "no-permission", null);
                        return true;
                    }
                    targetName = args[1];
                    if (args.length >= 3) page = parsePage(args[2]);
                }
            }

            boolean viewingOther = !targetName.equalsIgnoreCase(player.getName());
            String targetUuid = viewingOther ? null : player.getUniqueId().toString();
            displayHistory(player, targetUuid, targetName, page, viewingOther, label);
            return true;
        }

        // Everything below this point is the donation request flow (MD5 confirmation + new request)
        if (!player.hasPermission(Constants.PERM_DONATE_REQUEST)) {
            MessageUtils.sendLangMessage(player, plugin, "no-permission", null);
            return true;
        }

        // Handle Confirmation via MD5
        if (args.length == 1 && DonationValidator.MD5_PATTERN.matcher(args[0]).matches()) {
            String hash = args[0].toLowerCase();
            DonationRequest request = pendingRequests.get(hash);

            if (request != null && request.playerUuid().equals(player.getUniqueId())) {
                pendingRequests.remove(hash);
                // Set cooldown here — confirmation was accepted (dialog "Yes" or chat confirm)
                cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
                processDonation(player, request.amount(), request.email(), request.method(), request.message(), plugin);
                return true;
            } else {
                // Return invalid usage as requested to keep regular players unaware
                Map<String, String> p = new HashMap<>();
                p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                p.put(Constants.COMMAND, label);
                player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("invalid-usage", "{PREFIX} <red>Invalid usage. <reset>try to run <yellow>/donate help<reset> for help"), p));
                return true;
            }
        }

        // Cooldown Check
        if (!player.hasPermission(Constants.PERM_DONATE_BYPASS_COOLDOWN)) {
            long lastUsage = cooldowns.getOrDefault(player.getUniqueId(), 0L);
            int cooldownSeconds = plugin.getConfig().getInt(Constants.CONF_DONATE_COOLDOWN, 10);
            long currentTime = System.currentTimeMillis();

            if (currentTime - lastUsage < (cooldownSeconds * 1000L)) {
                long remaining = (cooldownSeconds * 1000L - (currentTime - lastUsage)) / 1000L;
                Map<String, String> p = new HashMap<>();
                p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                p.put(Constants.TIME, String.valueOf(remaining + 1));
                player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("cooldown-error", "{PREFIX} <white>Sorry, you're still in <yellow>{TIME}s <white>cooldown"), p));
                return true;
            }
        }

        if (args.length < 3) {
            Map<String, String> p = new HashMap<>();
            p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
            p.put(Constants.COMMAND, label);
            player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("invalid-usage", "{PREFIX} <red>Invalid usage. <reset>try to run <yellow>/donate help<reset> for help"), p));
            return true;
        }

        // 1. Amount Validation
        Double parsedAmount = DonationValidator.parseAmount(args[0], player, plugin);
        if (parsedAmount == null) return true;
        double amount = parsedAmount;

        double minConfig = plugin.getConfig().getDouble(Constants.CONF_DONATE_MIN_AMOUNT, 1000);
        double maxConfig = plugin.getConfig().getDouble(Constants.CONF_DONATE_MAX_AMOUNT, 10000000);

        if (amount < minConfig) {
            Map<String, String> p = new HashMap<>();
            p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
            p.put(Constants.AMOUNT, String.valueOf((long) minConfig));
            p.put(Constants.AMOUNT_FORMATTED, MessageUtils.formatAmount(plugin, minConfig));
            player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("min-donation-error", "{PREFIX} <white>Sorry, <red>minimum <white>amount of donation is <yellow>{AMOUNT_FORMATTED}"), p));
            return true;
        }

        if (amount > maxConfig) {
            Map<String, String> p = new HashMap<>();
            p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
            p.put(Constants.AMOUNT, String.valueOf((long) maxConfig));
            p.put(Constants.AMOUNT_FORMATTED, MessageUtils.formatAmount(plugin, maxConfig));
            player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("max-donation-error", "{PREFIX} <white>Sorry, <red>maximum <white>amount of donation is <yellow>{AMOUNT_FORMATTED}"), p));
            return true;
        }

        // 2. Email Validation
        String email = args[1];
        if (!DonationValidator.validateEmail(email, player, plugin)) return true;

        // 3. Payment Method Validation
        String method = args[2].toLowerCase();
        if (!DonationValidator.validateMethod(method, amount, player, plugin)) return true;

        // 4. Message Validation
        String messageStr = DonationValidator.buildMessage(args, 3);
        if (!DonationValidator.validateMessageLength(messageStr, player, plugin)) return true;

        // 5. Confirmation System
        boolean requireConfirmation = plugin.getConfig().getBoolean(Constants.CONF_DONATE_CONFIRMATION, true);

        if (!requireConfirmation) {
            // No confirmation required — set cooldown immediately and process
            cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
            processDonation(player, amount, email, method, messageStr, plugin);
        } else {
            if (plugin.getBedrockFormHandler() != null && plugin.getBedrockFormHandler().isBedrockPlayer(player)) {
                // Cooldown set when Bedrock confirmation form is accepted
                plugin.getBedrockFormHandler().sendConfirmationForm(player, amount, email, method, messageStr, false, false);
                return true;
            }

            // Generate MD5 Hash for this specific request
            long timestamp = System.currentTimeMillis();
            String rawString = player.getUniqueId().toString() + "-" + timestamp + "-" + amount + "-" + email + "-" + method;
            String hash = HashUtils.md5(rawString);

            if (hash == null) {
                MessageUtils.sendLangMessage(player, plugin, "general-error", null);
                return true;
            }

            // Clean up previous requests from this player to avoid clutter
            pendingRequests.entrySet().removeIf(entry -> entry.getValue().playerUuid().equals(player.getUniqueId()));

            pendingRequests.put(hash, new DonationRequest(player.getUniqueId(), amount, email, method, messageStr));

            Map<String, String> p = MessageUtils.getDonationPlaceholders(plugin, amount, player.getName(), email, method, messageStr);
            p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
            p.put(Constants.COMMAND, "/" + label + " " + hash);

            // If Java dialog is supported for this specific client, show dialog; otherwise chat fallback
            // Cooldown set when dialog/chat confirmation is accepted
            if (plugin.getJavaDialogHandler() != null && ProtocolVersionUtil.supportsDialogs(player)) {
                MessageUtils.playConfigSounds(player, plugin, "sound-effects.donation-confirmation");
                plugin.getJavaDialogHandler().openConfirmationDialog(player, hash, amount, email, method, messageStr);
            } else {
                MessageUtils.sendLangMessageList(player, plugin, "donation-confirmation-java", p);
                MessageUtils.playConfigSounds(player, plugin, "sound-effects.donation-confirmation");
            }
        }

        return true;
    }

    public static void processDonation(Player player, double amount, String email, String method, String message, PlsDonate plugin) {
        // Sound: donation-processed
        MessageUtils.playConfigSounds(player, plugin, "sound-effects.donation-processed");

        plugin.getDonationPlatform().createDonation(player.getName(), email, amount, method, message).thenAccept(response -> {
            if (response.success()) {
                // Log request to ledger to prevent replay
                if (response.transactionId() != null) {
                    plugin.getTransactionRepository().createDonationRequest(response.transactionId(), amount, player.getName(), player.getUniqueId().toString(), false);

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

    public void clearPendingRequests(UUID uuid) {
        pendingRequests.entrySet().removeIf(entry -> entry.getValue().playerUuid().equals(uuid));
        cooldowns.remove(uuid);
    }

    private static final int HISTORY_PAGE_SIZE = 10;

    private int parsePage(String s) {
        try {
            return Math.max(1, Integer.parseInt(s));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Fetches the page off the main thread (no cache exists for history) then renders on the main thread. */
    private void displayHistory(Player viewer, @Nullable String targetUuid, String targetName, int page, boolean viewingOther, String label) {
        int offset = (page - 1) * HISTORY_PAGE_SIZE;
        TransactionRepository repo = plugin.getTransactionRepository();

        CompletableFuture.runAsync(() -> {
            List<TransactionRepository.TransactionRecord> records = repo.getPlayerHistory(targetName, targetUuid, HISTORY_PAGE_SIZE, offset);
            int totalCount = repo.getPlayerHistoryCount(targetName, targetUuid);
            double total = repo.getPlayerTotal(targetUuid, targetName);
            int rank = repo.getPlayerRank(targetUuid, targetName);

            Bukkit.getScheduler().runTask(plugin, () ->
                    renderHistory(viewer, targetName, page, viewingOther, label, records, totalCount, total, rank));
        });
    }

    private void renderHistory(Player viewer, String targetName, int page, boolean viewingOther, String label,
                               List<TransactionRepository.TransactionRecord> records, int totalCount, double total, int rank) {
        if (!viewer.isOnline()) return;

        Map<String, String> p = new HashMap<>();
        p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
        p.put("{NAME}", targetName);

        if (records.isEmpty()) {
            MessageUtils.sendLangMessage(viewer, plugin, "history-empty", p);
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) totalCount / HISTORY_PAGE_SIZE));
        p.put("{PAGE}", String.valueOf(page));
        p.put("{TOTAL_PAGES}", String.valueOf(totalPages));
        // Total and rank reflect COMPLETED donations only; the list also shows PENDING, which the
        // per-row status colour distinguishes (so an unpaid link doesn't inflate the headline total).
        p.put("{TOTAL_FORMATTED}", MessageUtils.formatAmount(plugin, total));
        p.put(Constants.RANK, rank > 0 ? "#" + rank : plugin.getLangConfig().getString("value-unranked", "Unranked"));

        String headerKey = viewingOther ? "history-header-other" : "history-header";
        viewer.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString(headerKey, "<gray>------ <aqua>History (Page {PAGE}/{TOTAL_PAGES}) <gray>------"), p));
        viewer.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("history-summary", "  <white>Total: <green>Rp{TOTAL_FORMATTED} <gray>| <white>Rank: <yellow>{RANK}"), p));

        String format = plugin.getLangConfig().getString("history-format", "<gray>{DATE} <gray>- <green>Rp{AMOUNT_FORMATTED} <gray>- {STATUS_COLORED}");
        for (TransactionRepository.TransactionRecord r : records) {
            Map<String, String> rp = new HashMap<>(p);
            rp.put("{DATE}", formatDate(r.timestamp()));
            rp.put(Constants.AMOUNT_FORMATTED, MessageUtils.formatAmount(plugin, r.amount()));
            rp.put("{STATUS_COLORED}", MessageUtils.formatStatus(plugin.getLangConfig(), r.status()));
            viewer.sendMessage(MessageUtils.parseMessage(format, rp));
        }

        String footer = plugin.getLangConfig().getString("history-footer", "<gray>----------------------------");
        if (page < totalPages) {
            String base = "/" + label + " history" + (viewingOther ? " " + targetName : "");
            String hover = plugin.getLangConfig().getString("history-next-hover", "<gray>Click to view page {NEXT_PAGE}")
                    .replace("{NEXT_PAGE}", String.valueOf(page + 1));
            String nextBtn = " <yellow><click:run_command:\"" + base + " " + (page + 1) + "\"><hover:show_text:\"" + hover + "\">[Next Page »]</hover></click>";
            viewer.sendMessage(MessageUtils.parseMessage(footer + nextBtn, p));
        } else {
            viewer.sendMessage(MessageUtils.parseMessage(footer, p));
        }
    }

    private String formatDate(long timestamp) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(timestamp * 1000L));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String sub = args[0].toLowerCase();
            if ("help".startsWith(sub)) completions.add("help");
            if ("top".startsWith(sub)) completions.add("top");
            if ("milestone".startsWith(sub)) completions.add("milestone");
            if ("history".startsWith(sub)) completions.add("history");

            double min = plugin.getConfig().getDouble(Constants.CONF_DONATE_MIN_AMOUNT, 1000);
            long[] suggestions = { (long) min, (long) min + 5000, (long) min + 10000 };
            for (long s : suggestions) {
                if (String.valueOf(s).startsWith(sub)) completions.add(String.valueOf(s));
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("top")) {
            String sub = args[1].toLowerCase();
            for (String pg : List.of("1", "2", "3")) {
                if (pg.startsWith(sub)) completions.add(pg);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("history")) {
            String sub = args[1].toLowerCase();
            // Page numbers for own history; online player names only for admins with the .others perm.
            for (String pg : List.of("1", "2", "3")) {
                if (pg.startsWith(sub)) completions.add(pg);
            }
            if (sender.hasPermission(Constants.PERM_DONATE_HISTORY_OTHERS)) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.getName().toLowerCase().startsWith(sub)) completions.add(online.getName());
                }
            }
        } else if (isNumericAmount(args[0])) {
            // Only suggest email/method/message once arg-1 is an actual amount, so subcommands
            // like "help"/"top"/"milestone" don't spill into donation-argument completions.
            if (args.length == 2) {
                String sub = args[1].toLowerCase();
                List<String> args2 = plugin.getLangConfig().getStringList("donation-tab-completions-args2");
                if (args2.isEmpty()) args2 = List.of("your-valid@email.com");
                for (String s : args2) {
                    if (s.toLowerCase().startsWith(sub)) completions.add(s);
                }
            } else if (args.length == 3) {
                String sub = args[2].toLowerCase();
                List<String> methods = List.of("qris", "gopay", "paypal");
                for (String m : methods) {
                    if (m.startsWith(sub)) completions.add(m);
                }
            } else if (args.length == 4) {
                String sub = args[3].toLowerCase();
                List<String> args4 = plugin.getLangConfig().getStringList("donation-tab-completions-args3");
                if (args4.isEmpty()) args4 = List.of("[messages]");
                for (String s : args4) {
                    if (s.toLowerCase().startsWith(sub)) completions.add(s);
                }
            }
        }

        return completions;
    }

    private boolean isNumericAmount(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            return Double.isFinite(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Sets the cooldown for a player. Called by confirmation handlers
     * (BedrockFormHandler, JavaDialogHandler) when a donation is confirmed.
     */
    public void setCooldown(UUID playerUuid) {
        cooldowns.put(playerUuid, System.currentTimeMillis());
    }
}
