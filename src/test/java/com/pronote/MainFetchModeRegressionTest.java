package com.pronote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronote.domain.EntryStatus;
import com.pronote.domain.SchoolLifeEvent;
import com.pronote.domain.TimetableEntry;
import com.pronote.safety.EmptyFetchGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static com.pronote.PipelineTestHarness.assignment;
import static com.pronote.PipelineTestHarness.evaluationResult;
import static com.pronote.PipelineTestHarness.lesson;
import static com.pronote.PipelineTestHarness.readFile;
import static com.pronote.PipelineTestHarness.schoolLifeEvent;
import static com.pronote.PipelineTestHarness.upcomingWeekday;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end regression tests for {@code --mode fetch}, past the login: a
 * {@link PipelineTestHarness.FakeSource} stands in for the Pronote scrapers, and everything
 * else — manual-entry merge, empty-fetch guard, diff and filters, diff report, notification,
 * snapshot write, views, portal — is the production code path.
 */
class MainFetchModeRegressionTest {

    @TempDir
    Path tempDir;

    private PipelineTestHarness env;
    private PipelineTestHarness.FakeSource pronote;
    private final ObjectMapper jackson = new ObjectMapper();
    private String extra;

    private final LocalDate day1 = upcomingWeekday(1);
    private final LocalDate day2 = upcomingWeekday(2);

    @BeforeEach
    void setUp() {
        env = new PipelineTestHarness(tempDir);
        pronote = new PipelineTestHarness.FakeSource();
    }

    @AfterEach
    void dump(TestInfo info) {
        env.dumpOutputs("fetch/" + info.getTestMethod().orElseThrow().getName(),
                "calls=" + pronote.calls + "\ndownloadedFor=" + pronote.downloadedFor + "\n"
                        + (extra != null ? extra : ""));
    }

    private void fetchDryRun(String... extraArgs) {
        try (PipelineTestHarness.LogCapture logs = new PipelineTestHarness.LogCapture()) {
            String[] args = new String[extraArgs.length + 1];
            args[0] = "--dry-run";
            System.arraycopy(extraArgs, 0, args, 1, extraArgs.length);
            env.runFetch(pronote, args);
            extra = logs.preview();
        }
    }

    private void seedPronoteData() {
        pronote.timetable.add(lesson("syn-t1", "SYN_MATHS", "DURAND A.", "B12",
                day1.atTime(10, 0), day1.atTime(11, 0)));
        pronote.assignments.add(assignment("syn-a1", "SYN_MATHS", "Exercices page 12", day1, null));
        pronote.evaluations.add(evaluationResult("syn-ev1", "SYN_MATHS", "Contrôle fractions",
                LocalDate.now().minusDays(4)));
        pronote.schoolLife.add(schoolLifeEvent("syn-sl1", SchoolLifeEvent.EventType.DELAY,
                LocalDate.now().minusDays(2), "Retard bus synthétique"));
    }

    @Test
    void firstRun_savesBaselineRendersViewsAndStaysSilent() throws Exception {
        env.writeConfig();
        seedPronoteData();

        fetchDryRun();

        assertEquals(List.of("assignments", "timetable", "evaluations", "schoolLife",
                        "downloadAttachments", "saveSession"), pronote.calls,
                "grades disabled; download and session save after the scrapers");

        for (String type : List.of("assignments", "timetable", "evaluations", "school-life")) {
            assertTrue(Files.exists(env.latestSnapshot(type)), type);
        }
        assertFalse(Files.exists(env.latestSnapshot("grades")), "disabled type is not persisted");

        JsonNode report = jackson.readTree(env.readDiffLatest());
        assertTrue(report.get("firstRun").asBoolean());
        assertTrue(env.readDiffHistory().contains("FIRST_RUN"));
        assertFalse(extra.contains("NOTIFICATION PREVIEW"), "first run never notifies");

        assertTrue(env.read(env.timetableViewDir, day1 + ".html").contains("SYN_MATHS"));
        assertTrue(env.read(env.assignmentViewDir, "index.html").contains("Exercices page 12"));
        assertTrue(env.read(env.evaluationViewDir, "index.html").contains("Contrôle fractions"));
        assertTrue(env.read(env.schoolLifeViewDir, "index.html").contains("Retard bus synthétique"));
        assertTrue(env.readPortal().contains("Vie scolaire"));
    }

