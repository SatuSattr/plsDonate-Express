package click.sattr.plsDonate.util;

import click.sattr.plsDonate.PlsDonate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MessageUtils {

    private MessageUtils() {} // Prevent instantiation

    public static Component parseMessage(String string, Map<String, String> placeholders) {
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                string = string.replace(entry.getKey(), entry.getValue());
            }
        }
        return parseMessage(string);
    }

    public static Component parseMessage(String string) {
        return MiniMessage.miniMessage().deserialize(string);
    }

    public static String toLegacy(String miniMessage) {
        return LegacyComponentSerializer.legacySection().serialize(parseMessage(miniMessage));
    }

    public static void sendLangMessage(CommandSender sender, PlsDonate plugin, String path, Map<String, String> placeholders) {
        String prefix = plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX);
        String message = plugin.getLangConfig().getString(path, "<red>Missing translation for: " + path + "</red>");

        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                message = message.replace(entry.getKey(), entry.getValue());
            }
        }
        message = message.replace(Constants.PREFIX, prefix);

        sender.sendMessage(parseMessage(message));
    }

    public static void sendLangMessageList(CommandSender sender, PlsDonate plugin, String path, Map<String, String> placeholders) {
        if (!plugin.getLangConfig().contains(path)) {
            sender.sendMessage(parseMessage("<red>Missing list translation for: " + path + "</red>"));
            return;
        }

        String prefix = plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX);
        List<String> list = plugin.getLangConfig().getStringList(path);

        for (String line : list) {
            String processedLine = line;
            if (placeholders != null) {
                for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                    processedLine = processedLine.replace(entry.getKey(), entry.getValue());
                }
            }
            processedLine = processedLine.replace(Constants.PREFIX, prefix);
            sender.sendMessage(parseMessage(processedLine));
        }
    }

    public static void playConfigSounds(Player player, PlsDonate plugin, String path) {
        List<String> soundStrings = plugin.getConfig().getStringList(path);
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
                plugin.getLogger().warning("Could not play sound '" + soundName + "': " + e.getMessage());
            }
        }
    }

    public static String formatIndonesianNumber(double number) {
        NumberFormat nf = NumberFormat.getInstance(Locale.forLanguageTag("id-ID"));
        return nf.format(number);
    }

    public static Map<String, String> getDonationPlaceholders(double amount, String donorName, String email, String method, String message) {
        Map<String, String> p = new HashMap<>();
        p.put(Constants.PLAYER, donorName);
        p.put("{PLAYER_UPPERCASED}", donorName.toUpperCase());
        p.put("{PLAYER_LOWERCASED}", donorName.toLowerCase());
        p.put(Constants.AMOUNT, String.valueOf((long) amount));
        p.put(Constants.AMOUNT_FORMATTED, formatIndonesianNumber(amount));
        p.put(Constants.EMAIL, email != null ? email : "");
        
        String msg = (message == null || message.isEmpty()) ? "No message" : message;
        p.put(Constants.MESSAGE, msg);
        p.put("{MESSAGE_LOWERCASED}", msg.toLowerCase());
        p.put("{MESSAGE_UPPERCASED}", msg.toUpperCase());

        String baseLabel = "QRIS";
        String colorCode = "#ED1A3D";
        if (method != null) {
            String m = method.toLowerCase();
            if (m.equals("gopay")) { baseLabel = "GoPay"; colorCode = "#01AED6"; }
            else if (m.equals("paypal")) { baseLabel = "PayPal"; colorCode = "#195ef7"; }
        }

        p.put(Constants.METHOD, baseLabel);
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
}
