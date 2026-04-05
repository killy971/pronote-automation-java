package com.pronote.notification;

import com.pronote.config.AppConfig;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Sends email notifications via SMTP with STARTTLS.
 *
 * <p>Supports Gmail App Passwords, standard SMTP relays, and any
 * STARTTLS-capable provider on port 587.
 */
public class EmailNotifier implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifier.class);

    private final AppConfig.EmailConfig config;

    public EmailNotifier(AppConfig.EmailConfig config) {
        this.config = config;
    }

    @Override
    public void send(NotificationPayload payload) throws NotificationException {
        Properties props = buildSmtpProperties();
        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.getUsername(), config.getPassword());
            }
        });

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(config.getFrom()));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(config.getTo()));
            message.setSubject(payload.title());

            // Build a simple HTML body
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(buildHtmlBody(payload), "text/html; charset=UTF-8");
            Multipart multipart = new MimeMultipart();
            multipart.addBodyPart(htmlPart);
            message.setContent(multipart);

            log.debug("Sending email from {} to {}", config.getFrom(), config.getTo());
            Transport.send(message);
            log.info("Email notification sent (subject: '{}')", payload.title());

        } catch (MessagingException e) {
            throw new NotificationException("Failed to send email: " + e.getMessage(), e);
        }
    }

    private Properties buildSmtpProperties() {
        Properties props = new Properties();
        props.put("mail.smtp.host", config.getSmtpHost());
        props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        return props;
    }

    private static String buildHtmlBody(NotificationPayload payload) {
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"/></head>
                <body style="font-family: Arial, sans-serif; font-size: 14px; color: #333;">
                  <h2 style="color: #2c3e50;">%s</h2>
                  <pre style="background: #f4f4f4; padding: 12px; border-radius: 4px; white-space: pre-wrap;">%s</pre>
                  <p style="font-size: 11px; color: #999;">Sent by pronote-automation</p>
                </body>
                </html>
                """.formatted(
                escapeHtml(payload.title()),
                escapeHtml(payload.body()));
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
