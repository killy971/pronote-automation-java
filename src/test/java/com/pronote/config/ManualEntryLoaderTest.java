package com.pronote.config;

import com.pronote.domain.Assignment;
import com.pronote.domain.TimetableEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link ManualEntryLoader} parsing, ID generation (legacy + explicit),
 * required-field validation, and the manual-entry conventions that the merge/dedup
 * logic in {@code Main.runViews} depends on (every manual ID must start with
 * {@code "manual:"} so {@code removeIf(id.startsWith("manual:"))} catches it).
 */
class ManualEntryLoaderTest {

    private static SubjectEnricher enricher() {
        return new SubjectEnricher(new AppConfig.SubjectEnrichmentConfig());
    }

    private static Path writeYaml(Path dir, String content) throws IOException {
        Path file = dir.resolve("manual-entries.yaml");
        Files.writeString(file, content);
        return file;
    }

    // -------------------------------------------------------------------------
    // File-level behaviour
    // -------------------------------------------------------------------------

    @Test
    void missingFile_returnsEmptyResult(@TempDir Path dir) {
        Path absent = dir.resolve("does-not-exist.yaml");
        ManualEntryLoader.ManualEntries result = ManualEntryLoader.load(absent, enricher());
        assertTrue(result.getAssignments().isEmpty());
        assertTrue(result.getUpcomingEvals().isEmpty());
    }

    @Test
    void emptyFile_returnsEmptyResult(@TempDir Path dir) throws IOException {
        Path file = writeYaml(dir, "");
        ManualEntryLoader.ManualEntries result = ManualEntryLoader.load(file, enricher());
        assertTrue(result.getAssignments().isEmpty());
        assertTrue(result.getUpcomingEvals().isEmpty());
    }

    // -------------------------------------------------------------------------
    // Assignment parsing
    // -------------------------------------------------------------------------

    @Test
    void assignment_withOnlyRequiredFields_parsesCorrectly(@TempDir Path dir) throws IOException {
        Path file = writeYaml(dir, """
                assignments:
                  - subject: MATHEMATIQUES
                    description: DS fractions
                    dueDate: '2026-05-15'
                """);

        List<Assignment> as = ManualEntryLoader.load(file, enricher()).getAssignments();
        assertEquals(1, as.size());
        Assignment a = as.get(0);
        assertEquals("MATHEMATIQUES", a.getSubject());
        assertEquals("DS fractions", a.getDescription());
        assertEquals(LocalDate.of(2026, 5, 15), a.getDueDate());
        assertNull(a.getAssignedDate(),
                "assignedDate must be null when omitted — prevents spurious 'Nouveau' badge on all manual entries");
        assertFalse(a.isDone(), "done defaults to false");
        assertNull(a.getTeacher());
    }

    @Test
    void assignment_withAllOptionalFields_parsesAndAppliesThem(@TempDir Path dir) throws IOException {
        Path file = writeYaml(dir, """
                assignments:
                  - subject: HISTOIRE-GEOGRAPHIE
                    description: Apprendre le cours
                    dueDate: '2026-05-20'
                    assignedDate: '2026-05-10'
                    done: true
                    teacher: BUKOWIECKI J.
                """);

        Assignment a = ManualEntryLoader.load(file, enricher()).getAssignments().get(0);
        assertEquals(LocalDate.of(2026, 5, 10), a.getAssignedDate());
        assertTrue(a.isDone());
        assertEquals("BUKOWIECKI J.", a.getTeacher());
    }

    @Test
    void assignment_missingRequiredField_throws(@TempDir Path dir) throws IOException {
        Path file = writeYaml(dir, """
                assignments:
                  - subject: MATHEMATIQUES
                    dueDate: '2026-05-15'
                """);
        ConfigLoader.ConfigException ex = assertThrows(ConfigLoader.ConfigException.class,
                () -> ManualEntryLoader.load(file, enricher()));
        assertTrue(ex.getMessage().contains("description"),
                "Error message should name the missing field: " + ex.getMessage());
    }

