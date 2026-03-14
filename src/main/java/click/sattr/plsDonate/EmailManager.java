package click.sattr.plsDonate;

import click.sattr.plsDonate.platform.DonationPlatform;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

public class EmailManager {

    private final PlsDonate plugin;

    public EmailManager(PlsDonate plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        // No persistent sessions anymore; handled per request.
    }

    public void sendPaymentEmail(String player, String emailAddress, double amount, String formattedAmount, String link, String messageParam) {
        ConfigurationSection hostsSection = plugin.getConfig().getConfigurationSection("email.hosts");
        if (hostsSection == null || hostsSection.getKeys(false).isEmpty()) {
            plugin.getLogger().warning("Email hosts not configured in config.yml! Cannot send email to " + player);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> hostKeys = new ArrayList<>(hostsSection.getKeys(false));
            // Sort by priority (1, 2, 3...)
            hostKeys.sort(Comparator.comparingInt(this::parseIntSafely));

            Exception lastException = null;
            boolean invalidRecipient = false;
            String subject = plugin.getConfig().getString("email.subject", "Your Donation Payment Link - plsDonate");

            for (String key : hostKeys) {
                ConfigurationSection hostConfig = hostsSection.getConfigurationSection(key);
                if (hostConfig == null) continue;

                // Stop processing dummy configs
                if ("smtp.mailgun.org".equals(hostConfig.getString("host", "")) && "postmaster@yourdomain.com".equals(hostConfig.getString("user", ""))) {
                    continue;
                }

                try {
                    sendUsingHost(hostConfig, emailAddress, player, amount, formattedAmount, link, subject, messageParam);
                    plugin.getLogger().info("Successfully sent payment email to " + emailAddress + " for player " + player + " using host " + key);
                    return; // Success, exit loop
                } catch (Exception e) {
                    lastException = e;
                    plugin.getLogger().warning("Failed to send email using host " + key + ": " + e.getMessage());

                    // Check if error is due to an invalid recipient (like a 550 error)
                    // No need to try other hosts if the email itself is fake.
                    String msg = e.getMessage();
                    if (msg != null && (msg.contains("550") || msg.contains("Invalid recipient") || msg.contains("User unknown") || msg.contains("does not exist"))) {
                        invalidRecipient = true;
                        break; // Stop trying other hosts
                    }
                }
            }

            if (invalidRecipient) {
                plugin.getLogger().severe("Email address '" + emailAddress + "' appears to be invalid or does not exist (Recipient Rejected).");
            } else if (lastException != null) {
                plugin.getLogger().severe("Failed to send email after trying all available hosts.");
                lastException.printStackTrace();
            }
        });
    }

    private void sendUsingHost(ConfigurationSection hostConfig, String toAddress, String player, double amount, String formattedAmount, String link, String subject, String messageParam) throws Exception {
        String host = hostConfig.getString("host", "");
        int port = hostConfig.getInt("port", 587);
        String username = hostConfig.getString("user", "");
        String password = hostConfig.getString("pass", "");
        boolean secure = hostConfig.getBoolean("secure", false);
        // If from.email is empty or missing, fallback to the username/user.
        String fromEmail = hostConfig.getString("from.email", username);
        if (fromEmail == null || fromEmail.isBlank()) {
            fromEmail = username;
        }
        
        // Use from.name, fallback to global email.from-name
        String globalFromName = plugin.getConfig().getString("email.from-name", "plsDonate");
        String fromName = hostConfig.getString("from.name", globalFromName);

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        
        if (secure) {
            props.put("mail.smtp.ssl.enable", "true");
            
            // Allow all SSL certs to mimic old behavior
            com.sun.mail.util.MailSSLSocketFactory sf = new com.sun.mail.util.MailSSLSocketFactory();
            sf.setTrustAllHosts(true);
            props.put("mail.smtp.ssl.socketFactory", sf);
        } else {
            props.put("mail.smtp.starttls.enable", "true");
        }

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });

        Message message = new MimeMessage(session);
        
        // We now explicitly set the name and address separately
        message.setFrom(new InternetAddress(fromEmail, fromName));
        
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toAddress));
        message.setSubject(subject);

        // Load and format HTML
        String htmlContent = loadTemplate();
        DonationPlatform platform = plugin.getDonationPlatform();
        htmlContent = htmlContent.replace("{PLAYER}", player)
                                 .replace("{AMOUNT}", String.valueOf((long) amount))
                                 .replace("{AMOUNT_FORMATTED}", formattedAmount)
                                 .replace("{LINK}", link)
                                 .replace("{MESSAGE}", messageParam != null && !messageParam.isEmpty() ? messageParam : "-")

                                 .replace("{PLATFORM_NAME}", platform.getPlatformName())
                                 .replace("{PLATFORM_URL}", platform.getPlatformUrl());

        message.setContent(htmlContent, "text/html; charset=utf-8");

        Transport.send(message);
    }

    private String loadTemplate() throws IOException {
        String templateName = plugin.getConfig().getString("email.template", "payment.html");
        File templateFile = new File(plugin.getDataFolder(), "templates/" + templateName);
        if (!templateFile.exists()) {
            return "<p>Hi <b>{PLAYER}</b>,</p><p>Please pay <b>Rp{AMOUNT_FORMATTED}</b> here: <a href=\"{LINK}\">{LINK}</a></p>";
        }
        return new String(Files.readAllBytes(templateFile.toPath()));
    }

    private int parseIntSafely(String val) {
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return 999;
        }
    }
}
