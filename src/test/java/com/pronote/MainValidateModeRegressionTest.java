package com.pronote;

import com.pronote.config.ConfigLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static com.pronote.PipelineTestHarness.lesson;
import static com.pronote.PipelineTestHarness.upcomingWeekday;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end regression tests for {@code --mode validate} (and mode dispatch itself):
 * validate must parse {@code manual-entries.yaml}, report, and produce zero side effects —
 * no snapshots, no diff report, no views, no network.
 */
class MainValidateModeRegressionTest {

    @TempDir
    Path tempDir;

    private PipelineTestHarness env;
    private String log;

    private final LocalDate day1 = upcomingWeekday(1);

    @BeforeEach
    void setUp() {
        env = new PipelineTestHarness(tempDir);
        env.writeConfig();
    }

    @AfterEach
    void dump(TestInfo info) {
        env.dumpOutputs("validate/" + info.getTestMethod().orElseThrow().getName(), log);
    }

    private void runValidate() {
        try (PipelineTestHarness.LogCapture logs = new PipelineTestHarness.LogCapture()) {
            env.runMain("--mode", "validate");
            log = String.join("\n", logs.messages().stream()
                    .filter(m -> !m.startsWith("Loading configuration") && !m.startsWith("Validating manual"))
                    .toList());
        }
    }

    @Test
    void validEntries_reportSuccessWithoutSideEffects() {
        env.seedLatest("timetable", List.of(
                lesson("syn-t1", "SYN_MATHS", "DURAND A.", "B12",
                        day1.atTime(10, 0), day1.atTime(11, 0))));
        env.writeManualEntries("""
                assignments:
                  - subject: "SYN_MATHS"
                    description: "Exercices synthétiques"
                    dueDate: "%s"
                evaluations:
                  - subject: "SYN_MATHS"
                    name: "DS synthétique"
                    date: "%s"
                """.formatted(day1, day1));

        runValidate();
        assertTrue(log.contains("Manual entries valid."), log);

        assertFalse(Files.exists(env.dataDir.resolve("diff-latest.json")),
                "validate must not write a diff report");
        assertFalse(Files.exists(env.timetableViewDir), "validate must not generate views");
    }

    @Test
    void unknownSubject_producesWarningNotFailure() {
        env.seedLatest("timetable", List.of(
                lesson("syn-t1", "SYN_MATHS", "DURAND A.", "B12",
                        day1.atTime(10, 0), day1.atTime(11, 0))));
        env.writeManualEntries("""
                assignments:
                  - subject: "SYN_MATSH"
                    description: "Subject contains a typo"
                    dueDate: "%s"
                """.formatted(day1));

        runValidate();
        assertTrue(log.contains("warning(s)"),
                "a subject absent from the timetable snapshot must be flagged");
    }

    @Test
    void missingAttachmentFile_producesWarning() {
        env.writeManualAttachment("present.png", "synthetic");
        env.writeManualEntries("""
                assignments:
                  - id: devoir-photo
                    subject: "SYN_MATHS"
                    description: "Voir les photos"
                    dueDate: "%s"
                    attachments:
                      - present.png
                      - absent.png
                """.formatted(day1));

        runValidate();
        assertTrue(log.contains("absent.png") && log.contains("file not found"), log);
        assertTrue(log.contains("1 warning(s)"), log);
        assertFalse(Files.exists(env.attachmentsDir()), "validate must not stage attachments");
    }

    @Test
    void missingRequiredField_failsFast() {
        env.writeManualEntries("""
                evaluations:
                  - subject: "SYN_MATHS"
                    name: "DS sans date"
                """);

        ConfigLoader.ConfigException ex = assertThrows(ConfigLoader.ConfigException.class,
                () -> env.runMain("--mode", "validate"));
        assertTrue(ex.getMessage().contains("date"), ex.getMessage());
    }

    @Test
    void absentManualEntriesFile_isFine() {
        assertDoesNotThrow(() -> env.runMain("--mode", "validate"));
    }

    @Test
    void offlineModesDoNotRequirePronoteCredentials() {
        env.includeCredentials = false;
        env.writeConfig();
        env.seedLatest("timetable", List.of(
                lesson("syn-t1", "SYN_MATHS", "DURAND A.", "B12", day1.atTime(10, 0), day1.atTime(11, 0))));
        env.seedLatest("assignments", List.of());

        assertDoesNotThrow(() -> env.runMain("--mode", "validate"));
        assertDoesNotThrow(() -> env.runMain("--mode", "views"));
        assertDoesNotThrow(() -> env.runMain("--mode", "diff", "--dry-run"));
    }

    @Test
    void fetchModeStillRequiresPronoteCredentials() {
        // Fails in config validation — before any lockout, session or network step.
        env.includeCredentials = false;
        env.writeConfig();

        ConfigLoader.ConfigException ex = assertThrows(ConfigLoader.ConfigException.class,
                () -> env.runMain("--mode", "fetch"));
        assertTrue(ex.getMessage().contains("pronote.baseUrl"), ex.getMessage());
        assertFalse(Files.exists(env.dataDir.resolve("lockout.json")));
    }

    @Test
    void unknownMode_failsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> env.runMain("--mode", "bogus"));
        assertTrue(ex.getMessage().contains("bogus"), ex.getMessage());
    }

    @Test
    void unknownFeatureName_failsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> env.runMain("--mode", "validate", "--features", "homework"));
    }
}
