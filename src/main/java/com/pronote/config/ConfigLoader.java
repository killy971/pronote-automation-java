package com.pronote.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads and validates config.yaml into an {@link AppConfig} instance.
 * Fails fast on missing required fields.
 */
public class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    private ConfigLoader() {}

    /**
     * Loads configuration from the given YAML file path.
     *
     * @param configPath path to config.yaml
     * @return validated AppConfig
     * @throws ConfigException if the file cannot be read or required fields are missing
     */
    public static AppConfig load(Path configPath) {
        log.info("Loading configuration from {}", configPath.toAbsolutePath());

        if (!Files.exists(configPath)) {
            throw new ConfigException("Configuration file not found: " + configPath.toAbsolutePath()
                    + " — copy config.yaml.example to config.yaml and fill in your values.");
        }

        AppConfig config;
        try (InputStream is = Files.newInputStream(configPath)) {
            LoaderOptions opts = new LoaderOptions();
            Yaml yaml = new Yaml(new Constructor(AppConfig.class, opts));
            config = yaml.load(is);
        } catch (IOException e) {
            throw new ConfigException("Failed to read configuration file: " + e.getMessage(), e);
        }

        if (config == null) {
            throw new ConfigException("Configuration file is empty.");
        }

        // Override password from environment variable if set
        String envPassword = System.getenv("PRONOTE_PASSWORD");
        if (envPassword != null && !envPassword.isBlank()) {
            log.debug("Using PRONOTE_PASSWORD from environment variable");
            config.getPronote().setPassword(envPassword);
        }

        validate(config);
        log.info("Configuration loaded successfully (baseUrl={}, loginMode={})",
                config.getPronote().getBaseUrl(), config.getPronote().getLoginMode());
        return config;
    }

    private static void validate(AppConfig config) {
        List<String> errors = new ArrayList<>();

        // Pronote section
        if (isBlank(config.getPronote().getBaseUrl())) {
            errors.add("pronote.baseUrl is required");
        }
        if (isBlank(config.getPronote().getUsername())) {
            errors.add("pronote.username is required");
        }
        if (isBlank(config.getPronote().getPassword())) {
            errors.add("pronote.password is required (or set PRONOTE_PASSWORD env var)");
        }

        // Notification sections
        AppConfig.NtfyConfig ntfy = config.getNotifications().getNtfy();
        if (ntfy.isEnabled() && isBlank(ntfy.getTopic())) {
            errors.add("notifications.ntfy.topic is required when ntfy is enabled");
        }

        AppConfig.EmailConfig email = config.getNotifications().getEmail();
        if (email.isEnabled()) {
            if (isBlank(email.getSmtpHost())) errors.add("notifications.email.smtpHost is required when email is enabled");
            if (isBlank(email.getUsername())) errors.add("notifications.email.username is required when email is enabled");
            if (isBlank(email.getPassword())) errors.add("notifications.email.password is required when email is enabled");
            if (isBlank(email.getFrom()))     errors.add("notifications.email.from is required when email is enabled");
            if (isBlank(email.getTo()))       errors.add("notifications.email.to is required when email is enabled");
        }

        if (!errors.isEmpty()) {
            throw new ConfigException("Configuration validation failed:\n  - " + String.join("\n  - ", errors));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // -------------------------------------------------------------------------

    public static class ConfigException extends RuntimeException {
        public ConfigException(String message) { super(message); }
        public ConfigException(String message, Throwable cause) { super(message, cause); }
    }
}