    @Test
    void assignment_invalidDate_throws(@TempDir Path dir) throws IOException {
        Path file = writeYaml(dir, """
                assignments:
                  - subject: MATHEMATIQUES
                    description: DS
                    dueDate: '15/05/2026'
                """);
        ConfigLoader.ConfigException ex = assertThrows(ConfigLoader.ConfigException.class,
                () -> ManualEntryLoader.load(file, enricher()));
        assertTrue(ex.getMessage().contains("dueDate"));
    }

    // -------------------------------------------------------------------------
    // Evaluation parsing
    // -------------------------------------------------------------------------

    @Test
    void evaluation_withOnlyRequiredFields_becomesSyntheticTimetableEntry(@TempDir Path dir) throws IOException {
        Path file = writeYaml(dir, """
                evaluations:
                  - subject: MATHEMATIQUES
                    name: Test fractions
                    date: '2026-05-22'
                """);

        List<TimetableEntry> evals = ManualEntryLoader.load(file, enricher()).getUpcomingEvals();
        assertEquals(1, evals.size());
        TimetableEntry e = evals.get(0);
        assertTrue(e.isEval(), "Manual evaluations must produce isEval=true synthetic entries");
        assertEquals("MATHEMATIQUES", e.getSubject());
        assertEquals("Test fractions", e.getLessonLabel());
        assertEquals(LocalDate.of(2026, 5, 22), e.getStartTime().toLocalDate());
        assertNotNull(e.getEndTime(), "Synthetic eval must have an end time so resolveManualEvalTimes can locate it");
    }

    @Test
    void evaluation_missingRequiredField_throws(@TempDir Path dir) throws IOException {
        Path file = writeYaml(dir, """
                evaluations:
                  - subject: MATHEMATIQUES
                    date: '2026-05-22'
                """);
        assertThrows(ConfigLoader.ConfigException.class,
                () -> ManualEntryLoader.load(file, enricher()));
    }

    // -------------------------------------------------------------------------
    // ID generation — legacy and explicit
    // -------------------------------------------------------------------------

    @Test
    void assignmentId_fallbackScheme_isSubjectAtDateAtDescription(@TempDir Path dir) throws IOException {
        Path file = writeYaml(dir, """
                assignments:
                  - subject: MATHS
                    description: hello
                    dueDate: '2026-05-15'
                """);
        Assignment a = ManualEntryLoader.load(file, enricher()).getAssignments().get(0);
        assertEquals("manual:MATHS@2026-05-15@hello", a.getId());
    }

    @Test
    void assignmentId_explicitId_takesPrecedenceAndIsStableAcrossDescriptionEdits(@TempDir Path dir) throws IOException {
        // Same id, different description — ID must remain stable so the diff doesn't churn.
        Path v1 = writeYaml(dir, """
                assignments:
                  - id: math-001
                    subject: MATHS
                    description: typo verison
                    dueDate: '2026-05-15'
                """);
        String firstId = ManualEntryLoader.load(v1, enricher()).getAssignments().get(0).getId();

        Path v2 = writeYaml(dir, """
                assignments:
                  - id: math-001
                    subject: MATHS
                    description: corrected version
                    dueDate: '2026-05-15'
                """);
        String secondId = ManualEntryLoader.load(v2, enricher()).getAssignments().get(0).getId();

        assertEquals("manual:math-001", firstId);
        assertEquals(firstId, secondId, "Explicit id: must keep the snapshot ID stable across description edits");
    }

    @Test
    void assignmentId_blankId_fallsBackToLegacyScheme(@TempDir Path dir) throws IOException {
        // Empty or whitespace id is treated as absent — guard against accidental "id: " yamls.
        Path file = writeYaml(dir, """
                assignments:
                  - id: '   '
                    subject: MATHS
                    description: hello
                    dueDate: '2026-05-15'
                """);
        Assignment a = ManualEntryLoader.load(file, enricher()).getAssignments().get(0);
        assertEquals("manual:MATHS@2026-05-15@hello", a.getId());
    }

