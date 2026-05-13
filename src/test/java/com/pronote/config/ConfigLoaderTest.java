package com.pronote.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    private static final String MINIMAL_YAML = """
            pronote:
              baseUrl: https://example.invalid/test
              username: synthetic_user
              password: synthetic_password
            """;

    private static Path writeConfig(Path dir, String content) throws IOException {
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void load_missingFile_throwsConfigException() {
        Path missing = Path.of("/tmp/this-file-definitely-does-not-exist-pronote.yaml");
        ConfigLoader.ConfigException ex = assertThrows(
                ConfigLoader.ConfigException.class, () -> ConfigLoader.load(missing));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void load_emptyFile_throwsConfigException(@TempDir Path dir) throws IOException {
        Path file = writeConfig(dir, "");
        ConfigLoader.ConfigException ex = assertThrows(
                ConfigLoader.ConfigException.class, () -> ConfigLoader.load(file));
        assertTrue(ex.getMessage().contains("empty"));
    }

    @Test
    void load_minimal_succeedsAndAppliesDefaults(@TempDir Path dir) throws IOException {
        Path file = writeConfig(dir, MINIMAL_YAML);
        AppConfig cfg = ConfigLoader.load(file);

        assertEquals("https://example.invalid/test", cfg.getPronote().getBaseUrl());
        assertEquals("synthetic_user", cfg.getPronote().getUsername());
        // Defaults
        assertEquals("./data",  cfg.getData().getDirectory());
        assertEquals(AppConfig.LoginMode.PARENT, cfg.getPronote().getLoginMode());
        assertEquals(2000L, cfg.getSafety().getMinDelayMs());
        assertEquals(3, cfg.getSafety().getMaxLoginFailures());
        // notifications default to disabled
        assertFalse(cfg.getNotifications().getNtfy().isEnabled());
        assertFalse(cfg.getNotifications().getEmail().isEnabled());
    }

    @Test
    void load_missingBaseUrl_failsValidation(@TempDir Path dir) throws IOException {
        Path file = writeConfig(dir, """
                pronote:
                  username: synthetic_user
                  password: synthetic_password
                """);
        ConfigLoader.ConfigException ex = assertThrows(
                ConfigLoader.ConfigException.class, () -> ConfigLoader.load(file));
        assertTrue(ex.getMessage().contains("pronote.baseUrl"));
    }

    @Test
    void load_missingUsername_failsValidation(@TempDir Path dir) throws IOException {
        Path file = writeConfig(dir, """
                pronote:
                  baseUrl: https://example.invalid/test
                  password: synthetic_password
                """);
        ConfigLoader.ConfigException ex = assertThrows(
                ConfigLoader.ConfigException.class, () -> ConfigLoader.load(file));
        assertTrue(ex.getMessage().contains("pronote.username"));
    }

    @Test
    void load_missingPassword_failsValidation(@TempDir Path dir) throws IOException {
        Path file = writeConfig(dir, """
                pronote:
                  baseUrl: https://example.invalid/test
                  username: synthetic_user
                """);
        ConfigLoader.ConfigException ex = assertThrows(
                ConfigLoader.ConfigException.class, () -> ConfigLoader.load(file));
        assertTrue(ex.getMessage().contains("pronote.password"));
    }

    @Test
    void load_ntfyEnabledWithoutTopic_failsValidation(@TempDir Path dir) throws IOException {
        Path file = writeConfig(dir, MINIMAL_YAML + """
                notifications:
                  ntfy:
                    enabled: true
                """);
        ConfigLoader.ConfigException ex = assertThrows(
                ConfigLoader.ConfigException.class, () -> ConfigLoader.load(file));
        assertTrue(ex.getMessage().contains("notifications.ntfy.topic"));
    }

    @Test
    void load_emailEnabledMissingFields_failsValidationWithAllErrors(@TempDir Path dir) throws IOException {
        Path file = writeConfig(dir, MINIMAL_YAML + """
                notifications:
                  email:
                    enabled: true
                """);
        ConfigLoader.ConfigException ex = assertThrows(
                ConfigLoader.ConfigException.class, () -> ConfigLoader.load(file));
        String msg = ex.getMessage();
        // All five required email fields should be reported together
        assertTrue(msg.contains("smtpHost"));
        assertTrue(msg.contains("username"));
        assertTrue(msg.contains("password"));
        assertTrue(msg.contains("from"));
        assertTrue(msg.contains("to"));
    }

    @Test
    void load_optionalSectionsParseCorrectly(@TempDir Path dir) throws IOException {
        Path file = writeConfig(dir, MINIMAL_YAML + """
                features:
                  assignments: false
                  timetable: true
                  grades: true
                pronote:
                  weeksAhead: 4
                  group: SYNTH GROUP A
                """);
        // SnakeYAML merges duplicate top-level keys by overwriting, so write it differently:
        Files.writeString(file, """
                pronote:
                  baseUrl: https://example.invalid/test
                  username: synthetic_user
                  password: synthetic_password
                  weeksAhead: 4
                  group: SYNTH GROUP A
                features:
                  assignments: false
                  timetable: true
                  grades: true
                """);
        AppConfig cfg = ConfigLoader.load(file);
        assertEquals(4, cfg.getPronote().getWeeksAhead());
        assertEquals("SYNTH GROUP A", cfg.getPronote().getGroup());
        assertFalse(cfg.getFeatures().isAssignments());
        assertTrue(cfg.getFeatures().isTimetable());
        assertTrue(cfg.getFeatures().isGrades());
    }
}
