package click.sattr.plsDonate;

import org.bukkit.plugin.java.JavaPlugin;
import com.tchristofferson.configupdater.ConfigUpdater;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import click.sattr.plsDonate.platform.DonationPlatform;
import click.sattr.plsDonate.platform.tako.TakoPlatform;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PlsDonate extends JavaPlugin implements Listener {

    private FileConfiguration langConfig;
    private WebhookManager webhookManager;
    private StorageManager storageManager;
    private DonationPlatform donationPlatform;
    private TriggersManager triggersManager;
    private EmailManager emailManager;
    private OverlayManager overlayManager;
    private BedrockFormHandler bedrockFormHandler;
    private DonateCommand donateCommand;
    private plsDonateCommand pdnCommand;

    // Getters for subsystems
    public StorageManager getStorageManager() { return storageManager; }
    public DonationPlatform getDonationPlatform() { return donationPlatform; }
    public FileConfiguration getLangConfig() { return langConfig; }
    public TriggersManager getTriggersManager() { return triggersManager; }
    public EmailManager getEmailManager() { return emailManager; }
    public OverlayManager getOverlayManager() { return overlayManager; }
    public BedrockFormHandler getBedrockFormHandler() { return bedrockFormHandler; }
    
    @Override
    public void onEnable() {
        // Create plugin folder if it doesn't exist
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        File configFile = new File(getDataFolder(), "config.yml");
        
        // If config doesn't exist, save default
        if (!configFile.exists()) {
            saveResource("config.yml", false);
        } else {
            // If it exists, update it
            try {
                ConfigUpdater.update(this, "config.yml", configFile, Collections.emptyList());
            } catch (IOException e) {
                getLogger().severe("Could not update config.yml!");
                e.printStackTrace();
            }
        }

        // Now reload to make sure we have the latest data
        reloadConfig();
        loadLanguageConfig();

        // Initialize Overlay Manager
        overlayManager = new OverlayManager(this);
        
        // Initial update and schedule periodic tasks
        if (overlayManager.isConfigured()) {
            getLogger().info("Fetching initial leaderboard and milestone data from Overlay API...");
            overlayManager.updateCacheAsync().thenRun(() -> {
                getLogger().info("Successfully cached leaderboard and milestone data.");
            });
            
            long interval = getConfig().getLong("tako.overlay-api.update-interval", 7);
            if (interval > 0) {
                Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                    overlayManager.updateCacheAsync();
                }, interval * 1200L, interval * 1200L); // Interval in minutes to ticks (20 * 60)
            }

            // Schedule silent ping every 3 minutes to keep API warm
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                overlayManager.pingApi();
            }, 600L, 3600L); // Initial 30s delay, then every 3 minutes (180s * 20 ticks)
        }

        // Initialize Storage Manager
        storageManager = new StorageManager(this);
        
        // Initialize Triggers Manager
        triggersManager = new TriggersManager(this);

        // Register Donate Command
        donateCommand = new DonateCommand(this);
        getCommand("donate").setExecutor(donateCommand);
        getCommand("donate").setTabCompleter(donateCommand);

        pdnCommand = new plsDonateCommand(this);
        getCommand("plsdonate").setExecutor(pdnCommand);
        
        getServer().getPluginManager().registerEvents(this, this);

        loadActivePlatform();
        
        emailManager = new EmailManager(this);
        
        // Initialize Bedrock/Floodgate Handler if enabled and installed
        if (getConfig().getBoolean("bedrock-support", false) && getServer().getPluginManager().getPlugin("floodgate") != null) {
            try {
                bedrockFormHandler = new BedrockFormHandler(this);
                getLogger().info("Geyser/Floodgate detected! Bedrock UI support enabled.");
            } catch (Exception e) {
                getLogger().warning("Failed to initialize Bedrock forms although floodgate was detected.");
            }
        }

        // Mandatory Webhook Initialization
        webhookManager = new WebhookManager(this);
        int port = getConfig().getInt("webhook.port", 8080);
        String path = getConfig().getString("webhook.path", "/donate");
        
        if (!webhookManager.start(port, path)) {
            getLogger().severe("Disabling plugin due to mandatory webhook failure!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Delayed startup message to appear after "Done!"
        Bukkit.getScheduler().runTask(this, () -> {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("{PREFIX}", langConfig.getString("prefix", "<gray>[<green>plsDonate<gray>]<reset>"));
            placeholders.put("{PORT}", String.valueOf(getConfig().getInt("webhook.port", 8080)));
            Bukkit.getConsoleSender().sendMessage(parseMessage(langConfig.getString("startup-success", "{PREFIX} <green>plsDonate version " + getPluginMeta().getVersion() + " loaded!</green>"), placeholders));
            
            checkImportantConfigs();
        });
    }

    private void checkImportantConfigs() {
        Map<String, String> p = new HashMap<>();
        p.put("{PREFIX}", langConfig.getString("prefix", "<gray>[<green>plsDonate<gray>]<reset>"));

        String takoToken = getConfig().getString("tako.webhook-token", "your_secret_token_here");
        if (takoToken.isEmpty() || "your_secret_token_here".equals(takoToken)) {
            Bukkit.getConsoleSender().sendMessage(parseMessage("{PREFIX} <red>[!] Tako.id Webhook Token is not set! (tako.webhook-token)</red>", p));
        }

        String takoCreator = getConfig().getString("tako.creator", "");
        if (takoCreator.isEmpty()) {
            Bukkit.getConsoleSender().sendMessage(parseMessage("{PREFIX} <red>[!] Tako.id Creator is empty! (tako.creator)</red>", p));
        }

        String takoKey = getConfig().getString("tako.api-key", "your_secret_api_key_here");
        if (takoKey.isEmpty() || "your_secret_api_key_here".equals(takoKey)) {
            Bukkit.getConsoleSender().sendMessage(parseMessage("{PREFIX} <red>[!] Tako.id API Key is empty! (tako.api-key)</red>", p));
        }

        // Check Overlay API
        String overlayBase = getConfig().getString("tako.overlay-api.base-url", "");
        String overlayKey = getConfig().getString("tako.overlay-api.overlay-key", "");
        if (overlayBase.isEmpty() || overlayKey.isEmpty()) {
            Bukkit.getConsoleSender().sendMessage(parseMessage("{PREFIX} <yellow>[!] Overlay API is not fully configured. /pdn leaderboard/milestone will be disabled.</yellow>", p));
        }

        // Email hosts check
        org.bukkit.configuration.ConfigurationSection hosts = getConfig().getConfigurationSection("email.hosts");
        if (hosts == null || hosts.getKeys(false).isEmpty()) {
            Bukkit.getConsoleSender().sendMessage(parseMessage("{PREFIX} <red>[!] 'email.hosts' is missing/empty in config.yml! Payment emails will not be sent.</red>", p));
        } else {
            boolean hasValidHost = false;
            for (String hostKey : hosts.getKeys(false)) {
                String user = getConfig().getString("email.hosts." + hostKey + ".user", "");
                String host = getConfig().getString("email.hosts." + hostKey + ".host", "");
                if (!user.isEmpty() && !host.isEmpty() && !"email@gmail.com".equalsIgnoreCase(user)) {
                    hasValidHost = true;
                    break;
                }
            }
            if (!hasValidHost) {
                Bukkit.getConsoleSender().sendMessage(parseMessage("{PREFIX} <red>[!] All SMTP hosts are using default/blank credentials! Payment emails will not work.</red>", p));
            }
        }
    }

    private void loadLanguageConfig() {
        String langName = getConfig().getString("language", "en-US");
        File langFile = new File(getDataFolder(), "lang/" + langName + ".yml");
        
        if (!langFile.exists()) {
            getLogger().warning("Language file " + langName + ".yml not found! Defaulting to en-US.yml");
            langFile = new File(getDataFolder(), "lang/en-US.yml");
            if (!langFile.exists()) {
                saveResource("lang/en-US.yml", false);
            }
        }
        
        try {
            ConfigUpdater.update(this, "lang/en-US.yml", langFile, Collections.emptyList());
        } catch (IOException e) {
            getLogger().severe("Could not update language file: " + langFile.getName());
            e.printStackTrace();
        }

        langConfig = YamlConfiguration.loadConfiguration(langFile);
    }

    @Override
    public void onDisable() {
        if (webhookManager != null) {
            webhookManager.stop();
        }
    }

    public void reloadPlugin() {
        if (webhookManager != null) {
            webhookManager.stop();
        }

        File configFile = new File(getDataFolder(), "config.yml");
        try {
            ConfigUpdater.update(this, "config.yml", configFile, Collections.emptyList());
        } catch (IOException e) {
            getLogger().severe("Could not update config.yml during reload!");
            e.printStackTrace();
        }
        
        reloadConfig();
        loadLanguageConfig();
        loadActivePlatform();

        if (triggersManager != null) {
            triggersManager.loadConfig();
        }
        
        if (emailManager != null) {
            emailManager.reload();
        }

        if (bedrockFormHandler == null && getConfig().getBoolean("bedrock-support", false) && getServer().getPluginManager().getPlugin("floodgate") != null) {
            try {
                bedrockFormHandler = new BedrockFormHandler(this);
            } catch (Exception e) {
                getLogger().warning("Failed to initialize Bedrock forms during reload.");
            }
        }

        int port = getConfig().getInt("webhook.port", 8080);
        String path = getConfig().getString("webhook.path", "/donate");
        
        if (!webhookManager.start(port, path)) {
            getLogger().severe("Failed to restart mandatory webhook listener during reload!");
        }

        checkImportantConfigs();
    }

    public Component parseMessage(String string, Map<String, String> placeholders) {
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                string = string.replace(entry.getKey(), entry.getValue());
            }
        }
        return parseMessage(string);
    }

    public Component parseMessage(String string) {
        return MiniMessage.miniMessage().deserialize(string);
    }

    public void sendLangMessage(CommandSender sender, String path, Map<String, String> placeholders) {
        String prefix = langConfig.getString("prefix", "<yellow>[plsDonate]</yellow> ");
        String message = langConfig.getString(path, "<red>Missing translation for: " + path + "</red>");

        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                message = message.replace(entry.getKey(), entry.getValue());
            }
        }
        message = message.replace("{PREFIX}", prefix);

        sender.sendMessage(parseMessage(message));
    }

    public void sendLangMessage(CommandSender sender, String path) {
        sendLangMessage(sender, path, null);
    }

    public void sendLangMessageList(CommandSender sender, String path, Map<String, String> placeholders) {
        if (!langConfig.contains(path)) {
            sender.sendMessage(parseMessage("<red>Missing list translation for: " + path + "</red>"));
            return;
        }

        String prefix = langConfig.getString("prefix", "<yellow>[plsDonate]</yellow> ");
        List<String> list = langConfig.getStringList(path);

        for (String line : list) {
            String processedLine = line;
            if (placeholders != null) {
                for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                    processedLine = processedLine.replace(entry.getKey(), entry.getValue());
                }
            }
            processedLine = processedLine.replace("{PREFIX}", prefix);
            sender.sendMessage(parseMessage(processedLine));
        }
    }

    public void playConfigSounds(Player player, String path) {
        List<String> soundStrings = getConfig().getStringList(path);
        if (soundStrings.isEmpty()) return;

        for (String s : soundStrings) {
            if (s == null || s.isBlank()) continue;
            
            String[] parts = s.split(",");
            String soundName = parts[0].trim();
            float pitch = 1.0f;
            float volume = 1.0f;

            if (parts.length >= 2) {
                try {
                    pitch = Float.parseFloat(parts[1].trim());
                } catch (NumberFormatException ignored) {}
            }
            if (parts.length >= 3) {
                try {
                    volume = Float.parseFloat(parts[2].trim());
                } catch (NumberFormatException ignored) {}
            }

            try {
                player.playSound(player.getLocation(), soundName, volume, pitch);
            } catch (Exception e) {
                getLogger().warning("Could not play sound '" + soundName + "': " + e.getMessage());
            }
        }
    }

    public String formatIndonesianNumber(double number) {
        NumberFormat nf = NumberFormat.getInstance(Locale.forLanguageTag("id-ID"));
        return nf.format(number);
    }

    public void loadActivePlatform() {
        donationPlatform = new TakoPlatform(this);
        getLogger().info("Donation Platform: Tako.id Enabled (Express Version)");
    }

    public Map<String, String> getDonationPlaceholders(String donorName, double amount, String email, String method, String message) {
        Map<String, String> p = new HashMap<>();
        p.put("{PLAYER}", donorName);
        p.put("{PLAYER_UPPERCASED}", donorName.toUpperCase());
        p.put("{PLAYER_LOWERCASED}", donorName.toLowerCase());
        p.put("{AMOUNT}", String.valueOf((long) amount));
        p.put("{AMOUNT_FORMATTED}", formatIndonesianNumber(amount));
        p.put("{EMAIL}", email != null ? email : "");
        
        String msg = (message == null || message.isEmpty()) ? "No message" : message;
        p.put("{MESSAGE}", msg);
        p.put("{MESSAGE_LOWERCASED}", msg.toLowerCase());
        p.put("{MESSAGE_UPPERCASED}", msg.toUpperCase());

        String baseLabel = "QRIS";
        String colorCode = "#ED1A3D";
        if (method != null) {
            String m = method.toLowerCase();
            if (m.equals("gopay")) { baseLabel = "GoPay"; colorCode = "#01AED6"; }
            else if (m.equals("paypal")) { baseLabel = "PayPal"; colorCode = "#195ef7"; }
        }

        p.put("{METHOD}", baseLabel);
        p.put("{METHOD_LOWERCASED}", baseLabel.toLowerCase());
        p.put("{METHOD_UPPERCASED}", baseLabel.toUpperCase());
        
        String colored = "<" + colorCode + ">" + baseLabel + "</" + colorCode + ">";
        String coloredLower = "<" + colorCode + ">" + baseLabel.toLowerCase() + "</" + colorCode + ">";
        String coloredUpper = "<" + colorCode + ">" + baseLabel.toUpperCase() + "</" + colorCode + ">";
        
        p.put("{METHOD_COLORED}", colored);
        p.put("{METHOD_COLORED_LOWERCASED}", coloredLower);
        p.put("{METHOD_COLORED_UPPERCASED}", coloredUpper);
        
        return p;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (donateCommand != null) {
            donateCommand.clearPendingRequests(event.getPlayer().getUniqueId());
        }
        if (pdnCommand != null) {
            pdnCommand.clearPendingRequests(event.getPlayer().getUniqueId());
        }
    }
}
