package com.pronote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronote.domain.Assignment;
import com.pronote.domain.EntryStatus;
import com.pronote.domain.SchoolLifeEvent;
import com.pronote.domain.TimetableEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static com.pronote.PipelineTestHarness.assignment;
import static com.pronote.PipelineTestHarness.evalLesson;
import static com.pronote.PipelineTestHarness.evaluationResult;
import static com.pronote.PipelineTestHarness.grade;
import static com.pronote.PipelineTestHarness.lesson;
import static com.pronote.PipelineTestHarness.schoolLifeEvent;
import static com.pronote.PipelineTestHarness.upcomingWeekday;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end regression tests for {@code --mode diff --dry-run}: seed the archive
 * (previous run) and {@code latest.json} (current run) snapshots, run the real CLI
 * pipeline, and assert on {@code diff-latest.json}, {@code diff-history.log} and the
 * dry-run notification preview. This pins the whole offline chain:
 * SnapshotStore → DiffEngine → diff filters → DiffReporter → NotificationPayloadBuilder.
 */
class MainDiffModeRegressionTest {

    @TempDir
    Path tempDir;

    private PipelineTestHarness env;
    private final ObjectMapper jackson = new ObjectMapper();
    private String preview;

    private final LocalDate day1 = upcomingWeekday(1);
    private final LocalDate day2 = upcomingWeekday(2);

    @BeforeEach
    void setUp() {
        env = new PipelineTestHarness(tempDir);
    }

    @AfterEach
    void dump(TestInfo info) {
        env.dumpOutputs("diff/" + info.getTestMethod().orElseThrow().getName(), preview);
    }

    private void runDiffDryRun() {
        try (PipelineTestHarness.LogCapture logs = new PipelineTestHarness.LogCapture()) {
            env.runMain("--mode", "diff", "--dry-run");
            preview = logs.preview();
        }
    }

    @Test
    void fullChangeScenario_reportsAndPreviewsAllFiveTypes() throws Exception {
        env.featGrades = true;
        env.writeConfig();

        // --- previous run (archive) ---
        TimetableEntry t1Prev = lesson("syn-t1", "SYN_MATHS", "DURAND A.", "B12",
                day1.atTime(10, 0), day1.atTime(11, 0));
        TimetableEntry t2 = lesson("syn-t2", "SYN_FRANCAIS", "MARTIN B.", "C03",
                day2.atTime(9, 0), day2.atTime(10, 0));
        env.seedArchive("timetable", List.of(t1Prev, t2));
        env.seedArchive("assignments", List.of(
                assignment("syn-a1", "SYN_FRANCAIS", "Apprendre le poème", day1, null)));
        env.seedArchive("grades", List.of());
        env.seedArchive("evaluations", List.of());
        env.seedArchive("school-life", List.of());

        // --- current run (latest): one change of each kind ---
        TimetableEntry t1Curr = lesson("syn-t1", "SYN_MATHS", "DURAND A.", "B12",
                day1.atTime(10, 0), day1.atTime(11, 0));
        t1Curr.setStatus(EntryStatus.CANCELLED);
        t1Curr.setStatusLabel("Prof. absent");
        env.seedLatest("timetable", List.of(t1Curr, t2,
                evalLesson("syn-e1", "SYN_SCIENCES", "DS synthétique volcans",
                        day2.atTime(11, 0), day2.atTime(12, 0))));
        env.seedLatest("assignments", List.of(
                assignment("syn-a1", "SYN_FRANCAIS", "Apprendre le poème", day1, null),
                assignment("syn-a2", "SYN_MATHS", "Exercices 12 à 15 page 80", day2, null)));
        env.seedLatest("grades", List.of(
                grade("syn-g1", "SYN_MATHS", "15,5", 20, LocalDate.now().minusDays(1))));
        env.seedLatest("evaluations", List.of(
                evaluationResult("syn-ev1", "SYN_SCIENCES", "Contrôle volcans", LocalDate.now().minusDays(2))));
        env.seedLatest("school-life", List.of(
                schoolLifeEvent("syn-sl1", SchoolLifeEvent.EventType.DELAY,
                        LocalDate.now().minusDays(1), "Retard bus synthétique")));

        runDiffDryRun();

        // Structured report
        JsonNode report = jackson.readTree(env.readDiffLatest());
        assertFalse(report.get("firstRun").asBoolean());
        assertTrue(report.get("hasChanges").asBoolean());
        assertEquals(1, report.get("assignments").get("added").asInt());
        assertEquals(1, report.get("timetable").get("added").asInt(), "the upcoming eval");
        assertEquals(1, report.get("timetable").get("modified").asInt(), "the cancellation");
        assertEquals(1, report.get("grades").get("added").asInt());
        assertEquals(1, report.get("evaluations").get("added").asInt());
        assertEquals(1, report.get("schoolLife").get("added").asInt());

        // Audit trail
        String history = env.readDiffHistory();
        assertTrue(history.contains("CHANGES"), history);

        // Dry-run notification preview (logged, not sent)
        assertTrue(preview.contains("NOTIFICATION PREVIEW"));
        assertTrue(preview.contains("Priority: HIGH"), "cancellation must bump priority to HIGH");
        assertTrue(preview.contains("Prof. absent"), "cancellation reason must be surfaced");
        assertTrue(preview.contains("📚 Devoirs"), "multi-section body must have section headers");
        assertTrue(preview.contains("DS synthétique volcans"), "upcoming eval label must be in the body");
        assertTrue(preview.contains("15,5/20"), "new grade must be in the body");
        assertTrue(preview.contains("🏫 Vie scolaire"));

        // Views are regenerated from the current snapshot
        assertTrue(Files.exists(env.timetableViewDir.resolve("index.html")));
        assertTrue(Files.exists(env.assignmentViewDir.resolve("index.html")));
        assertTrue(env.readPortal().contains("Emploi du temps"));
    }