    @Test
    void secondRun_notifiesChangesAndArchivesThePreviousSnapshot() throws Exception {
        env.writeConfig();
        seedPronoteData();
        env.runFetch(pronote);
        pronote.calls.clear();

        TimetableEntry cancelled = lesson("syn-t1", "SYN_MATHS", "DURAND A.", "B12",
                day1.atTime(10, 0), day1.atTime(11, 0));
        cancelled.setStatus(EntryStatus.CANCELLED);
        cancelled.setStatusLabel("Prof. absent");
        pronote.timetable.set(0, cancelled);
        pronote.assignments.add(assignment("syn-a2", "SYN_FRANCAIS", "Lire le chapitre 3", day2, null));

        fetchDryRun();

        JsonNode report = jackson.readTree(env.readDiffLatest());
        assertFalse(report.get("firstRun").asBoolean());
        assertEquals(1, report.get("timetable").get("modified").asInt());
        assertEquals(1, report.get("assignments").get("added").asInt());
        assertTrue(extra.contains("NOTIFICATION PREVIEW"));
        assertTrue(extra.contains("Prof. absent"));
        assertTrue(extra.contains("Lire le chapitre 3"));
        assertTrue(readFile(env.latestSnapshot("assignments")).contains("syn-a2"));
        assertEquals(1, env.archiveCount("assignments"), "previous latest.json moved to the archive");
    }

    @Test
    void lateCancelledGhostOfARoomChange_isAnnouncedAsARoomChange() {
        // The relocated lesson was already fetched last run; this run only adds the cancelled
        // ghost of the original room. Telling the two apart needs the whole current timetable,
        // not just the diff.
        env.writeConfig();
        TimetableEntry moved = lesson("syn-t1b", "SYN_MATHS", "DURAND A.", "B14",
                day1.atTime(10, 0), day1.atTime(11, 0));
        pronote.timetable.add(moved);
        env.runFetch(pronote);

        TimetableEntry ghost = lesson("syn-t1", "SYN_MATHS", "DURAND A.", "B12",
                day1.atTime(10, 0), day1.atTime(11, 0));
        ghost.setStatus(EntryStatus.CANCELLED);
        ghost.setStatusLabel("Changement de salle");
        pronote.timetable.add(ghost);

        fetchDryRun();

        assertTrue(extra.contains("salle B12 → B14"), extra);
        assertFalse(extra.contains("Priority: HIGH"), "a room change is not a lost lesson");
    }

    @Test
    void manualEntriesAreMergedStagedAndPersisted() throws Exception {
        env.enrichmentRules.add(new String[]{"SYN_HG", "DURAND A.", "Histoire SYN"});
        env.writeConfig();
        pronote.timetable.add(lesson("syn-t1", "SYN_HG", "DURAND A.", "B12",
                day1.atTime(14, 5), day1.atTime(15, 5)));
        pronote.assignments.add(assignment("syn-a1", "SYN_HG", "Carte des fleuves", day1, null));
        env.writeManualAttachment("syn-photo.png", "synthetic image bytes");
        env.writeManualEntries("""
                assignments:
                  - id: devoir-photo
                    subject: "SYN_HG"
                    description: "Voir la photo du tableau"
                    dueDate: "%s"
                    attachments:
                      - syn-photo.png
                evaluations:
                  - id: ds-hg
                    subject: "SYN_HG"
                    name: "DS synthétique fleuves"
                    date: "%s"
                """.formatted(day1, day1));

        fetchDryRun();

        String assignments = readFile(env.latestSnapshot("assignments"));
        assertTrue(assignments.contains("manual:devoir-photo"));
        assertTrue(assignments.contains("Histoire SYN"),
                "Pronote assignment re-enriched with the teacher found in the timetable");
        assertTrue(assignments.contains("manual_devoir-photo"),
                "snapshot records the staged attachment's localPath");
        assertTrue(Files.isRegularFile(
                env.attachmentsDir().resolve("manual_devoir-photo").resolve("syn-photo.png")));
        assertTrue(pronote.downloadedFor.contains("manual:devoir-photo"),
                "attachment download sees the merged list");

        String timetable = readFile(env.latestSnapshot("timetable"));
        assertTrue(timetable.contains("manual:ds-hg"));
        String day = env.read(env.timetableViewDir, day1 + ".html");
        assertTrue(day.contains("DS synthétique fleuves"));
        assertFalse(day.contains("8h00"), "manual eval takes the matching lesson's time");
    }

