package click.sattr.plsDonate;

import org.bukkit.plugin.java.JavaPlugin;
import com.tchristofferson.configupdater.ConfigUpdater;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.Bukkit;
import click.sattr.plsDonate.platform.donet.DonetPlatform;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import click.sattr.plsDonate.platform.DonationPlatform;
import click.sattr.plsDonate.platform.tako.TakoPlatform;

import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PlsDonate extends JavaPlugin {

    private FileConfiguration langConfig;
    private WebhookManager webhookManager;
    private DatabaseManager databaseManager;
    private DonationPlatform donationPlatform;
    private TriggersManager triggersManager;
    private EmailManager emailManager;
    private BedrockFormHandler bedrockFormHandler;

    // Getters for subsystems
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public DonationPlatform getDonationPlatform() { return donationPlatform; }
    public FileConfiguration getLangConfig() { return langConfig; }
    public TriggersManager getTriggersManager() { return triggersManager; }
    public EmailManager getEmailManager() { return emailManager; }
    public BedrockFormHandler getBedrockFormHandler() { return bedrockFormHandler; }
    public void onEnable() {
        // Plugin startup logic
        saveDefaultConfig();
        if (!new File(getDataFolder(), "lang/en-US.yml").exists()) {
            saveResource("lang/en-US.yml", false);
        }
        
        if (!new File(getDataFolder(), "templates/payment.html").exists()) {
            saveResource("templates/payment.html", false);
        }

        File configFile = new File(getDataFolder(), "config.yml");
        try {
            ConfigUpdater.update(this, "config.yml", configFile, Collections.emptyList());
        } catch (IOException e) {
            getLogger().severe("Could not update config.yml!");
            e.printStackTrace();
        }

        reloadConfig();
        
        loadLanguageConfig();

        // Initialize SQLite Database
        databaseManager = new DatabaseManager(this);
        if (!databaseManager.connect()) {
            getLogger().severe("Disabling plugin due to Database connection failure!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        // Initialize Triggers Manager
        triggersManager = new TriggersManager(this);

        // Register Dynamic Donate Command
        try {
            String alias = getConfig().getString("donate.command", "donate");
            getServer().getCommandMap().register("plsdonate", new CustomDonateCommand(alias, this));
            getLogger().info("Registered custom donation command: /" + alias);
        } catch (Exception e) {
            getLogger().severe("Failed to register custom donate command!");
            e.printStackTrace();
        }

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

        getCommand("plsdonate").setExecutor(new DonateCommand(this));

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

        String activePlatform = getConfig().getString("platform.active", "tako").toLowerCase();

        if (activePlatform.equals("tako")) {
            String takoToken = getConfig().getString("platform.tako.webhook-token", getConfig().getString("webhook.token", ""));
            if (takoToken.isEmpty() || "your_secret_token_here".equals(takoToken)) {
                Bukkit.getConsoleSender().sendMessage(parseMessage("{PREFIX} <red>[!] Tako.id Webhook Token is not set! (platform.tako.webhook-token)</red>", p));
            }

            String takoCreator = getConfig().getString("platform.tako.creator", getConfig().getString("api.creator", ""));
            if (takoCreator.isEmpty()) {
                Bukkit.getConsoleSender().sendMessage(parseMessage("{PREFIX} <red>[!] Tako.id Creator is empty! (platform.tako.creator)</red>", p));
            }

            String takoKey = getConfig().getString("platform.tako.api-key", getConfig().getString("api.key", ""));
            if (takoKey.isEmpty() || "your_secret_api_key_here".equals(takoKey)) {
                Bukkit.getConsoleSender().sendMessage(parseMessage("{PREFIX} <red>[!] Tako.id API Key is empty! (platform.tako.api-key)</red>", p));
            }
        } else if (activePlatform.equals("donet")) {
            String donetToken = getConfig().getString("platform.donet.webhook-token", "");
            if (donetToken.isEmpty() || "your_secret_token_here".equals(donetToken)) {
                Bukkit.getConsoleSender().sendMessage(parseMessage("{PREFIX} <red>[!] Donet.co Webhook Token is not set! (platform.donet.webhook-token)</red>", p));
            }

            String donetCreator = getConfig().getString("platform.donet.creator", "");
            if (donetCreator.isEmpty()) {
                Bukkit.getConsoleSender().sendMessage(parseMessage("{PREFIX} <red>[!] Donet.co Creator is empty! (platform.donet.creator)</red>", p));
            }
        }

        if (getConfig().getBoolean("bedrock-support", false)) {
            org.bukkit.configuration.ConfigurationSection hosts = getConfig().getConfigurationSection("email.hosts");
            if (hosts == null || hosts.getKeys(false).isEmpty()) {
                Bukkit.getConsoleSender().sendMessage(parseMessage("{PREFIX} <red>[!] Bedrock Support is enabled, but 'email.hosts' is missing/empty in config.yml!</red>", p));
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
                    Bukkit.getConsoleSender().sendMessage(parseMessage("{PREFIX} <red>[!] Bedrock Support is enabled, but all SMTP hosts are using default/blank credentials!</red>", p));
                }
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
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    /**
     * Reloads configuration files, updates them, and restarts the webhook listener if needed.
     */
    public void reloadPlugin() {
        // Stop Webhook before we reload config to avoid port binding issues on restart
        if (webhookManager != null) {
            webhookManager.stop();
        }

        // Fetch configs & update defaults
        File configFile = new File(getDataFolder(), "config.yml");

        try {
            ConfigUpdater.update(this, "config.yml", configFile, Collections.emptyList());
        } catch (IOException e) {
            getLogger().severe("Could not update config.yml during reload!");
            e.printStackTrace();
        }
        
        // Reload into memory
        reloadConfig();
        loadLanguageConfig();
        loadActivePlatform();

        if (triggersManager != null) {
            triggersManager.loadConfig();
        }
        
        if (emailManager != null) {
            emailManager.reload();
        }

        // Check if Bedrock support was just enabled mid-game, initialize if null
        if (bedrockFormHandler == null && getConfig().getBoolean("bedrock-support", false) && getServer().getPluginManager().getPlugin("floodgate") != null) {
            try {
                bedrockFormHandler = new BedrockFormHandler(this);
                getLogger().info("Geyser/Floodgate detected during reload! Bedrock UI support enabled.");
            } catch (Exception e) {
                getLogger().warning("Failed to initialize Bedrock forms although floodgate was detected.");
            }
        }

        // Restart webhook with new config (Mandatory)
        int port = getConfig().getInt("webhook.port", 8080);
        String path = getConfig().getString("webhook.path", "/donate");
        
        if (!webhookManager.start(port, path)) {
            getLogger().severe("Failed to restart mandatory webhook listener during reload!");
        }

        checkImportantConfigs();
    }

    /**
     * Parses a string to a Component using MiniMessage.
     * @param string The MiniMessage string to parse.
     * @param placeholders Key-Value map of {PLACEHOLDER} -> Replacement (e.g., {"{AMOUNT}": "10"})
     * @return The parsed Component.
     */
    public Component parseMessage(String string, Map<String, String> placeholders) {
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                string = string.replace(entry.getKey(), entry.getValue());
            }
        }
        return parseMessage(string);
    }

    /**
     * Parses a string to a Component using MiniMessage.
     * @param string The MiniMessage string to parse.
     * @return The parsed Component.
     */
    public Component parseMessage(String string) {
        return MiniMessage.miniMessage().deserialize(string);
    }

    /**
     * Sends a prefixed MiniMessage to a CommandSender with placeholders.
     * @param sender The receiver
     * @param path The path in lang.yml
     * @param placeholders Placeholder replacement map
     */
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

    /**
     * Sends a string message to a CommandSender without prefix (used internally for lists).
     */
    public void sendLangMessage(CommandSender sender, String path) {
        sendLangMessage(sender, path, null);
    }

    /**
     * Sends a list of prefixed MiniMessages to a CommandSender with placeholders.
     * Often used for multi-line messages like 'donation-confirmation-java'.
     */
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

    /**
     * Plays a sequence of sounds defined in config.yml at the given path.
     * Format: "sound_name,pitch,volume"
     */
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

    /**
     * Formats a number using Indonesian locale (dots as thousand separators).
     */
    public String formatIndonesianNumber(double number) {
        NumberFormat nf = NumberFormat.getInstance(Locale.forLanguageTag("id-ID"));
        return nf.format(number);
    }

    public void loadActivePlatform() {
        String activePlatform = getConfig().getString("platform.active", "tako").toLowerCase();
        if (activePlatform.equals("donet")) {
            donationPlatform = new DonetPlatform(this);
            getLogger().info("Donation Platform: Donet.co Enabled");
        } else {
            donationPlatform = new TakoPlatform(this);
            getLogger().info("Donation Platform: Tako.id Enabled");
        }
    }
}
