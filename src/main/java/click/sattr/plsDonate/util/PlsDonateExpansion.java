package click.sattr.plsDonate.util;

import click.sattr.plsDonate.PlsDonate;
import click.sattr.plsDonate.database.repository.TransactionRepository;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class PlsDonateExpansion extends PlaceholderExpansion {

    private final PlsDonate plugin;

    public PlsDonateExpansion(PlsDonate plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "plsdonate";
    }

    @Override
    public @NotNull String getAuthor() {
        return plugin.getPluginMeta().getAuthors().get(0);
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params.isEmpty()) return null;

        // 1. Milestone / Goal Placeholders
        if (params.startsWith("milestone_")) {
            double current = plugin.getTransactionRepository().getTotalDonations() + plugin.getConfig().getDouble(Constants.CONF_MILESTONE_OFFSET, 0);
            double target = plugin.getConfig().getDouble(Constants.CONF_MILESTONE_TARGET, 1000000);
            
            switch (params) {
                case "milestone_current":
                    return String.valueOf((long) current);
                case "milestone_current_formatted":
                    return MessageUtils.formatIndonesianNumber(current);
                case "milestone_target":
                    return MessageUtils.formatIndonesianNumber(target);
                case "milestone_percent":
                    double percent = (current / target) * 100;
                    return String.format("%.1f", Math.min(percent, 100.0));
                case "milestone_title":
                    return MessageUtils.toLegacy(plugin.getConfig().getString(Constants.CONF_MILESTONE_TITLE, "Goal"));
                case "milestone_bar":
                    double pBar = (current / target) * 100;
                    return MessageUtils.toLegacy(createProgressBar(Math.min(pBar, 100.0)));
                case "milestone_remaining":
                    double remaining = target - current;
                    return MessageUtils.formatIndonesianNumber(Math.max(remaining, 0));
            }
        }

        // 2. Player Stats (Personal)
        if (player != null && params.startsWith("player_")) {
            switch (params) {
                case "player_total":
                    double total = plugin.getTransactionRepository().getPlayerTotal(player.getName());
                    return MessageUtils.formatIndonesianNumber(total);
                case "player_rank":
                    int rank = plugin.getTransactionRepository().getPlayerRank(player.getName());
                    return rank > 0 ? "#" + rank : "N/A";
            }
        }

        // 3. Leaderboard (Global)
        if (params.startsWith("top_")) {
            String[] parts = params.split("_");
            if (parts.length < 3) return null;
            
            try {
                int rank = Integer.parseInt(parts[parts.length - 1]);
                List<TransactionRepository.LeaderboardEntry> leaderboard = plugin.getTransactionRepository().getLeaderboard(rank);
                
                if (leaderboard.size() < rank) return "N/A";
                TransactionRepository.LeaderboardEntry entry = leaderboard.get(rank - 1);

                if (params.startsWith("top_name_")) return entry.name();
                if (params.startsWith("top_amount_")) return String.valueOf((long) entry.amount());
                if (params.startsWith("top_amount_formatted_")) return entry.amountFormatted();
                
            } catch (NumberFormatException e) {
                return null;
            }
        }

        // 4. Recent Donation
        if (params.startsWith("recent_")) {
            TransactionRepository.LeaderboardEntry recent = plugin.getTransactionRepository().getRecentDonation();
            if (recent == null) return "None";

            switch (params) {
                case "recent_name":
                    return recent.name();
                case "recent_amount":
                    return String.valueOf((long) recent.amount());
                case "recent_amount_formatted":
                    return recent.amountFormatted();
            }
        }

        return null;
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
