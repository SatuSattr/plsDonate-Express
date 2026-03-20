package click.sattr.plsDonate;

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

public class DonateCommand implements CommandExecutor, TabCompleter {

    private final PlsDonate plugin;
    // Map to store pending requests: Map<MD5Hash, DonationRequest>
    private final Map<String, DonationRequest> pendingRequests = new ConcurrentHashMap<>();
    // Map to store cooldowns: Map<PlayerUUID, LastUsageTimestamp>
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    
    // Simple Email Regex Pattern
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$");
    private static final Pattern MD5_PATTERN = Pattern.compile("^[a-fA-F0-9]{32}$");

    public DonateCommand(PlsDonate plugin) {
        this.plugin = plugin;
    }

    public record DonationRequest(UUID playerUuid, double amount, String email, String method, String message) {}

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Map<String, String> pOnly = new HashMap<>();
            pOnly.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            sender.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("command-only-players", "{PREFIX} <red>Only players can execute this command.</red>"), pOnly));
            return true;
        }

        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("help"))) {
            Map<String, String> p = new HashMap<>();
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            p.put("{COMMAND}", label);
            plugin.sendLangMessageList(player, "donation-help", p);
            return true;
        }

        // Handle Confirmation via MD5
        if (args.length == 1 && MD5_PATTERN.matcher(args[0]).matches()) {
            String hash = args[0].toLowerCase();
            DonationRequest request = pendingRequests.get(hash);

            if (request != null && request.playerUuid().equals(player.getUniqueId())) {
                pendingRequests.remove(hash);
                processDonation(player, request.amount(), request.email(), request.method(), request.message(), plugin);
                return true;
            } else {
                // Return invalid usage as requested to keep regular players unaware
                Map<String, String> p = new HashMap<>();
                p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                p.put("{COMMAND}", label);
                player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("invalid-usage", "{PREFIX} <red>Invalid usage. <reset>try to run <yellow>/donate help<reset> for help"), p));
                return true;
            }
        }

        // Cooldown Check
        if (!player.hasPermission("plsdonate.cooldown.bypass")) {
            long lastUsage = cooldowns.getOrDefault(player.getUniqueId(), 0L);
            int cooldownSeconds = plugin.getConfig().getInt("donate.cooldown", 10);
            long currentTime = System.currentTimeMillis();

            if (currentTime - lastUsage < (cooldownSeconds * 1000L)) {
                long remaining = (cooldownSeconds * 1000L - (currentTime - lastUsage)) / 1000L;
                Map<String, String> p = new HashMap<>();
                p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                p.put("{TIME}", String.valueOf(remaining + 1));
                player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("cooldown-error", "{PREFIX} <white>Sorry, you're still in <yellow>{TIME}s <white>cooldown"), p));
                return true;
            }
        }

        if (args.length < 3) {
            Map<String, String> p = new HashMap<>();
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            p.put("{COMMAND}", label);
            player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("invalid-usage", "{PREFIX} <red>Invalid usage. <reset>try to run <yellow>/donate help<reset> for help"), p));
            return true;
        }

        // 1. Amount Validation
        double amount;
        try {
            amount = Double.parseDouble(args[0]);
        } catch (NumberFormatException e) {
            Map<String, String> p = new HashMap<>();
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("general-error", "{PREFIX} <red>Something wrong with the donation system! please contact admin"), p));
            return true;
        }

        double minConfig = plugin.getConfig().getDouble("donate.amount.min", 1000);
        double maxConfig = plugin.getConfig().getDouble("donate.amount.max", 10000000);

        if (amount < minConfig) {
            Map<String, String> p = new HashMap<>();
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            p.put("{AMOUNT}", String.valueOf((long) minConfig));
            p.put("{AMOUNT_FORMATTED}", plugin.formatIndonesianNumber(minConfig));
            player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("min-donation-error", "{PREFIX} <white>Sorry, <red>minimum <white>amount of donation is <yellow>{AMOUNT_FORMATTED}"), p));
            return true;
        }

        if (amount > maxConfig) {
            Map<String, String> p = new HashMap<>();
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            p.put("{AMOUNT}", String.valueOf((long) maxConfig));
            p.put("{AMOUNT_FORMATTED}", plugin.formatIndonesianNumber(maxConfig));
            player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("max-donation-error", "{PREFIX} <white>Sorry, <red>maximum <white>amount of donation is <yellow>{AMOUNT_FORMATTED}"), p));
            return true;
        }

        // 2. Email Validation
        String email = args[1];
        if (!EMAIL_PATTERN.matcher(email).matches() || email.length() > 64) {
            Map<String, String> p = new HashMap<>();
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("invalid-email", "{PREFIX} <white>Please <red>provide <white>a valid email <gray>example: (your@gmail.com)"), p));
            return true;
        }

        // 3. Payment Method Validation
        String method = args[2].toLowerCase();
        if (!method.equals("qris") && !method.equals("gopay") && !method.equals("paypal")) {
            Map<String, String> p = new HashMap<>();
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            player.sendMessage(plugin.parseMessage("{PREFIX} <red>Invalid payment method! <yellow>Options: qris, gopay, paypal", p));
            return true;
        }

        if (method.equals("gopay") && amount < 10000) {
            Map<String, String> p = new HashMap<>();
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            player.sendMessage(plugin.parseMessage("{PREFIX} <red>Minimum donation for GoPay is Rp10.000", p));
            return true;
        }

        if (method.equals("paypal") && amount < 50000) {
            Map<String, String> p = new HashMap<>();
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            player.sendMessage(plugin.parseMessage("{PREFIX} <red>Minimum donation for PayPal is Rp50.000", p));
            return true;
        }

        // 4. Message Validation
        String messageStr = "";
        if (args.length > 3) {
            StringBuilder sb = new StringBuilder();
            for (int i = 3; i < args.length; i++) {
                sb.append(args[i]).append(" ");
            }
            messageStr = sb.toString().trim();
        }

        int configMaxMsgLen = plugin.getConfig().getInt("donate.message.max-length", 255);
        int platformMaxMsgLen = plugin.getDonationPlatform().getMaxMessageLength();
        int maxMsgLen = Math.min(Math.min(configMaxMsgLen, platformMaxMsgLen), 190);
        
        if (messageStr.length() > maxMsgLen) {
            Map<String, String> p = new HashMap<>();
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            p.put("{LIMIT}", String.valueOf(maxMsgLen));
            player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("message-length-error", "{PREFIX} <white>Sorry, <red>maximal length <white>of the message is <yellow>{LIMIT} Character. <white>Please shorten your message."), p));
            return true;
        }

        // Set Cooldown
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());

        // 5. Confirmation System
        boolean requireConfirmation = plugin.getConfig().getBoolean("donate.confirmation", true);

        if (!requireConfirmation) {
            processDonation(player, amount, email, method, messageStr, plugin);
        } else {
            if (plugin.getBedrockFormHandler() != null && plugin.getConfig().getBoolean("bedrock-support", false) && plugin.getBedrockFormHandler().isBedrockPlayer(player)) {
                plugin.getBedrockFormHandler().sendConfirmationForm(player, amount, email, method, messageStr, false, false);
                return true;
            }

            // Generate MD5 Hash for this specific request
            long timestamp = System.currentTimeMillis();
            String rawString = player.getUniqueId().toString() + "-" + timestamp + "-" + amount + "-" + email + "-" + method;
            String hash = md5(rawString);

            if (hash == null) {
                plugin.sendLangMessage(player, "general-error");
                return true;
            }

            // Clean up previous requests from this player to avoid clutter
            pendingRequests.entrySet().removeIf(entry -> entry.getValue().playerUuid().equals(player.getUniqueId()));

            pendingRequests.put(hash, new DonationRequest(player.getUniqueId(), amount, email, method, messageStr));

            Map<String, String> p = plugin.getDonationPlaceholders(player.getName(), amount, email, method, messageStr);
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            p.put("{COMMAND}", "/" + label + " " + hash);

            plugin.sendLangMessageList(player, "donation-confirmation-java", p);
            plugin.playConfigSounds(player, "sound-effects.donation-confirmation");
        }

        return true;
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

    public static void processDonation(Player player, double amount, String email, String method, String message, PlsDonate plugin) {
        // Sound: donation-processed
        plugin.playConfigSounds(player, "sound-effects.donation-processed");

        plugin.getDonationPlatform().createDonation(player.getName(), email, amount, method, message).thenAccept(response -> {
            if (response.success()) {
                // Log request to ledger to prevent replay
                if (response.transactionId() != null) {
                    plugin.getStorageManager().createDonationRequest(response.transactionId(), amount, player.getName(), false);

                }

                // Send Email to Bedrock Player
                plugin.getEmailManager().sendPaymentEmail(
                        player.getName(),
                        email,
                        amount,
                        plugin.formatIndonesianNumber(amount),
                        method,
                        response.paymentUrl(),
                        message
                );

                Bukkit.getScheduler().runTask(plugin, () -> {
                    Map<String, String> succP = new HashMap<>();
                    succP.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                    player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("donation-email-sent", "{PREFIX} <green>A payment link has been sent to your email!</green>"), succP));
                    plugin.playConfigSounds(player, "sound-effects.donation-success");
                });
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Map<String, String> errP = new HashMap<>();
                    errP.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                    errP.put("{ERROR}", response.message());
                    player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("api-error", "{PREFIX} <red>API Error: {ERROR}</red>"), errP));
                });
            }
        });
    }

    public void clearPendingRequests(UUID uuid) {
        pendingRequests.entrySet().removeIf(entry -> entry.getValue().playerUuid().equals(uuid));
        cooldowns.remove(uuid);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            String sub = args[0].toLowerCase();
            if ("help".startsWith(sub)) completions.add("help");
            
            double min = plugin.getConfig().getDouble("donate.amount.min", 1000);
            long[] suggestions = { (long) min, (long) min + 5000, (long) min + 10000 };
            for (long s : suggestions) {
                if (String.valueOf(s).startsWith(sub)) completions.add(String.valueOf(s));
            }
        } else if (args.length == 2) {
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

        return completions;
    }
}
