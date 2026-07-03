package click.sattr.plsDonate.util;

import click.sattr.plsDonate.PlsDonate;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class DonationValidator {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$");
    public static final Pattern MD5_PATTERN = Pattern.compile("^[a-fA-F0-9]{32}$");

    private DonationValidator() {}

    @Nullable
    public static Double parseAmount(String raw, Player player, PlsDonate plugin) {
        try {
            double amount = Double.parseDouble(raw);
            if (!Double.isFinite(amount)) throw new NumberFormatException("non-finite amount");
            return amount;
        } catch (NumberFormatException e) {
            Map<String, String> p = new HashMap<>();
            p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
            player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("invalid-amount", "{PREFIX} <white>Please <red>enter a valid amount <white>using numbers only <gray>(example: 50000)"), p));
            return null;
        }
    }

    public static boolean validateEmail(String email, Player player, PlsDonate plugin) {
        if (EMAIL_PATTERN.matcher(email).matches() && email.length() <= 64) return true;
        Map<String, String> p = new HashMap<>();
        p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
        player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("invalid-email", "{PREFIX} <white>Please <red>provide <white>a valid email <gray>example: (your@gmail.com)"), p));
        return false;
    }

    public static boolean validateMethod(String method, double amount, Player player, PlsDonate plugin) {
        if (!method.equals("qris") && !method.equals("gopay") && !method.equals("paypal")) {
            Map<String, String> p = new HashMap<>();
            p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
            player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("invalid-payment-method", "{PREFIX} <red>Invalid payment method! <yellow>Options: qris, gopay, paypal"), p));
            return false;
        }

        if (method.equals("gopay") && amount < 10000) {
            Map<String, String> p = new HashMap<>();
            p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
            p.put(Constants.METHOD, method);
            p.put("{METHOD_UPPERCASED}", method.toUpperCase());
            p.put(Constants.AMOUNT_FORMATTED, MessageUtils.formatAmount(plugin, 10000));
            player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("payment-method-min-error", "{PREFIX} <red>Minimum donation for {METHOD_UPPERCASED} is <yellow>Rp{AMOUNT_FORMATTED}"), p));
            return false;
        }

        if (method.equals("paypal") && amount < 50000) {
            Map<String, String> p = new HashMap<>();
            p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
            p.put(Constants.METHOD, method);
            p.put("{METHOD_UPPERCASED}", method.toUpperCase());
            p.put(Constants.AMOUNT_FORMATTED, MessageUtils.formatAmount(plugin, 50000));
            player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("payment-method-min-error", "{PREFIX} <red>Minimum donation for {METHOD_UPPERCASED} is <yellow>Rp{AMOUNT_FORMATTED}"), p));
            return false;
        }

        return true;
    }

    public static String buildMessage(String[] args, int fromIndex) {
        if (args.length <= fromIndex) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = fromIndex; i < args.length; i++) {
            sb.append(args[i]).append(" ");
        }
        return sb.toString().trim();
    }

    public static boolean validateMessageLength(String message, Player player, PlsDonate plugin) {
        int configMaxMsgLen = plugin.getConfig().getInt(Constants.CONF_DONATE_MAX_MESSAGE, 255);
        int maxMsgLen = Math.min(configMaxMsgLen, plugin.getDonationPlatform().getMaxMessageLength());

        if (message.length() <= maxMsgLen) return true;
        Map<String, String> p = new HashMap<>();
        p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
        p.put("{LIMIT}", String.valueOf(maxMsgLen));
        player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("message-length-error", "{PREFIX} <white>Sorry, <red>maximal length <white>of the message is <yellow>{LIMIT} Character. <white>Please shorten your message."), p));
        return false;
    }
}