    @Test
    void allEmptyFetch_isRefusedAlertedAndLeavesSnapshotsUntouched() {
        env.writeConfig();
        seedPronoteData();
        env.runFetch(pronote);
        String before = readFile(env.latestSnapshot("timetable"));
        Path report = env.dataDir.resolve("diff-latest.json");
        String reportBefore = readFile(report);

        PipelineTestHarness.FakeSource dead = new PipelineTestHarness.FakeSource();
        pronote = dead;
        // Manual entries must not mask a dead session.
        env.writeManualEntries("""
                assignments:
                  - subject: "SYN_MATHS"
                    description: "Travail noté à la main"
                    dueDate: "%s"
                """.formatted(day1));
        PipelineTestHarness.RecordingNotifier alerts = new PipelineTestHarness.RecordingNotifier();

        assertThrows(EmptyFetchGuard.EmptyFetchException.class, () -> env.runFetch(dead, alerts));

        assertEquals(1, alerts.sent.size());
        assertTrue(alerts.sent.get(0).title().contains("récupération vide"), alerts.sent.get(0).title());
        assertEquals(before, readFile(env.latestSnapshot("timetable")), "snapshot not overwritten");
        assertEquals(reportBefore, readFile(report), "no diff report for a refused run");
        assertFalse(dead.calls.contains("saveSession"));
    }

    @Test
    void scraperFailure_alertsWithPhaseAndStopsBeforeAnyWrite() {
        env.writeConfig();
        seedPronoteData();
        pronote.failOn = "timetable";
        PipelineTestHarness.RecordingNotifier alerts = new PipelineTestHarness.RecordingNotifier();

        RuntimeException ex = assertThrows(RuntimeException.class, () -> env.runFetch(pronote, alerts));

        assertTrue(ex.getMessage().contains("synthetic timetable failure"));
        assertEquals(List.of("assignments", "timetable"), pronote.calls, "later scrapers are not called");
        assertEquals(1, alerts.sent.size());
        assertTrue(alerts.sent.get(0).title().contains("emploi du temps"));
        assertFalse(Files.exists(env.latestSnapshot("assignments")));
        assertFalse(Files.exists(env.dataDir.resolve("diff-latest.json")));
    }

    @Test
    void pastDueAssignmentsLeavingTheWindowAreNotAnnounced() throws Exception {
        env.writeConfig();
        pronote.timetable.add(lesson("syn-t1", "SYN_MATHS", "DURAND A.", "B12",
                day1.atTime(10, 0), day1.atTime(11, 0)));
        pronote.assignments.add(assignment("syn-old", "SYN_MATHS", "Ancien devoir",
                LocalDate.now().minusDays(5), null));
        pronote.assignments.add(assignment("syn-up", "SYN_MATHS", "Devoir à venir", day2, null));
        env.runFetch(pronote);

        pronote.assignments.remove(0);
        fetchDryRun();

        JsonNode report = jackson.readTree(env.readDiffLatest());
        assertEquals(0, report.get("assignments").get("removed").asInt());
        assertFalse(report.get("hasChanges").asBoolean());
    }

    @Test
    void featureOverride_fetchesAndPersistsOnlyTheSelectedTypes() {
        env.writeConfig();
        seedPronoteData();

        fetchDryRun("--features", "timetable");

        assertEquals(List.of("timetable", "saveSession"), pronote.calls,
                "no attachment download without the assignments feature");
        assertTrue(Files.exists(env.latestSnapshot("timetable")));
        assertFalse(Files.exists(env.latestSnapshot("assignments")));
        assertFalse(Files.exists(env.assignmentViewDir.resolve("index.html")));
        String portal = env.readPortal();
        assertTrue(portal.contains("Emploi du temps"));
        assertFalse(portal.contains("Devoirs"));
    }

    @Test
    void newlyEnabledTypeOnExistingInstall_isASilentBaseline() throws Exception {
        env.writeConfig();
        seedPronoteData();
        env.runFetch(pronote, "--features", "timetable");

        fetchDryRun();

        JsonNode report = jackson.readTree(env.readDiffLatest());
        assertFalse(report.get("firstRun").asBoolean());
        assertEquals(0, report.get("assignments").get("added").asInt(),
                "a type without a previous snapshot gets an empty diff");
        assertFalse(extra.contains("NOTIFICATION PREVIEW"));
        assertTrue(Files.exists(env.latestSnapshot("assignments")));
    }

    @Test
    void emptyLists_stillRenderViews() {
        // Unlike diff mode, fetch renders a view even when its list is empty.
        env.writeConfig();
        pronote.timetable.add(lesson("syn-t1", "SYN_MATHS", "DURAND A.", "B12",
                day1.atTime(10, 0), day1.atTime(11, 0)));

        fetchDryRun();

        assertTrue(Files.exists(env.assignmentViewDir.resolve("index.html")));
        assertTrue(Files.exists(env.schoolLifeViewDir.resolve("index.html")));
        assertTrue(Files.exists(env.evaluationViewDir.resolve("summary.html")));
    }
}