    @Test
    void evaluationId_explicitId_takesPrecedence(@TempDir Path dir) throws IOException {
        Path file = writeYaml(dir, """
                evaluations:
                  - id: ds3-maths
                    subject: MATHEMATIQUES
                    name: DS 3
                    date: '2026-05-22'
                """);
        TimetableEntry e = ManualEntryLoader.load(file, enricher()).getUpcomingEvals().get(0);
        assertEquals("manual:ds3-maths", e.getId());
    }

    @Test
    void evaluationId_fallbackScheme_isSubjectAtDateAtName(@TempDir Path dir) throws IOException {
        Path file = writeYaml(dir, """
                evaluations:
                  - subject: FRANCAIS
                    name: Dictée
                    date: '2026-05-22'
                """);
        TimetableEntry e = ManualEntryLoader.load(file, enricher()).getUpcomingEvals().get(0);
        assertEquals("manual:FRANCAIS@2026-05-22@Dictée", e.getId());
    }

    // -------------------------------------------------------------------------
    // Merge/dedup contract — every manual ID must start with "manual:"
    // -------------------------------------------------------------------------

    @Test
    void allManualIds_startWithManualPrefix(@TempDir Path dir) throws IOException {
        // Main.runViews relies on removeIf(id.startsWith("manual:")) to dedup snapshot-baked
        // entries before re-injecting from YAML. If a future change drops the prefix anywhere,
        // dedup silently breaks and the user sees doubled entries in --mode views.
        Path file = writeYaml(dir, """
                assignments:
                  - subject: MATHS
                    description: hello
                    dueDate: '2026-05-15'
                  - id: math-explicit
                    subject: MATHS
                    description: world
                    dueDate: '2026-05-16'
                evaluations:
                  - subject: FRANCAIS
                    name: Dictée
                    date: '2026-05-22'
                  - id: ev-explicit
                    subject: FRANCAIS
                    name: DS
                    date: '2026-05-23'
                """);
        ManualEntryLoader.ManualEntries m = ManualEntryLoader.load(file, enricher());
        for (Assignment a : m.getAssignments()) {
            assertTrue(a.getId().startsWith("manual:"),
                    "Assignment ID must start with 'manual:' — got " + a.getId());
        }
        for (TimetableEntry e : m.getUpcomingEvals()) {
            assertTrue(e.getId().startsWith("manual:"),
                    "Evaluation ID must start with 'manual:' — got " + e.getId());
        }
    }

    // -------------------------------------------------------------------------
    // Enrichment is applied during load
    // -------------------------------------------------------------------------

    @Test
    void enrichment_isAppliedToAssignmentAndEvaluation(@TempDir Path dir) throws IOException {
        AppConfig.SubjectEnrichmentRule rule = new AppConfig.SubjectEnrichmentRule();
        rule.setSubject("MATHEMATIQUES");
        rule.setEnrichedSubject("Mathématiques");
        AppConfig.SubjectEnrichmentConfig cfg = new AppConfig.SubjectEnrichmentConfig();
        cfg.setRules(List.of(rule));
        SubjectEnricher enr = new SubjectEnricher(cfg);

        Path file = writeYaml(dir, """
                assignments:
                  - subject: MATHEMATIQUES
                    description: x
                    dueDate: '2026-05-15'
                evaluations:
                  - subject: MATHEMATIQUES
                    name: y
                    date: '2026-05-15'
                """);

        ManualEntryLoader.ManualEntries m = ManualEntryLoader.load(file, enr);
        assertEquals("Mathématiques", m.getAssignments().get(0).getEnrichedSubject());
        assertEquals("Mathématiques", m.getUpcomingEvals().get(0).getEnrichedSubject());
    }
}
