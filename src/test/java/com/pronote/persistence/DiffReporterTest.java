package com.pronote.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronote.domain.Assignment;
import com.pronote.domain.CompetenceEvaluation;
import com.pronote.domain.Grade;
import com.pronote.domain.SchoolLifeEvent;
import com.pronote.domain.TimetableEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiffReporterTest {

    private static <T extends Identifiable> DiffResult<T> emptyDiff() {
        return new DiffResult<>(List.of(), List.of(), Map.of());
    }

    private static Assignment assignment(String id) {
        Assignment a = new Assignment();
        a.setId(id);
        a.setSubject("TEST_SUBJECT");
        a.setDueDate(LocalDate.of(2030, 5, 1));
        return a;
    }

    @Test
    void record_writesBothFiles(@TempDir Path dataDir) throws Exception {
        DiffReporter reporter = new DiffReporter(dataDir);
        reporter.record(emptyDiff(), emptyDiff(), emptyDiff(), emptyDiff(), emptyDiff(), false);

        assertTrue(Files.exists(dataDir.resolve("diff-latest.json")));
        assertTrue(Files.exists(dataDir.resolve("diff-history.log")));
    }

    @Test
    void record_firstRun_marksFirstRunTrue(@TempDir Path dataDir) throws Exception {
        DiffReporter reporter = new DiffReporter(dataDir);
        reporter.record(emptyDiff(), emptyDiff(), emptyDiff(), emptyDiff(), emptyDiff(), true);

        JsonNode root = new ObjectMapper().readTree(dataDir.resolve("diff-latest.json").toFile());
        assertTrue(root.get("firstRun").asBoolean());
        assertFalse(root.get("hasChanges").asBoolean());

        String history = Files.readString(dataDir.resolve("diff-history.log"));
        assertTrue(history.contains("FIRST_RUN"));
    }

    @Test
    void record_noChanges_writesNoChangeLine(@TempDir Path dataDir) throws Exception {
        DiffReporter reporter = new DiffReporter(dataDir);
        reporter.record(emptyDiff(), emptyDiff(), emptyDiff(), emptyDiff(), emptyDiff(), false);

        String history = Files.readString(dataDir.resolve("diff-history.log"));
        assertTrue(history.contains("NO_CHANGE"));

        JsonNode root = new ObjectMapper().readTree(dataDir.resolve("diff-latest.json").toFile());
        assertFalse(root.get("firstRun").asBoolean());
        assertFalse(root.get("hasChanges").asBoolean());
    }

    @Test
    void record_withAddedAssignment_recordsCountsAndItem(@TempDir Path dataDir) throws Exception {
        DiffResult<Assignment> diff = new DiffResult<>(
                List.of(assignment("a-1")), List.of(), Map.of());

        new DiffReporter(dataDir).record(diff,
                DiffReporterTest.<TimetableEntry>emptyDiff(),
                DiffReporterTest.<Grade>emptyDiff(),
                DiffReporterTest.<CompetenceEvaluation>emptyDiff(),
                DiffReporterTest.<SchoolLifeEvent>emptyDiff(),
                false);

        JsonNode root = new ObjectMapper().readTree(dataDir.resolve("diff-latest.json").toFile());
        assertTrue(root.get("hasChanges").asBoolean());
        JsonNode assignments = root.get("assignments");
        assertEquals(1, assignments.get("added").asInt());
        assertEquals(0, assignments.get("removed").asInt());
        assertEquals(0, assignments.get("modified").asInt());
        assertEquals("a-1", assignments.get("addedItems").get(0).get("id").asText());

        String history = Files.readString(dataDir.resolve("diff-history.log"));
        assertTrue(history.contains("CHANGES(1)"));
        assertTrue(history.contains("assignments: +1 -0 ~0"));
    }

    @Test
    void record_modifiedAssignment_includesFieldChanges(@TempDir Path dataDir) throws Exception {
        Assignment a = assignment("a-1");
        DiffResult<Assignment> diff = new DiffResult<>(
                List.of(), List.of(),
                Map.of(a, List.of(new FieldChange("done", false, true))));

        new DiffReporter(dataDir).record(diff,
                DiffReporterTest.<TimetableEntry>emptyDiff(),
                DiffReporterTest.<Grade>emptyDiff(),
                DiffReporterTest.<CompetenceEvaluation>emptyDiff(),
                DiffReporterTest.<SchoolLifeEvent>emptyDiff(),
                false);

        JsonNode root = new ObjectMapper().readTree(dataDir.resolve("diff-latest.json").toFile());
        JsonNode modified = root.get("assignments").get("modifiedItems").get(0);
        JsonNode change = modified.get("changes").get(0);
        assertEquals("done",  change.get("field").asText());
        assertEquals("false", change.get("oldValue").asText());
        assertEquals("true",  change.get("newValue").asText());
    }

    @Test
    void record_appendsToExistingHistoryFile(@TempDir Path dataDir) throws Exception {
        DiffReporter reporter = new DiffReporter(dataDir);
        reporter.record(emptyDiff(), emptyDiff(), emptyDiff(), emptyDiff(), emptyDiff(), false);
        reporter.record(emptyDiff(), emptyDiff(), emptyDiff(), emptyDiff(), emptyDiff(), false);

        long lineCount = Files.readAllLines(dataDir.resolve("diff-history.log")).size();
        assertEquals(2, lineCount);
    }
}