    @Test
    void roomChangeIsToldApartFromCancellation() throws Exception {
        env.writeConfig();
        // Pronote's shape for a room change: the original lesson turns into a cancelled ghost
        // and a new normal lesson appears in the same slot.
        TimetableEntry before = lesson("syn-t1", "SYN_MATHS", "DURAND A.", "B12",
                day1.atTime(10, 0), day1.atTime(11, 0));
        env.seedArchive("timetable", List.of(before));

        TimetableEntry ghost = lesson("syn-t1", "SYN_MATHS", "DURAND A.", "B12",
                day1.atTime(10, 0), day1.atTime(11, 0));
        ghost.setStatus(EntryStatus.CANCELLED);
        ghost.setStatusLabel("Changement de salle");
        TimetableEntry moved = lesson("syn-t1b", "SYN_MATHS", "DURAND A.", "B14",
                day1.atTime(10, 0), day1.atTime(11, 0));
        env.seedLatest("timetable", List.of(ghost, moved));

        runDiffDryRun();

        assertTrue(preview.contains("salle B12 → B14"), preview);
        assertFalse(preview.contains("Priority: HIGH"), "a room change is not worth interrupting for");
    }

    @Test
    void noChanges_writesNoChangeLineAndSendsNothing() throws Exception {
        env.writeConfig();
        List<Assignment> same = List.of(
                assignment("syn-a1", "SYN_MATHS", "Exercices page 12", day1, null));
        env.seedArchive("assignments", same);
        env.seedLatest("assignments", same);

        runDiffDryRun();

        JsonNode report = jackson.readTree(env.readDiffLatest());
        assertFalse(report.get("hasChanges").asBoolean());
        assertTrue(env.readDiffHistory().contains("NO_CHANGE"));
        assertFalse(preview.contains("NOTIFICATION PREVIEW"),
                "no notification preview when nothing changed");
    }

    @Test
    void firstRun_noArchive_establishesBaselineSilently() throws Exception {
        env.writeConfig();
        env.seedLatest("assignments", List.of(
                assignment("syn-a1", "SYN_MATHS", "Exercices page 12", day1, null)));

        runDiffDryRun();

        JsonNode report = jackson.readTree(env.readDiffLatest());
        assertTrue(report.get("firstRun").asBoolean());
        assertFalse(report.get("hasChanges").asBoolean());
        assertTrue(env.readDiffHistory().contains("FIRST_RUN"));
        assertFalse(preview.contains("NOTIFICATION PREVIEW"));
    }

    @Test
    void pastTimetableChangesAreSuppressed() throws Exception {
        env.writeConfig();
        LocalDate yesterday = LocalDate.now().minusDays(1);

        TimetableEntry p1Prev = lesson("syn-p1", "SYN_MATHS", "DURAND A.", "B12",
                yesterday.atTime(10, 0), yesterday.atTime(11, 0));
        TimetableEntry p2 = lesson("syn-p2", "SYN_HISTOIRE", "MARTIN B.", "C03",
                yesterday.atTime(11, 0), yesterday.atTime(12, 0));
        env.seedArchive("timetable", List.of(p1Prev, p2));

        // Current run: p1's room changed, p2 disappeared — both lessons already over.
        TimetableEntry p1Curr = lesson("syn-p1", "SYN_MATHS", "DURAND A.", "B14",
                yesterday.atTime(10, 0), yesterday.atTime(11, 0));
        env.seedLatest("timetable", List.of(p1Curr));

        runDiffDryRun();

        JsonNode report = jackson.readTree(env.readDiffLatest());
        assertFalse(report.get("hasChanges").asBoolean(),
                "changes to lessons that already ended are not actionable");
        assertEquals(0, report.get("timetable").get("modified").asInt());
        assertEquals(0, report.get("timetable").get("removed").asInt());
        assertTrue(env.readDiffHistory().contains("NO_CHANGE"));
    }

