package click.sattr.plsDonate.command;

import click.sattr.plsDonate.PlsDonate;
import click.sattr.plsDonate.database.repository.TransactionRepository;
import click.sattr.plsDonate.util.Constants;
import click.sattr.plsDonate.util.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class plsDonateCommand implements CommandExecutor, TabCompleter {

    private final PlsDonate plugin;
    private final Map<String, DonationSimulationRequest> pendingRequests = new ConcurrentHashMap<>();
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$");
    private static final Pattern MD5_PATTERN = Pattern.compile("^[a-fA-F0-9]{32}$");

    public plsDonateCommand(PlsDonate plugin) {
        this.plugin = plugin;
    }

    public record DonationSimulationRequest(UUID playerUuid, double amount, String email, String method, String message, boolean isSandbox) {}

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
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
            sender.sendMessage(MessageUtils.parseMessage("    <yellow>/pdn leaderboard <gray>- Show top donators", p));
            sender.sendMessage(MessageUtils.parseMessage("    <yellow>/pdn milestone <gray>- Show donation goal", p));
            if (sender.hasPermission(Constants.PERM_ADMIN)) {
                sender.sendMessage(MessageUtils.parseMessage("    <yellow>/pdn transaction <gray>- Manage transactions (CRUD)", p));
                sender.sendMessage(MessageUtils.parseMessage("    <yellow>/pdn fakedonate <amount> <email> <method> [msg] <gray>- Simulate a sandbox donation (Hidden from stats)", p));
                sender.sendMessage(MessageUtils.parseMessage("    <yellow>/pdn pushdonate <amount> <email> <method> [msg] <gray>- Simulate a real donation (Included in stats)", p));
                sender.sendMessage(MessageUtils.parseMessage("    <yellow>/pdn reload <gray>- Reload configuration", p));
            }
            sender.sendMessage(MessageUtils.parseMessage("<newline><gray>----------------------------", p));
            return true;
        }

        if (args[0].equalsIgnoreCase("leaderboard")) {
            int page = 1;
            if (args.length >= 2) {
                try {
                    page = Integer.parseInt(args[1]);
                    if (page < 1) page = 1;
                } catch (NumberFormatException ignored) {}
            }
            displayLeaderboard(sender, page);
            return true;
        }

        if (args[0].equalsIgnoreCase("milestone")) {
            displayMilestone(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("transaction") && sender.hasPermission(Constants.PERM_ADMIN)) {
            if (args.length == 1) {
                sender.sendMessage(MessageUtils.parseMessage("<gray>------ <gold>Transaction Management <gray>------", null));
                sender.sendMessage(MessageUtils.parseMessage("  <yellow>/pdn transaction list [page]", null));
                sender.sendMessage(MessageUtils.parseMessage("  <yellow>/pdn transaction info <id>", null));
                sender.sendMessage(MessageUtils.parseMessage("  <yellow>/pdn transaction add <player> <amount> [method]", null));
                sender.sendMessage(MessageUtils.parseMessage("  <yellow>/pdn transaction delete <id>", null));
                sender.sendMessage(MessageUtils.parseMessage("  <yellow>/pdn transaction setstatus <id> <status>", null));
                sender.sendMessage(MessageUtils.parseMessage("  <yellow>/pdn transaction clear <player|all>", null));
                sender.sendMessage(MessageUtils.parseMessage("<gray>----------------------------", null));
                return true;
            }

            String sub = args[1].toLowerCase();
            switch (sub) {
                case "list":
                    int page = 1;
                    if (args.length >= 3) {
                        try { page = Integer.parseInt(args[2]); if (page < 1) page = 1; } catch (NumberFormatException ignored) {}
                    }
                    displayTransactionList(sender, page);
                    break;
                case "info":
                    if (args.length < 3) { sender.sendMessage(MessageUtils.parseMessage("<red>Usage: /pdn transaction info <id>", null)); return true; }
                    try { displayTransactionInfo(sender, Integer.parseInt(args[2])); } catch (NumberFormatException e) { sender.sendMessage(MessageUtils.parseMessage("<red>Invalid ID.", null)); }
                    break;
                case "add":
                    if (args.length < 4) { sender.sendMessage(MessageUtils.parseMessage("<red>Usage: /pdn transaction add <player> <amount> [method]", null)); return true; }
                    String pName = args[2];
                    double amt;
                    try { amt = Double.parseDouble(args[3]); } catch (NumberFormatException e) { sender.sendMessage(MessageUtils.parseMessage("<red>Invalid amount.", null)); return true; }
                    String mtd = args.length >= 5 ? args[4] : "MANUAL";
                    String manualTxId = "MANUAL-" + System.currentTimeMillis();
                    plugin.getDonationService().fulfillDonation(pName, amt, "manual@internal", mtd, "Manual entry by admin", manualTxId, false);
                    sender.sendMessage(MessageUtils.parseMessage("<green>Transaction added successfully!", null));
                    break;
                case "delete":
                    if (args.length < 3) { sender.sendMessage(MessageUtils.parseMessage("<red>Usage: /pdn transaction delete <id>", null)); return true; }
                    try {
                        int id = Integer.parseInt(args[2]);
                        if (plugin.getTransactionRepository().deleteTransaction(id)) sender.sendMessage(MessageUtils.parseMessage("<green>Transaction #" + id + " deleted.", null));
                        else sender.sendMessage(MessageUtils.parseMessage("<red>Transaction not found.", null));
                    } catch (NumberFormatException e) { sender.sendMessage(MessageUtils.parseMessage("<red>Invalid ID.", null)); }
                    break;
                case "setstatus":
                    if (args.length < 4) { sender.sendMessage(MessageUtils.parseMessage("<red>Usage: /pdn transaction setstatus <id> <status>", null)); return true; }
                    try {
                        int id = Integer.parseInt(args[2]);
                        String status = args[3].toUpperCase();
                        if (plugin.getTransactionRepository().updateTransactionStatus(id, status)) sender.sendMessage(MessageUtils.parseMessage("<green>Status of #" + id + " updated to " + status + ".", null));
                        else sender.sendMessage(MessageUtils.parseMessage("<red>Transaction not found.", null));
                    } catch (NumberFormatException e) { sender.sendMessage(MessageUtils.parseMessage("<red>Invalid ID.", null)); }
                    break;
                case "clear":
                    if (args.length < 3) { sender.sendMessage(MessageUtils.parseMessage("<red>Usage: /pdn transaction clear <player|all>", null)); return true; }
                    String target = args[2];
                    plugin.getTransactionRepository().clearTransactions(target);
                    sender.sendMessage(MessageUtils.parseMessage("<green>Cleared transactions for: " + target, null));
                    break;
                default:
                    sender.sendMessage(MessageUtils.parseMessage("<red>Unknown sub-command.", null));
                    break;
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("fakedonate") || args[0].equalsIgnoreCase("pushdonate")) {
            String sub = args[0].toLowerCase();
            boolean isSandbox = sub.equals("fakedonate");

            if (!sender.hasPermission(Constants.PERM_ADMIN)) {
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
            if (args.length == 2 && MD5_PATTERN.matcher(args[1]).matches()) {
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
                p.put(Constants.COMMAND, label + " " + sub + " <amount> <email> <method> [msg]");
                player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("invalid-usage", "{PREFIX} <red>Invalid usage. <reset>try to run <yellow>/donate help<reset> for help"), p));
                return true;
            }

            double amount;
            try {
                amount = Double.parseDouble(args[1]);
            } catch (NumberFormatException e) {
                Map<String, String> p = new HashMap<>();
                p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("general-error", "{PREFIX} <red>Something wrong with the donation system! please contact admin"), p));
                return true;
            }

            String email = args[2];
            if (!EMAIL_PATTERN.matcher(email).matches()) {
                Map<String, String> p = new HashMap<>();
                p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("invalid-email", "{PREFIX} <white>Please <red>provide <white>a valid email <gray>example: (your@gmail.com)"), p));
                return true;
            }

            String method = args[3].toLowerCase();
            if (!method.equals("qris") && !method.equals("gopay") && !method.equals("paypal")) {
                Map<String, String> p = new HashMap<>();
                p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                player.sendMessage(MessageUtils.parseMessage("{PREFIX} <red>Invalid payment method! <yellow>Options: qris, gopay, paypal", p));
                return true;
            }

            String message = "";
            if (args.length > 4) {
                StringBuilder sb = new StringBuilder();
                for (int i = 4; i < args.length; i++) {
                    sb.append(args[i]).append(" ");
                }
                message = sb.toString().trim();
            }

            if (plugin.getConfig().getBoolean(Constants.CONF_DONATE_CONFIRMATION, true)) {
                if (plugin.getBedrockFormHandler() != null && plugin.getConfig().getBoolean(Constants.CONF_BEDROCK_SUPPORT, false) && plugin.getBedrockFormHandler().isBedrockPlayer(player)) {
                    plugin.getBedrockFormHandler().sendConfirmationForm(player, amount, email, method, message, true, isSandbox);
                    return true;
                }

                // Generate MD5 Hash
                long timestamp = System.currentTimeMillis();
                String rawString = player.getUniqueId().toString() + "-" + timestamp + "-" + amount + "-" + email + "-" + method + "-" + isSandbox;
                String hash = md5(rawString);

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

                MessageUtils.sendLangMessageList(player, plugin, "donation-confirmation-java", p);
                MessageUtils.playConfigSounds(player, plugin, "sound-effects.donation-confirmation");
            } else {
                executeSimulatedDonation(player, amount, email, method, message, isSandbox);
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(Constants.PERM_ADMIN)) {
                MessageUtils.sendLangMessage(sender, plugin, "no-permission", null);
                return true;
            }
            plugin.reloadPlugin();
            MessageUtils.sendLangMessage(sender, plugin, "reload-success", null);
            return true;
        }

        Map<String, String> p = new HashMap<>();
        p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
        p.put(Constants.COMMAND, label);
        MessageUtils.sendLangMessage(sender, plugin, "invalid-usage", p);
        return true;
    }

    private void displayLeaderboard(CommandSender sender, int page) {
        int limit = 11;
        int offset = (page - 1) * limit;
        List<TransactionRepository.LeaderboardEntry> entries = plugin.getTransactionRepository().getLeaderboard(limit, offset);
        int totalCount = plugin.getTransactionRepository().getLeaderboardCount();
        int totalPages = (int) Math.ceil((double) totalCount / limit);

        Map<String, String> p = new HashMap<>();
        p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
        p.put("{PAGE}", String.valueOf(page));
        p.put("{TOTAL_PAGES}", String.valueOf(totalPages));
        
        sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("leaderboard-header", "<gray>------ <gold>Donation Leaderboard (Page {PAGE}/{TOTAL_PAGES}) <gray>------"), p));
        if (entries.isEmpty()) {
            sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("leaderboard-empty", "<gray>No donation records found."), p));
        } else {
            for (int i = 0; i < entries.size(); i++) {
                TransactionRepository.LeaderboardEntry entry = entries.get(i);
                Map<String, String> entryP = new HashMap<>(p);
                entryP.put(Constants.RANK, String.valueOf(offset + i + 1));
                entryP.put("{NAME}", entry.name());
                entryP.put(Constants.AMOUNT_FORMATTED, entry.amountFormatted());
                sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("leaderboard-format", "<yellow>{RANK}. <white>{NAME} <gray>- <green>{AMOUNT_FORMATTED}"), entryP));
            }
        }

        if (page < totalPages) {
            String footer = plugin.getLangConfig().getString("leaderboard-footer", "<gray>----------------------------");
            String nextBtn = " <yellow><click:run_command:\"/pdn leaderboard " + (page + 1) + "\"><hover:show_text:\"<gray>Click to view page " + (page + 1) + "\">[Next Page »]</hover></click>";
            sender.sendMessage(MessageUtils.parseMessage(footer + nextBtn, p));
        } else {
            sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("leaderboard-footer", "<gray>----------------------------"), p));
        }
    }

    private void displayTransactionList(CommandSender sender, int page) {
        int limit = 10;
        int offset = (page - 1) * limit;
        List<TransactionRepository.TransactionRecord> records = plugin.getTransactionRepository().getTransactions(limit, offset);
        int totalCount = plugin.getTransactionRepository().getTransactionsCount();
        int totalPages = (int) Math.ceil((double) totalCount / limit);

        sender.sendMessage(MessageUtils.parseMessage("<gray>------ <gold>Transaction List (Page " + page + "/" + totalPages + ") <gray>------", null));
        if (records.isEmpty()) {
            sender.sendMessage(MessageUtils.parseMessage("<gray>No transactions found.", null));
        } else {
            for (TransactionRepository.TransactionRecord record : records) {
                String color = record.status().equalsIgnoreCase("COMPLETED") ? "<green>" : (record.status().equalsIgnoreCase("PENDING") ? "<yellow>" : "<red>");
                String msg = "<gray>#<white><click:run_command:\"/pdn transaction info " + record.id() + "\"><hover:show_text:\"<gray>Click for details\">" + record.id() + "</hover></click> <gray>| <white>" + record.donorName() + " <gray>| <green>" + MessageUtils.formatAmount(plugin, record.amount()) + " <gray>| " + color + record.status();
                sender.sendMessage(MessageUtils.parseMessage(msg, null));
            }
        }

        if (page < totalPages) {
            String footer = "<gray>----------------------------";
            String nextBtn = " <yellow><click:run_command:\"/pdn transaction list " + (page + 1) + "\"><hover:show_text:\"<gray>Click for next page\">[Next Page »]</hover></click>";
            sender.sendMessage(MessageUtils.parseMessage(footer + nextBtn, null));
        } else {
            sender.sendMessage(MessageUtils.parseMessage("<gray>----------------------------", null));
        }
    }

    private void displayTransactionInfo(CommandSender sender, int id) {
        TransactionRepository.TransactionRecord r = plugin.getTransactionRepository().getTransactionById(id);
        if (r == null) {
            sender.sendMessage(MessageUtils.parseMessage("<red>Transaction not found.", null));
            return;
        }

        sender.sendMessage(MessageUtils.parseMessage("<gray>------ <gold>Transaction Info: #" + r.id() + " <gray>------", null));
        sender.sendMessage(MessageUtils.parseMessage(" <gray>» <white>TX-ID: <yellow>" + r.txId(), null));
        sender.sendMessage(MessageUtils.parseMessage(" <gray>» <white>Donor: <yellow>" + r.donorName(), null));
        sender.sendMessage(MessageUtils.parseMessage(" <gray>» <white>Amount: <green>" + MessageUtils.formatAmount(plugin, r.amount()), null));
        sender.sendMessage(MessageUtils.parseMessage(" <gray>» <white>Status: " + (r.status().equalsIgnoreCase("COMPLETED") ? "<green>" : "<yellow>") + r.status(), null));
        sender.sendMessage(MessageUtils.parseMessage(" <gray>» <white>Type: " + (r.isSandbox() ? "<red>SANDBOX" : "<green>LIVE"), null));
        sender.sendMessage(MessageUtils.parseMessage(" <gray>» <white>Date: <gray>" + formatDate(r.timestamp()), null));
        if (r.completedAt() > 0) sender.sendMessage(MessageUtils.parseMessage(" <gray>» <white>Completed: <gray>" + formatDate(r.completedAt()), null));
        sender.sendMessage(MessageUtils.parseMessage(" <gray>» <white>Checksum: <dark_gray>" + r.checksum(), null));
        sender.sendMessage(MessageUtils.parseMessage("<gray>----------------------------", null));
    }

    private String formatDate(long timestamp) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(timestamp * 1000L));
    }

    private void displayMilestone(CommandSender sender) {
        if (!plugin.getConfig().getBoolean(Constants.CONF_MILESTONE_ENABLED, true)) {
            sender.sendMessage(MessageUtils.parseMessage("<red>Milestone feature is currently disabled in config.yml.", new HashMap<>()));
            return;
        }

        double current = plugin.getTransactionRepository().getTotalDonations() + plugin.getConfig().getDouble(Constants.CONF_MILESTONE_OFFSET, 0);
        double target = plugin.getConfig().getDouble(Constants.CONF_MILESTONE_TARGET, 1000000);
        String title = plugin.getConfig().getString(Constants.CONF_MILESTONE_TITLE, "Goal");

        double percentage = (current / target) * 100;
        if (percentage > 100) percentage = 100;

        Map<String, String> p = new HashMap<>();
        p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
        p.put(Constants.TITLE, title);
        p.put("{CURRENT_FORMATTED}", MessageUtils.formatAmount(plugin, current));
        p.put("{TARGET_FORMATTED}", MessageUtils.formatAmount(plugin, target));
        p.put(Constants.PERCENTAGE, String.format("%.1f", percentage));
        p.put(Constants.BAR, createProgressBar(percentage));
        
        sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("milestone-header", "<gray>------ <gold>Donation Milestone <gray>------"), p));
        sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("milestone-title", "  <white>Target: <yellow>{TITLE}"), p));
        sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("milestone-progress", "  <white>Progress: <green>{CURRENT_FORMATTED} <gray>/ <red>{TARGET_FORMATTED}"), p));
        sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("milestone-percentage", "  <white>Percentage: <aqua>{PERCENT}% {BAR}"), p));
        sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("milestone-footer", "<gray>----------------------------"), p));
    }

    private String createProgressBar(double percentage) {
        int bars = 10;
        int filled = (int) (percentage / 10);
        StringBuilder sb = new StringBuilder("<green>");
        for (int i = 0; i < filled; i++) sb.append("■");
        sb.append("<gray>");
        for (int i = filled; i < bars; i++) sb.append("■");
        return sb.toString();
    }

    private void executeSimulatedDonation(Player player, double amount, String email, String method, String message, boolean isSandbox) {
        String txId = (isSandbox ? "FAKETX-" : "PUSHTX-") + System.currentTimeMillis();

        plugin.getDonationService().fulfillDonation(player.getName(), amount, email, method, message, txId, isSandbox);

        Map<String, String> fP = new HashMap<>();
        fP.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
        String langKey = isSandbox ? "fake-donation-triggered" : "push-donation-triggered";
        String defaultMsg = isSandbox ? "{PREFIX} <green>Fake donation successfully triggered (Sandbox Mode)!</green>" : "{PREFIX} <green>Donation successfully pushed (Live Mode)!</green>";
        player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString(langKey, defaultMsg), fP));
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(input.getBytes());
            BigInteger no = new BigInteger(1, messageDigest);
            String hashtext = no.toString(16);
            while (hashtext.length() < 32) {
                hashtext = "0" + hashtext;
            }
            return hashtext;
        } catch (NoSuchAlgorithmException e) {
            plugin.getLogger().severe("MD5 algorithm not found!");
            return null;
        }
    }

    public void clearPendingRequests(UUID uuid) {
        pendingRequests.entrySet().removeIf(entry -> entry.getValue().playerUuid().equals(uuid));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String sub = args[0].toLowerCase();
            if ("fakedonate".startsWith(sub) && sender.hasPermission(Constants.PERM_ADMIN)) completions.add("fakedonate");
            if ("pushdonate".startsWith(sub) && sender.hasPermission(Constants.PERM_ADMIN)) completions.add("pushdonate");
            if ("reload".startsWith(sub) && sender.hasPermission(Constants.PERM_ADMIN)) completions.add("reload");
            if ("leaderboard".startsWith(sub)) completions.add("leaderboard");
            if ("milestone".startsWith(sub)) completions.add("milestone");
            if ("transaction".startsWith(sub) && sender.hasPermission(Constants.PERM_ADMIN)) completions.add("transaction");
            if ("help".startsWith(sub)) completions.add("help");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("leaderboard")) {
            completions.add("1");
            completions.add("2");
            completions.add("3");
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("transaction") && sender.hasPermission(Constants.PERM_ADMIN)) {
            if (args.length == 2) {
                String sub = args[1].toLowerCase();
                List<String> subs = List.of("list", "info", "add", "delete", "setstatus", "clear");
                for (String s : subs) if (s.startsWith(sub)) completions.add(s);
            } else if (args.length == 3) {
                String sub = args[2].toLowerCase();
                if (args[1].equalsIgnoreCase("setstatus") || args[1].equalsIgnoreCase("delete") || args[1].equalsIgnoreCase("info")) {
                    completions.add("[id]");
                } else if (args[1].equalsIgnoreCase("clear")) {
                    completions.add("all");
                    for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(sub)) completions.add(p.getName());
                } else if (args[1].equalsIgnoreCase("add")) {
                    for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(sub)) completions.add(p.getName());
                }
            } else if (args.length == 4 && args[1].equalsIgnoreCase("setstatus")) {
                List<String> stats = List.of("PENDING", "COMPLETED", "VOID");
                for (String s : stats) if (s.toLowerCase().startsWith(args[3].toLowerCase())) completions.add(s);
            }
        } else if (args.length > 1 && (args[0].equalsIgnoreCase("fakedonate") || args[0].equalsIgnoreCase("pushdonate")) && sender.hasPermission(Constants.PERM_ADMIN)) {
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
