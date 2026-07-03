package click.sattr.plsDonate.manager;

import click.sattr.plsDonate.PlsDonate;
import click.sattr.plsDonate.database.repository.TransactionRepository.LeaderboardEntry;
import click.sattr.plsDonate.util.Constants;
import click.sattr.plsDonate.util.MessageUtils;
import click.sattr.plsDonate.util.PluginLogger;
import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Keeps the leaderboard top entries and the milestone total in memory so that
 * /donate top, /pdn leaderboard and /pdn milestone don't hit the database on
 * every invocation. Only the top {@link #CACHE_SIZE} donors are cached, so the
 * footprint stays bounded even on servers with thousands of donors. The cache is
 * refreshed whenever a donation is fulfilled, an admin edits the ledger, or the
 * plugin reloads.
 */
public class StatsManager {

    private static final int CACHE_SIZE = 50;
    private static final int PAGE_SIZE = 10;

    private final PlsDonate plugin;

    private volatile List<LeaderboardEntry> cachedTop = Collections.emptyList();
    private volatile int cachedDonorCount = 0;
    private volatile double cachedTotal = 0;

    public StatsManager(PlsDonate plugin) {
        this.plugin = plugin;
    }

    /** Blocking refresh, used once at startup so the cache is never empty when players can run commands. */
    public void refreshSync() {
        loadFromDatabase();
    }

    /** Async refresh, used after donations, admin ledger edits, and reloads. */
    public CompletableFuture<Void> refresh() {
        return CompletableFuture.runAsync(this::loadFromDatabase);
    }

    private void loadFromDatabase() {
        try {
            List<LeaderboardEntry> top = plugin.getTransactionRepository().getLeaderboard(CACHE_SIZE, 0);
            int count = plugin.getTransactionRepository().getLeaderboardCount();
            double total = plugin.getTransactionRepository().getTotalDonations();
            this.cachedTop = top;
            this.cachedDonorCount = count;
            this.cachedTotal = total;
        } catch (Exception e) {
            PluginLogger.warn("Failed to refresh stats cache: " + e.getMessage());
        }
    }

    public void displayLeaderboard(CommandSender sender, int page, String nextPageBaseCommand) {
        if (page < 1) page = 1;
        int offset = (page - 1) * PAGE_SIZE;

        List<LeaderboardEntry> cache = this.cachedTop;
        int totalCount = this.cachedDonorCount;

        List<LeaderboardEntry> entries;
        // The common pages fit inside the cached window; deep pages are rare so they hit the DB.
        if (offset + PAGE_SIZE <= CACHE_SIZE) {
            int from = Math.min(offset, cache.size());
            int to = Math.min(offset + PAGE_SIZE, cache.size());
            entries = cache.subList(from, to);
        } else {
            entries = plugin.getTransactionRepository().getLeaderboard(PAGE_SIZE, offset);
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) totalCount / PAGE_SIZE));

        Map<String, String> p = new HashMap<>();
        p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
        p.put("{PAGE}", String.valueOf(page));
        p.put("{TOTAL_PAGES}", String.valueOf(totalPages));

        sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("leaderboard-header", "<gray>------ <gold>Donation Leaderboard (Page {PAGE}/{TOTAL_PAGES}) <gray>------"), p));
        if (entries.isEmpty()) {
            sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("leaderboard-empty", "<gray>No donation records found."), p));
        } else {
            for (int i = 0; i < entries.size(); i++) {
                LeaderboardEntry entry = entries.get(i);
                Map<String, String> entryP = new HashMap<>(p);
                entryP.put(Constants.RANK, String.valueOf(offset + i + 1));
                entryP.put("{NAME}", entry.name());
                entryP.put(Constants.AMOUNT_FORMATTED, entry.amountFormatted());
                sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("leaderboard-format", "<yellow>{RANK}. <white>{NAME} <gray>- <green>{AMOUNT_FORMATTED}"), entryP));
            }
        }

        if (page < totalPages) {
            String footer = plugin.getLangConfig().getString("leaderboard-footer", "<gray>----------------------------");
            String nextBtn = " <yellow><click:run_command:\"" + nextPageBaseCommand + " " + (page + 1) + "\"><hover:show_text:\"<gray>Click to view page " + (page + 1) + "\">[Next Page »]</hover></click>";
            sender.sendMessage(MessageUtils.parseMessage(footer + nextBtn, p));
        } else {
            sender.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("leaderboard-footer", "<gray>----------------------------"), p));
        }
    }

    public void displayMilestone(CommandSender sender) {
        if (!plugin.getConfig().getBoolean(Constants.CONF_MILESTONE_ENABLED, true)) {
            MessageUtils.sendLangMessage(sender, plugin, "milestone-disabled", null);
            return;
        }

        double current = this.cachedTotal + plugin.getConfig().getDouble(Constants.CONF_MILESTONE_OFFSET, 0);
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
}
