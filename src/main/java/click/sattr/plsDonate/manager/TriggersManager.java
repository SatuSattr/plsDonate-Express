package click.sattr.plsDonate.manager;

import click.sattr.plsDonate.PlsDonate;
import click.sattr.plsDonate.util.ExpressionEvaluator;
import click.sattr.plsDonate.util.PluginLogger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TriggersManager implements Listener {

    private final PlsDonate plugin;
    private FileConfiguration triggersConfig;
    private final Pattern mathPattern = Pattern.compile("\\{math:(.*?)\\}");

    public TriggersManager(PlsDonate plugin) {
        this.plugin = plugin;
        loadConfig();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void loadConfig() {
        File triggersFile = new File(plugin.getDataFolder(), "triggers.yml");
        if (!triggersFile.exists()) {
            plugin.saveResource("triggers.yml", false);
        }
        triggersConfig = YamlConfiguration.loadConfiguration(triggersFile);
    }

    public void processDonation(String donorName, double amount, String formattedAmount, String message, String paymentMethod, String txId) {
        if (!plugin.getConfig().getBoolean("donate.triggers", true)) return;

        // Prepare base placeholders
        if (donorName == null) donorName = "Anonymous";
        if (message == null) message = "";
        if (paymentMethod == null) paymentMethod = "unknown";
        if (txId == null) txId = "local";

        ConfigurationSection triggersSection = triggersConfig.getConfigurationSection("triggers");
        if (triggersSection == null) return;

        for (String triggerKey : triggersSection.getKeys(false)) {
            ConfigurationSection trigger = triggersSection.getConfigurationSection(triggerKey);
            if (trigger == null) continue;

            if (!trigger.getBoolean("enabled", true)) continue;

            List<String> conditions = trigger.getStringList("conditions");
            boolean conditionsMet = true;

            for (String condition : conditions) {
                String processedCondition = formatPlaceholders(condition, donorName, amount, formattedAmount, message, paymentMethod, txId, true);
                if (!ExpressionEvaluator.evaluateCondition(processedCondition)) {
                    conditionsMet = false;
                    break;
                }
            }

            if (conditionsMet) {
                List<String> commands = trigger.getStringList("commands");
                boolean requireOnline = trigger.getBoolean("require_online", false);

                boolean isOnline = Bukkit.getPlayerExact(donorName) != null;

                for (String command : commands) {
                    // Sanitize message/name for command execution
                    String processedCommand = formatPlaceholders(command, donorName, amount, formattedAmount, message, paymentMethod, txId, false);
                    processedCommand = evaluateMathBlocks(processedCommand);

                    if (requireOnline && !isOnline) {
                        plugin.getOfflineTriggerRepository().insertOfflineTrigger(donorName, processedCommand);
                        PluginLogger.info("Saved offline trigger command for " + donorName + " (Waiting for login).");
                    } else {
                        executeCommand(processedCommand);
                    }
                }
            }
        }
    }

    private String formatPlaceholders(String text, String player, double amount, String formattedAmount, String message, String method, String id, boolean forExpression) {
        // Sanitize player name and message to prevent command injection
        String safePlayer = sanitize(player);
        String safeMessage = sanitize(message);
        
        return text.replace("{player}", safePlayer)
                   .replace("{amount}", String.valueOf(amount))
                   .replace("{amount_formatted}", formattedAmount)
                   .replace("{message}", safeMessage)
                   .replace("{method}", method)
                   .replace("{id}", id);
    }

    private String sanitize(String input) {
        if (input == null) return "";
        // Escape double quotes and backslashes to prevent JSON/Command breaking
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String evaluateMathBlocks(String command) {
        Matcher matcher = mathPattern.matcher(command);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String mathExpr = matcher.group(1);
            try {
                double mathResult = ExpressionEvaluator.evaluateMath(mathExpr);
                // If it is a whole number (e.g. 15.0), format it as an integer ("15")
                String resultStr;
                if (mathResult == (long) mathResult) {
                    resultStr = String.format("%d", (long) mathResult);
                } else {
                    resultStr = String.format("%s", mathResult);
                }
                matcher.appendReplacement(result, resultStr);
            } catch (Exception e) {
                PluginLogger.warn("Failed to evaluate math {" + mathExpr + "} in command: " + command);
                matcher.appendReplacement(result, "0"); // Default fallback
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private void executeCommand(String command) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), command);
            PluginLogger.info("Executed trigger command: " + command);
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("donate.triggers", true)) return;

        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> offlineCommands = plugin.getOfflineTriggerRepository().getAndRemoveOfflineTriggers(player.getName());
            if (!offlineCommands.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // Check if player is still online before executing offline rewards
                    if (player.isOnline()) {
                        for (String command : offlineCommands) {
                            executeCommand(command);
                        }
                    } else {
                        // Put them back if they left instantly (Extreme edge case)
                        for (String command : offlineCommands) {
                            plugin.getOfflineTriggerRepository().insertOfflineTrigger(player.getName(), command);
                        }
                    }
                });
            }
        });
    }
}
