package click.sattr.plsDonate;

import click.sattr.plsDonate.command.DonateCommand;
import click.sattr.plsDonate.command.plsDonateCommand;
import click.sattr.plsDonate.database.DatabaseManager;
import click.sattr.plsDonate.database.repository.OfflineTriggerRepository;
import click.sattr.plsDonate.database.repository.TransactionRepository;
import click.sattr.plsDonate.manager.BedrockFormHandler;
import click.sattr.plsDonate.manager.DiscordManager;
import click.sattr.plsDonate.manager.JavaDialogHandler;
import click.sattr.plsDonate.manager.DonationService;
import click.sattr.plsDonate.manager.EmailManager;
import click.sattr.plsDonate.manager.StatsManager;
import click.sattr.plsDonate.manager.TriggersManager;
import click.sattr.plsDonate.platform.DonationPlatform;
import click.sattr.plsDonate.platform.tako.TakoPlatform;
import click.sattr.plsDonate.util.Constants;
import click.sattr.plsDonate.util.MessageUtils;
import click.sattr.plsDonate.util.PluginLogger;
import click.sattr.plsDonate.util.ProtocolVersionUtil;
import click.sattr.plsDonate.util.UpdateChecker;
import click.sattr.plsDonate.webhook.WebhookManager;
import com.tchristofferson.configupdater.ConfigUpdater;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PlsDonate extends JavaPlugin implements Listener {

    private static final int BSTATS_PLUGIN_ID = 32260;

    private FileConfiguration langConfig;
    private WebhookManager webhookManager;
    private Metrics metrics;
    
    private DatabaseManager databaseManager;
    private TransactionRepository transactionRepository;
    private OfflineTriggerRepository offlineTriggerRepository;
    
    private DonationPlatform donationPlatform;
    private TriggersManager triggersManager;
    private EmailManager emailManager;
    private DonationService donationService;
    private StatsManager statsManager;
    private DiscordManager discordManager;
    private BedrockFormHandler bedrockFormHandler;
    private JavaDialogHandler javaDialogHandler;
    private DonateCommand donateCommand;
    private plsDonateCommand pdnCommand;

    // Getters for subsystems
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public TransactionRepository getTransactionRepository() { return transactionRepository; }
    public OfflineTriggerRepository getOfflineTriggerRepository() { return offlineTriggerRepository; }
    
    public DonationPlatform getDonationPlatform() { return donationPlatform; }
    public FileConfiguration getLangConfig() { return langConfig; }
    public TriggersManager getTriggersManager() { return triggersManager; }
    public EmailManager getEmailManager() { return emailManager; }
    public DonationService getDonationService() { return donationService; }
    public StatsManager getStatsManager() { return statsManager; }
    public DiscordManager getDiscordManager() { return discordManager; }
    public BedrockFormHandler getBedrockFormHandler() { return bedrockFormHandler; }
    public JavaDialogHandler getJavaDialogHandler() { return javaDialogHandler; }
    public DonateCommand getDonateCommand() { return donateCommand; }
    
    @Override
    public void onEnable() {
        PluginLogger.init(this);

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
        
        // Save default templates
        saveDefaultTemplates();
        
        loadLanguageConfig();

        // Detect ViaVersion for per-player client protocol version checking
        ProtocolVersionUtil.init(this);

        // Initialize Database & Repositories
        databaseManager = new DatabaseManager(this);
        transactionRepository = new TransactionRepository(this, databaseManager);
        offlineTriggerRepository = new OfflineTriggerRepository(this, databaseManager);
        
        // Initialize Triggers Manager
        triggersManager = new TriggersManager(this);

        // Initialize Donation Service
        donationService = new DonationService(this);

        // Initialize Discord webhook notifications. Reads config per-request, so it is
        // created once here and never recreated on reload (avoids leaking HttpClient pools).
        discordManager = new DiscordManager(this);

        // Initialize stats cache (leaderboard + milestone) and warm it from the DB
        statsManager = new StatsManager(this);
        statsManager.refresh();

        // Register Donate Command
        donateCommand = new DonateCommand(this);
        getCommand("donate").setExecutor(donateCommand);
        getCommand("donate").setTabCompleter(donateCommand);

        pdnCommand = new plsDonateCommand(this);
        getCommand("plsdonate").setExecutor(pdnCommand);
        
        getServer().getPluginManager().registerEvents(this, this);

        loadActivePlatform();
        
        emailManager = new EmailManager(this);

        // Register PlaceholderAPI expansion
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new click.sattr.plsDonate.util.PlsDonateExpansion(this).register();
            PluginLogger.info("PlaceholderAPI " + getServer().getPluginManager().getPlugin("PlaceholderAPI").getDescription().getVersion() + " detected - https://github.com/SatuSattr/plsDonate/wiki/PlaceholderAPI-Integration");
        }

        // Initialize Bedrock/Floodgate Handler if installed
        if (getServer().getPluginManager().getPlugin("floodgate") != null) {
            try {
                bedrockFormHandler = new BedrockFormHandler(this);
                PluginLogger.info("Floodgate " + getServer().getPluginManager().getPlugin("floodgate").getDescription().getVersion() + " detected - Bedrock UI support enabled");
            } catch (Exception e) {
                PluginLogger.warn("Failed to initialize Bedrock forms although floodgate was detected.");
            }
        }

        // Log Geyser if present
        org.bukkit.plugin.Plugin geyser = getServer().getPluginManager().getPlugin("Geyser-Spigot");
        if (geyser == null) geyser = getServer().getPluginManager().getPlugin("Geyser");
        if (geyser != null) {
            PluginLogger.info("Geyser " + geyser.getDescription().getVersion() + " detected");
        }

        // Log SkinsRestorer if present
        org.bukkit.plugin.Plugin skinsRestorer = getServer().getPluginManager().getPlugin("SkinsRestorer");
        if (skinsRestorer != null) {
            PluginLogger.info("SkinsRestorer " + skinsRestorer.getDescription().getVersion() + " detected");
        }

        // Log ViaVersion if present
        org.bukkit.plugin.Plugin viaVersion = getServer().getPluginManager().getPlugin("ViaVersion");
        if (viaVersion != null) {
            PluginLogger.info("ViaVersion " + viaVersion.getDescription().getVersion() + " detected - per-player dialog version check enabled");
        }

        // Java Dialog support (1.21.6+)
        try {
            if (JavaDialogHandler.isServerSupported()) {
                javaDialogHandler = new JavaDialogHandler(this);
                PluginLogger.info("Java Dialog API 1.21.6+ support enabled");
            }
        } catch (Throwable t) {
            // silently skip — older server version
        }

        // Mandatory Webhook Initialization
        webhookManager = new WebhookManager(this);
        int port = getConfig().getInt(Constants.CONF_WEBHOOK_PORT, Constants.DEFAULT_WEBHOOK_PORT);
        String path = getConfig().getString(Constants.CONF_WEBHOOK_PATH, Constants.DEFAULT_WEBHOOK_PATH);
        
        if (!webhookManager.start(port, path)) {
            PluginLogger.severe("Disabling plugin due to mandatory webhook failure!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        PluginLogger.info("Webhook listener started on port " + port + " at path " + path);

        // Initialize bStats metrics
        metrics = new Metrics(this, BSTATS_PLUGIN_ID);

        // Delayed startup message to appear after "Done!"
        Bukkit.getScheduler().runTask(this, () -> {
            String prefix = langConfig.getString("prefix", Constants.DEFAULT_PREFIX);
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put(Constants.PREFIX, prefix);
            placeholders.put("{PORT}", String.valueOf(port));

            Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage(
                "{PREFIX} <green>plsDonate has been loaded successfully! <reset>Webhook is listening on port <yellow>{PORT}",
                placeholders));

            if (isLocalEnvironment()) {
                Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage("{PREFIX} <red>[!] LOCAL ENVIRONMENT DETECTED [!]", placeholders));
                Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage("{PREFIX} <yellow>It seems you are running this server in a local environment.", placeholders));
                Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage("{PREFIX} <yellow>Please ensure that your webhook port (<white>{PORT}<yellow>) is accessible from the internet.", placeholders));
                Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage("{PREFIX} <yellow>You might need to use <white>Port Forwarding <yellow>or <white>ngrok <yellow>to make it work.", placeholders));
            }

            checkImportantConfigs();
        });
    }

    private boolean isLocalEnvironment() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) continue;

                java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (address instanceof java.net.Inet4Address) {
                        if (!address.isLoopbackAddress() && !address.isSiteLocalAddress() && !address.isLinkLocalAddress()) {
                            return false; // Found a non-local address
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    private void checkImportantConfigs() {
        checkImportantConfigs(null);
    }

    private void checkImportantConfigs(@Nullable CommandSender sender) {
        Map<String, String> p = new HashMap<>();
        p.put(Constants.PREFIX, langConfig.getString("prefix", Constants.DEFAULT_PREFIX));

        String takoToken = getConfig().getString(Constants.CONF_TAKO_TOKEN, "your_secret_token_here");
        if (takoToken.isEmpty() || "your_secret_token_here".equals(takoToken)) {
            Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage("{PREFIX} <red>[!] Tako.id Webhook Token is not set! (" + Constants.CONF_TAKO_TOKEN + ")</red>", p));
        }

        String takoCreator = getConfig().getString(Constants.CONF_TAKO_CREATOR, "");
        if (takoCreator.isEmpty()) {
            Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage("{PREFIX} <red>[!] Tako.id Creator is empty! (" + Constants.CONF_TAKO_CREATOR + ")</red>", p));
        }

        String takoKey = getConfig().getString(Constants.CONF_TAKO_KEY, "your_secret_api_key_here");
        if (takoKey.isEmpty() || "your_secret_api_key_here".equals(takoKey)) {
            Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage("{PREFIX} <red>[!] Tako.id API Key is empty! (" + Constants.CONF_TAKO_KEY + ")</red>", p));
        }

        // Email hosts check
        List<Map<?, ?>> hostsList = getConfig().getMapList("email.hosts");
        if (hostsList == null || hostsList.isEmpty()) {
            Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage("{PREFIX} <red>[!] 'email.hosts' is missing/empty in config.yml! Payment emails will not be sent.</red>", p));
        } else {
            boolean hasValidHost = false;
            for (Map<?, ?> hostMap : hostsList) {
                if (hostMap == null) continue;
                String user = String.valueOf(hostMap.get("user"));
                String host = String.valueOf(hostMap.get("host"));
                if (!user.isEmpty() && !host.isEmpty() && !"email@gmail.com".equalsIgnoreCase(user)) {
                    hasValidHost = true;
                    break;
                }
            }
            if (!hasValidHost) {
                Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage("{PREFIX} <red>[!] All SMTP hosts are using default/blank credentials! Payment emails will not work.</red>", p));
            }
        }

        validateConfigValues(sender);
    }

    /**
     * Validates all numeric config values to reject negative/invalid values.
     * When called during reload by a player, errors are sent to both the player
     * and PluginLogger. Otherwise only PluginLogger is used.
     */
    private void validateConfigValues(@Nullable CommandSender sender) {
        Player player = (sender instanceof Player) ? (Player) sender : null;
        int errors = 0;

        errors += checkNotNegative(player, Constants.CONF_DONATE_MIN_AMOUNT, getConfig().getDouble(Constants.CONF_DONATE_MIN_AMOUNT, 1000));

        double maxAmount = getConfig().getDouble(Constants.CONF_DONATE_MAX_AMOUNT, 10000000);
        errors += checkNotNegative(player, Constants.CONF_DONATE_MAX_AMOUNT, maxAmount);
        double minAmount = getConfig().getDouble(Constants.CONF_DONATE_MIN_AMOUNT, 1000);
        if (minAmount >= 0 && maxAmount >= 0 && maxAmount < minAmount) {
            String msg = "'" + Constants.CONF_DONATE_MAX_AMOUNT + "' (" + maxAmount + ") is less than '" + Constants.CONF_DONATE_MIN_AMOUNT + "' (" + minAmount + ").";
            PluginLogger.severe(msg);
            if (player != null) sendErrorToPlayer(player, msg);
            errors++;
        }

        errors += checkNotNegative(player, Constants.CONF_DONATE_COOLDOWN, getConfig().getInt(Constants.CONF_DONATE_COOLDOWN, 15));

        int maxMsgLen = getConfig().getInt(Constants.CONF_DONATE_MAX_MESSAGE, 100);
        if (maxMsgLen <= 0) {
            String msg = "'" + Constants.CONF_DONATE_MAX_MESSAGE + "' is " + maxMsgLen + ". Must be > 0.";
            PluginLogger.severe(msg);
            if (player != null) sendErrorToPlayer(player, msg);
            errors++;
        }

        int port = getConfig().getInt(Constants.CONF_WEBHOOK_PORT, Constants.DEFAULT_WEBHOOK_PORT);
        if (port < 1 || port > 65535) {
            String msg = "'" + Constants.CONF_WEBHOOK_PORT + "' is " + port + ". Must be between 1 and 65535.";
            PluginLogger.severe(msg);
            if (player != null) sendErrorToPlayer(player, msg);
            errors++;
        }

        errors += checkNotNegative(player, Constants.CONF_MILESTONE_TARGET, getConfig().getDouble(Constants.CONF_MILESTONE_TARGET, 1000000));
        errors += checkNotNegative(player, Constants.CONF_MILESTONE_OFFSET, getConfig().getDouble(Constants.CONF_MILESTONE_OFFSET, 0));

        if (errors > 0) {
            String msg = "Found " + errors + " invalid config value(s). Please fix them in config.yml and run /pdn reload.";
            PluginLogger.severe(msg);
            if (player != null) sendErrorToPlayer(player, msg);
        }
    }

    private int checkNotNegative(@Nullable Player player, String configKey, double value) {
        if (value >= 0) return 0;
        String msg = "'" + configKey + "' is negative (" + value + "). Must be >= 0.";
        PluginLogger.severe(msg);
        if (player != null) sendErrorToPlayer(player, msg);
        return 1;
    }

    private void sendErrorToPlayer(Player player, String message) {
        Map<String, String> p = new HashMap<>();
        p.put(Constants.PREFIX, langConfig.getString("prefix", Constants.DEFAULT_PREFIX));
        player.sendMessage(MessageUtils.parseMessage("{PREFIX} <red>[!] " + message + "</red>", p));
    }

    private void loadLanguageConfig() {
        String langName = getConfig().getString("language", "en-US");
        File langFolder = new File(getDataFolder(), "lang");
        if (!langFolder.exists()) langFolder.mkdirs();

        File langFile = new File(langFolder, langName + ".yml");
        
        if (!langFile.exists()) {
            try {
                saveResource("lang/" + langName + ".yml", false);
            } catch (IllegalArgumentException e) {
                getLogger().warning("Language file lang/" + langName + ".yml not found in resources! Falling back to en-US.yml");
                langName = "en-US";
                langFile = new File(langFolder, "en-US.yml");
                if (!langFile.exists()) {
                    saveResource("lang/en-US.yml", false);
                }
            }
        }
        
        try {
            String resourcePath = "lang/" + langName + ".yml";
            try {
                ConfigUpdater.update(this, resourcePath, langFile, Collections.emptyList());
            } catch (Exception e) {
                ConfigUpdater.update(this, "lang/en-US.yml", langFile, Collections.emptyList());
            }
        } catch (IOException e) {
            getLogger().severe("Could not update language file: " + langFile.getName());
            e.printStackTrace();
        }

        langConfig = YamlConfiguration.loadConfiguration(langFile);
        getLogger().info("Language loaded: " + langName);
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

    public void reloadPlugin() {
        reloadPlugin(null);
    }

    public void reloadPlugin(@Nullable CommandSender sender) {
        PluginLogger.info("Reloading plsDonate configuration...");
        if (webhookManager != null) {
            webhookManager.stop();
        }

        File configFile = new File(getDataFolder(), "config.yml");
        try {
            ConfigUpdater.update(this, "config.yml", configFile, Collections.emptyList());
        } catch (IOException e) {
            PluginLogger.severe("Could not update config.yml during reload!");
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

        if (statsManager != null) {
            statsManager.refresh();
        }

        if (bedrockFormHandler == null && getServer().getPluginManager().getPlugin("floodgate") != null) {
            try {
                bedrockFormHandler = new BedrockFormHandler(this);
            } catch (Exception e) {
                PluginLogger.warn("Failed to initialize Bedrock forms during reload.");
            }
        }

        int port = getConfig().getInt(Constants.CONF_WEBHOOK_PORT, Constants.DEFAULT_WEBHOOK_PORT);
        String path = getConfig().getString(Constants.CONF_WEBHOOK_PATH, Constants.DEFAULT_WEBHOOK_PATH);
        
        if (!webhookManager.start(port, path)) {
            PluginLogger.severe("Failed to restart mandatory webhook listener during reload!");
        }

        checkImportantConfigs(sender);
        PluginLogger.info("plsDonate reload complete.");
    }

    public void loadActivePlatform() {
        // TakoPlatform reads all config per-request, so it never needs recreating on reload.
        // Recreating would leak the previous instance's HttpClient thread pool.
        if (donationPlatform == null) {
            donationPlatform = new TakoPlatform(this);
            PluginLogger.info("Donation Platform: Tako.id Enabled");
        }
    }

    private void saveDefaultTemplates() {
        File templatesFolder = new File(getDataFolder(), "templates");
        if (!templatesFolder.exists()) {
            templatesFolder.mkdirs();
        }

        File paymentTemplate = new File(templatesFolder, "payment.html");
        if (!paymentTemplate.exists()) {
            saveResource("templates/payment.html", false);
        }
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

    @EventHandler
    public void onPlayerJoinUpdateCheck(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission(Constants.PERM_UPDATE_NOTIFY)) return;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            if (!UpdateChecker.hasChecked()) return;

            String latest = UpdateChecker.getLatestVersion();
            if (latest != null && UpdateChecker.isUpdateAvailable(getPluginMeta().getVersion())) {
                String prefix = langConfig.getString("prefix", Constants.DEFAULT_PREFIX);
                String url = UpdateChecker.getUpdateUrl();
                String current = getPluginMeta().getVersion();

                Map<String, String> p = new HashMap<>();
                p.put(Constants.VERSION, current);
                p.put(Constants.NEW_VERSION, latest);
                p.put(Constants.URL, url != null ? url : "");

                String msg = langConfig.getString("update-available",
                        "{PREFIX} <yellow>Update available: <click:open_url:\"{URL}\"><hover:show_text:\"<gray>Click to download\">{NEW_VERSION}</hover></click> <gray>(current: {VERSION})");
                String resolved = msg;
                for (Map.Entry<String, String> entry : p.entrySet()) {
                    resolved = resolved.replace(entry.getKey(), entry.getValue());
                }
                resolved = resolved.replace(Constants.PREFIX, prefix);

                player.sendMessage(MessageUtils.parseMessage(resolved));
            }
        });
    }
}