    @Test
    void pastDueAssignmentsLeavingTheWindowAreNotAnnounced() throws Exception {
        env.writeConfig();
        Assignment lastWeek = assignment("syn-old", "SYN_MATHS", "Exercices de la semaine dernière",
                LocalDate.now().minusDays(5), null);
        Assignment upcoming = assignment("syn-up", "SYN_MATHS", "Exercices à venir", day2, null);
        Assignment cancelled = assignment("syn-gone", "SYN_HISTOIRE", "Devoir retiré", day2, null);
        env.seedArchive("assignments", List.of(lastWeek, upcoming, cancelled));
        env.seedLatest("assignments", List.of(upcoming));

        runDiffDryRun();

        JsonNode report = jackson.readTree(env.readDiffLatest());
        assertEquals(1, report.get("assignments").get("removed").asInt(),
                "only the future assignment counts as removed");
        String removed = report.get("assignments").get("removedItems").toString();
        assertTrue(removed.contains("syn-gone"));
        assertFalse(removed.contains("syn-old"), "past-due work only fell out of the fetch window");
    }

    @Test
    void newlyDiscoveredFurthestWeek_suppressesBulkAdditionsButKeepsEvals() throws Exception {
        env.writeConfig();
        LocalDate furthestMonday = LocalDate.now().plusWeeks(env.weeksAhead)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        TimetableEntry thisWeek = lesson("syn-w0", "SYN_MATHS", "DURAND A.", "B12",
                day1.atTime(10, 0), day1.atTime(11, 0));
        env.seedArchive("timetable", List.of(thisWeek));

        env.seedLatest("timetable", List.of(
                thisWeek,
                lesson("syn-normal-far", "SYN_HISTOIRE", "MARTIN B.", "C03",
                        furthestMonday.atTime(10, 0), furthestMonday.atTime(11, 0)),
                evalLesson("syn-eval-far", "SYN_SCIENCES", "DS programmé",
                        furthestMonday.atTime(14, 0), furthestMonday.atTime(15, 0))));

        runDiffDryRun();

        JsonNode report = jackson.readTree(env.readDiffLatest());
        assertEquals(1, report.get("timetable").get("added").asInt(),
                "only the eval survives the new-week bulk filter");
        String addedItems = report.get("timetable").get("addedItems").toString();
        assertTrue(addedItems.contains("syn-eval-far"), "evals always notify (user needs lead time)");
        assertFalse(addedItems.contains("syn-normal-far"), "bulk normal additions are suppressed");
    }

    @Test
    void furthestWeekIsTakenFromTheDataNotFromWeeksAhead() throws Exception {
        // Pronote returned one week less than weeksAhead (end of term): the newly discovered
        // week is next week, and its bulk additions must still be suppressed.
        env.weeksAhead = 3;
        env.writeConfig();
        LocalDate nextMonday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        TimetableEntry soon = lesson("syn-w0", "SYN_MATHS", "DURAND A.", "B12",
                nextMonday.minusDays(7).atTime(23, 0), nextMonday.minusDays(7).atTime(23, 30));
        env.seedArchive("timetable", List.of(soon));
        env.seedLatest("timetable", List.of(soon,
                lesson("syn-next", "SYN_HISTOIRE", "MARTIN B.", "C03",
                        nextMonday.atTime(10, 0), nextMonday.atTime(11, 0))));

        runDiffDryRun();

        JsonNode report = jackson.readTree(env.readDiffLatest());
        assertEquals(0, report.get("timetable").get("added").asInt(),
                report.get("timetable").toString());
    }

    @Test
    void emptyCurrentSnapshotSkipsThatViewButKeepsTheOthers() {
        env.writeConfig();
        env.seedArchive("timetable", List.of());
        env.seedLatest("timetable", List.of());
        env.seedArchive("assignments", List.of());
        env.seedLatest("assignments", List.of(
                assignment("syn-a1", "SYN_MATHS", "Exercices page 12", day1, null)));

        runDiffDryRun();

        assertFalse(Files.exists(env.timetableViewDir.resolve("index.html")),
                "diff mode does not render a view from an empty list");
        assertTrue(Files.exists(env.assignmentViewDir.resolve("index.html")));
    }
}
