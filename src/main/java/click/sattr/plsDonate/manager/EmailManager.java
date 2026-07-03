package click.sattr.plsDonate.manager;

import click.sattr.plsDonate.PlsDonate;
import click.sattr.plsDonate.util.MessageUtils;
import click.sattr.plsDonate.util.PluginLogger;
import org.bukkit.Bukkit;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class EmailManager {

    private final PlsDonate plugin;

    public EmailManager(PlsDonate plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        // No persistent sessions anymore; handled per request.
    }

    public void sendPaymentEmail(String player, String emailAddress, double amount, String formattedAmount, String methodParam, String link, String messageParam) {
        List<Map<?, ?>> hostsList = plugin.getConfig().getMapList("email.hosts");
        if (hostsList == null || hostsList.isEmpty()) {
            PluginLogger.warn("Email hosts not configured in config.yml! Cannot send email to " + player);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Exception lastException = null;
            boolean invalidRecipient = false;
            String subject = plugin.getConfig().getString("email.subject", "Your Donation Payment Link - plsDonate");

            for (Map<?, ?> hostMap : hostsList) {
                if (hostMap == null) continue;

                String hostId = String.valueOf(hostMap.get("host"));
                if ("smtp.mailgun.org".equals(hostId) && "postmaster@yourdomain.com".equals(String.valueOf(hostMap.get("user")))) {
                    continue;
                }

                try {
                    sendUsingHost(hostMap, emailAddress, player, amount, formattedAmount, methodParam, link, subject, messageParam);
                    PluginLogger.info("Successfully sent payment email for " + player + " (" + emailAddress + ")");
                    return;
                } catch (Exception e) {
                    lastException = e;
                    PluginLogger.warn("Failed to send email for " + player + " using host " + hostId + ": " + e.getMessage());

                    String msg = e.getMessage();
                    if (msg != null && (msg.contains("550") || msg.contains("Invalid recipient") || msg.contains("User unknown") || msg.contains("does not exist"))) {
                        invalidRecipient = true;
                        break;
                    }
                }
            }

            if (invalidRecipient) {
                PluginLogger.severe("Email address '" + emailAddress + "' appears to be invalid or does not exist (Recipient Rejected).");
            } else if (lastException != null) {
                PluginLogger.severe("Failed to send email after trying all available hosts.");
                lastException.printStackTrace();
            }
        });
    }

    private void sendUsingHost(Map<?, ?> hostMap, String toAddress, String player, double amount, String formattedAmount, String method, String link, String subject, String messageParam) throws Exception {
        String hostHost = String.valueOf(hostMap.get("host"));
        int port = toInt(hostMap.get("port"));
        String username = String.valueOf(hostMap.get("user"));
        String password = String.valueOf(hostMap.get("pass"));
        boolean secure = toBoolean(hostMap.get("secure"));

        String globalFromName = plugin.getConfig().getString("email.from-name", "plsDonate");
        String fromEmail = username;
        String fromName = globalFromName;
        Object fromObj = hostMap.get("from");
        if (fromObj instanceof Map) {
            Map<?, ?> fromMap = (Map<?, ?>) fromObj;
            String customFromEmail = String.valueOf(fromMap.get("email"));
            if (!customFromEmail.equals("null") && !customFromEmail.isBlank()) {
                fromEmail = customFromEmail;
            }
            String customFromName = String.valueOf(fromMap.get("name"));
            if (customFromName != null && !customFromName.isBlank()) {
                fromName = customFromName;
            }
        }

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.host", hostHost);
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

        // Load and format HTML from classpath resource (bundled in JAR)
        String htmlContent = loadTemplate();
        String methodDisplay = MessageUtils.friendlyMethod(plugin.getLangConfig(), method);

        htmlContent = htmlContent.replace("{PLAYER}", player)
                                 .replace("{PLAYER_UPPERCASED}", player.toUpperCase())
                                 .replace("{PLAYER_LOWERCASED}", player.toLowerCase())
                                 .replace("{AMOUNT}", String.valueOf((long) amount))
                                 .replace("{AMOUNT_FORMATTED}", formattedAmount)
                                 .replace("{METHOD}", methodDisplay)
                                 .replace("{METHOD_LOWERCASED}", methodDisplay.toLowerCase())
                                 .replace("{METHOD_UPPERCASED}", methodDisplay.toUpperCase())
                                 .replace("{LINK}", link)
                                 .replace("{MESSAGE}", messageParam != null && !messageParam.isEmpty() ? messageParam : "-");

        message.setContent(htmlContent, "text/html; charset=utf-8");

        Transport.send(message);
    }

    private String loadTemplate() throws IOException {
        String templateName = plugin.getConfig().getString("email.body-template", "payment.html");
        if (templateName == null || templateName.trim().isEmpty()) {
            templateName = "payment.html";
        }

        // Enforce .html extension
        if (!templateName.toLowerCase().endsWith(".html")) {
            PluginLogger.warn("Email template '" + templateName + "' in config.yml does not end with .html! Falling back to payment.html");
            templateName = "payment.html";
        }

        File templateFile = new File(plugin.getDataFolder(), "templates/" + templateName);
        if (templateFile.exists()) {
            return java.nio.file.Files.readString(templateFile.toPath(), StandardCharsets.UTF_8);
        }

        // Fallback to JAR resource if file doesn't exist in data folder
        try (InputStream is = plugin.getResource("templates/" + templateName)) {
            if (is == null) {
                // Last ditch effort: try the default payment.html if they specified a non-existent file
                try (InputStream defaultIs = plugin.getResource("templates/payment.html")) {
                    if (defaultIs == null) {
                        return "<p>Hi <b>{PLAYER}</b>,</p><p>Please pay <b>{AMOUNT_FORMATTED}</b> here: <a href=\"{LINK}\">{LINK}</a></p>";
                    }
                    return new String(defaultIs.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private int toInt(Object obj) {
        if (obj instanceof Number) return ((Number) obj).intValue();
        if (obj instanceof String) {
            try {
                return Integer.parseInt((String) obj);
            } catch (NumberFormatException ignored) {}
        }
        return 587;
    }

    private boolean toBoolean(Object obj) {
        if (obj instanceof Boolean) return (Boolean) obj;
        return Boolean.parseBoolean(String.valueOf(obj));
    }
}
