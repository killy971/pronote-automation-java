package com.pronote;

import com.pronote.config.AppConfig;
import com.pronote.domain.Assignment;
import com.pronote.domain.SchoolLifeEvent;
import com.pronote.domain.TimetableEntry;
import com.pronote.views.SubjectColorResolver;
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
import static com.pronote.PipelineTestHarness.evalLesson;
import static com.pronote.PipelineTestHarness.evaluationResult;
import static com.pronote.PipelineTestHarness.lesson;
import static com.pronote.PipelineTestHarness.schoolLifeEvent;
import static com.pronote.PipelineTestHarness.upcomingWeekday;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end regression tests for {@code --mode views}: seed snapshot files on disk,
 * run the real CLI pipeline, assert on the generated HTML. These tests pin the observable
 * behaviour of the offline view pipeline so the implementation can be refactored safely.
 */
class MainViewsModeRegressionTest {

    @TempDir
    Path tempDir;

    private PipelineTestHarness env;

    private final LocalDate day0 = upcomingWeekday(0);
    private final LocalDate day1 = upcomingWeekday(1);

    @BeforeEach
    void setUp() {
        env = new PipelineTestHarness(tempDir);
    }

    @AfterEach
    void dump(TestInfo info) {
        env.dumpOutputs("views/" + info.getTestMethod().orElseThrow().getName(), null);
    }

    @Test
    void regeneratesAllEnabledViewsFromSnapshots() {
        env.enrichmentRules.add(new String[]{"SYN_MATHS", null, "Maths SYN"});
        env.writeConfig();

        env.seedLatest("timetable", List.of(
                lesson("syn-t1", "SYN_MATHS", "DURAND A.", "B12",
                        day0.atTime(10, 0), day0.atTime(11, 0)),
                lesson("syn-t2", "SYN_HISTOIRE", "MARTIN B.", "C03",
                        day1.atTime(9, 0), day1.atTime(10, 0)),
                evalLesson("syn-e1", "SYN_SCIENCES", "DS synthétique volcans",
                        day1.atTime(11, 0), day1.atTime(12, 0))));
        env.seedLatest("assignments", List.of(
                assignment("syn-a1", "SYN_MATHS", "Réviser le chapitre 4 sur les fractions",
                        day0, LocalDate.now()),
                assignment("syn-a2", "SYN_HISTOIRE", "Lire la leçon 7", day1, null)));
        env.seedLatest("evaluations", List.of(
                evaluationResult("syn-ev1", "SYN_SCIENCES", "Contrôle synthétique volcans",
                        LocalDate.now().minusDays(10))));
        env.seedLatest("school-life", List.of(
                schoolLifeEvent("syn-sl1", SchoolLifeEvent.EventType.DELAY,
                        LocalDate.now().minusDays(3), "Retard bus synthétique")));

        env.runMain("--mode", "views");

        // Timetable: index + one page per upcoming weekday + current.html
        String ttIndex = env.read(env.timetableViewDir, "index.html");
        assertTrue(ttIndex.contains("Emploi du temps"));
        assertTrue(ttIndex.contains("éval"), "index day card should flag the upcoming eval");
        assertTrue(Files.exists(env.timetableViewDir.resolve("current.html")));
        String ttDay0 = env.read(env.timetableViewDir, day0 + ".html");
        assertTrue(ttDay0.contains("Maths SYN"), "enrichment rule applies to the snapshot in views mode");
        assertTrue(ttDay0.contains("B12"), "room should be displayed");
        String ttDay1 = env.read(env.timetableViewDir, day1 + ".html");
        assertTrue(ttDay1.contains("DS synthétique volcans"), "eval label should appear on its day page");

        // Assignments: enriched subject, description, "Nouveau" badge for today's assignment,
        // and the upcoming eval surfaced from the timetable snapshot.
        String asgn = env.read(env.assignmentViewDir, "index.html");
        assertTrue(asgn.contains("Réviser le chapitre 4 sur les fractions"));
        assertTrue(asgn.contains("Maths SYN"));
        assertTrue(asgn.contains("Nouveau"), "assignment assigned today should carry the Nouveau badge");
        assertTrue(asgn.contains("DS synthétique volcans"), "upcoming eval should appear in assignment view");

        // Evaluations: bilan + summary
        String evals = env.read(env.evaluationViewDir, "index.html");
        assertTrue(evals.contains("Contrôle synthétique volcans"));
        assertTrue(Files.exists(env.evaluationViewDir.resolve("summary.html")));

        // School life
        String schoolLife = env.read(env.schoolLifeViewDir, "index.html");
        assertTrue(schoolLife.contains("Retard bus synthétique"));

        // Portal index links all enabled sections
        String portal = env.readPortal();
        assertTrue(portal.contains("Emploi du temps"));
        assertTrue(portal.contains("Devoirs"));
        assertTrue(portal.contains("Évaluations"));
        assertTrue(portal.contains("Bilan"));
        assertTrue(portal.contains("Vie scolaire"));
    }

    @Test
    void enrichmentRuleEditsApplyToOldSnapshotsWithoutFetch() {
        env.enrichmentRules.add(new String[]{"SYN_HG", "DURAND A.", "Histoire SYN"});
        env.writeConfig();

        // Saved before the rule existed: enrichedSubject is the stale raw value.
        TimetableEntry t = lesson("syn-t1", "SYN_HG", "DURAND A.", "B12",
                day1.atTime(10, 0), day1.atTime(11, 0));
        env.seedLatest("timetable", List.of(t));
        // Homework API carries no teacher — it must be inferred from the lesson on the due date.
        env.seedLatest("assignments", List.of(
                assignment("syn-a1", "SYN_HG", "Carte des fleuves", day1, null)));

        env.runMain("--mode", "views");

        assertTrue(env.read(env.timetableViewDir, day1 + ".html").contains("Histoire SYN"));
        assertTrue(env.read(env.assignmentViewDir, "index.html").contains("Histoire SYN"),
                "assignment picks up the teacher-specific rule via the timetable");
    }

    @Test
    void manualEntriesAreStrippedFromSnapshotAndReinjectedFreshFromYaml() {
        env.writeConfig();

        // Snapshot contains stale manual entries (as persisted by a previous fetch)
        TimetableEntry staleEval = evalLesson("manual:ds-sciences", "SYN_SCIENCES",
                "ANCIEN LIBELLE", day1.atTime(8, 0), day1.atTime(9, 0));
        env.seedLatest("timetable", List.of(
                lesson("syn-t1", "SYN_SCIENCES", "ROBERT C.", "D05",
                        day1.atTime(14, 5), day1.atTime(15, 5)),
                staleEval));
        env.seedLatest("assignments", List.of(
                assignment("manual:devoir-x", "SYN_SCIENCES", "ANCIENNE DESCRIPTION", day1, null)));

        // YAML carries the same stable ids with corrected texts
        env.writeManualEntries("""
                assignments:
                  - id: devoir-x
                    subject: "SYN_SCIENCES"
                    description: "Nouvelle description synthétique"
                    dueDate: "%s"
                evaluations:
                  - id: ds-sciences
                    subject: "SYN_SCIENCES"
                    name: "Nouveau libellé DS"
                    date: "%s"
                """.formatted(day1, day1));

        env.runMain("--mode", "views");

        String asgn = env.read(env.assignmentViewDir, "index.html");
        assertTrue(asgn.contains("Nouvelle description synthétique"));
        assertFalse(asgn.contains("ANCIENNE DESCRIPTION"), "stale manual assignment must be stripped");
        assertTrue(asgn.contains("Nouveau libellé DS"));
        assertFalse(asgn.contains("ANCIEN LIBELLE"), "stale manual eval must be stripped");

        // The manual eval's placeholder 08:00 slot must be resolved against the
        // 14:05 SYN_SCIENCES lesson on the same day.
        String ttDay1 = env.read(env.timetableViewDir, day1 + ".html");
        assertTrue(ttDay1.contains("Nouveau libellé DS"));
        assertFalse(ttDay1.contains("ANCIEN LIBELLE"));
        assertFalse(ttDay1.contains("8h00"),
                "manual eval should take the matching lesson's time, not the 08:00 placeholder");
    }

    @Test
    void manualAttachmentsAreStagedAndPrunedOffline() {
        env.writeConfig();
        env.seedLatest("timetable", List.of(
                lesson("syn-t1", "SYN_MATHS", "DURAND A.", "B12", day1.atTime(10, 0), day1.atTime(11, 0))));
        env.seedLatest("assignments", List.of());
        env.writeManualAttachment("syn-photo.png", "synthetic image bytes");
        env.writeManualEntries("""
                assignments:
                  - id: devoir-photo
                    subject: "SYN_MATHS"
                    description: "Voir la photo du tableau"
                    dueDate: "%s"
                    attachments:
                      - syn-photo.png
                      - url: "https://example.invalid/corrige"
                        label: "Corrigé en ligne"
                """.formatted(day1));

        env.runMain("--mode", "views");

        Path staged = env.attachmentsDir().resolve("manual_devoir-photo").resolve("syn-photo.png");
        assertTrue(Files.isRegularFile(staged), "file attachment is copied into the attachments tree");
        String asgn = env.read(env.assignmentViewDir, "index.html");
        assertTrue(asgn.contains("syn-photo.png"), "staged attachment is linked from the view");
        assertTrue(asgn.contains("https://example.invalid/corrige"), "url attachment is linked as-is");

        // Dropping the attachment from the YAML prunes the staged copy on the next views run.
        env.writeManualEntries("""
                assignments:
                  - id: devoir-photo
                    subject: "SYN_MATHS"
                    description: "Voir la photo du tableau"
                    dueDate: "%s"
                """.formatted(day1));
        env.runMain("--mode", "views");
        assertFalse(Files.exists(staged), "attachment removed from YAML is pruned");
    }

    @Test
    void subjectColourOverridesAndIconsApplyToEveryView() {
        env.colorOverrides.put("SYN_MATHS", "#2e7d32");
        env.iconOverrides.put("SYN_MATHS", "🧮");
        env.writeConfig();

        List<TimetableEntry> timetable = List.of(
                lesson("syn-t1", "SYN_MATHS", "DURAND A.", "B12", day1.atTime(10, 0), day1.atTime(11, 0)));
        env.seedLatest("timetable", timetable);
        env.seedLatest("assignments", List.of(
                assignment("syn-a1", "SYN_MATHS", "Exercices page 12", day1, null)));
        env.seedLatest("evaluations", List.of(
                evaluationResult("syn-ev1", "SYN_MATHS", "Contrôle fractions", LocalDate.now().minusDays(5))));

        env.runMain("--mode", "views");

        AppConfig.SubjectColorsConfig colors = new AppConfig.SubjectColorsConfig();
        colors.getOverrides().put("SYN_MATHS", "#2e7d32");
        String accent = SubjectColorResolver.from(colors, timetable).styleAttr("SYN_MATHS");

        for (String html : List.of(
                env.read(env.timetableViewDir, day1 + ".html"),
                env.read(env.assignmentViewDir, "index.html"),
                env.read(env.evaluationViewDir, "index.html"))) {
            assertTrue(html.contains("🧮"), "configured icon appears in every view");
            assertTrue(html.contains(accent), "colour override appears in every view");
        }
    }

    @Test
    void disabledViewIsSkippedAndPortalOmitsIt() {
        env.assignmentViewEnabled = false;
        env.writeConfig();

        env.seedLatest("timetable", List.of(
                lesson("syn-t1", "SYN_MATHS", "DURAND A.", "B12",
                        day0.atTime(10, 0), day0.atTime(11, 0))));

        env.runMain("--mode", "views");

        assertTrue(Files.exists(env.timetableViewDir.resolve("index.html")));
        assertFalse(Files.exists(env.assignmentViewDir.resolve("index.html")),
                "disabled assignment view must not be generated");

        String portal = env.readPortal();
        assertTrue(portal.contains("Emploi du temps"));
        assertFalse(portal.contains("Devoirs"), "portal must omit the disabled section");
    }

    @Test
    void featureDisabledButViewEnabled_skipsViewLikeThePortalDoes() {
        // A view is active only when its feature flag AND *View.enabled are set — in views mode
        // as in fetch and diff, and for rendering as for the portal card.
        env.featSchoolLife = false;
        env.writeConfig();
        env.seedLatest("timetable", List.of(
                lesson("syn-t1", "SYN_MATHS", "DURAND A.", "B12", day0.atTime(10, 0), day0.atTime(11, 0))));
        env.seedLatest("assignments", List.of());
        env.seedLatest("school-life", List.of(
                schoolLifeEvent("syn-sl1", SchoolLifeEvent.EventType.DELAY,
                        LocalDate.now().minusDays(3), "Retard bus synthétique")));

        env.runMain("--mode", "views");

        assertFalse(Files.exists(env.schoolLifeViewDir.resolve("index.html")));
        assertFalse(env.readPortal().contains("Vie scolaire"));
    }

    @Test
    void missingOptionalSnapshotsAreSkippedNotFatal() {
        env.writeConfig();
        env.seedLatest("timetable", List.of(
                lesson("syn-t1", "SYN_MATHS", "DURAND A.", "B12", day0.atTime(10, 0), day0.atTime(11, 0))));
        env.seedLatest("assignments", List.<Assignment>of());

        env.runMain("--mode", "views");

        assertTrue(Files.exists(env.timetableViewDir.resolve("index.html")));
        assertTrue(Files.exists(env.assignmentViewDir.resolve("index.html")));
        assertFalse(Files.exists(env.evaluationViewDir.resolve("index.html")));
        assertFalse(Files.exists(env.schoolLifeViewDir.resolve("index.html")));
    }

    @Test
    void featureOverrideLimitsViewsModeToTheSelectedTypes() {
        env.writeConfig();
        env.seedLatest("assignments", List.of(
                assignment("syn-a1", "SYN_MATHS", "Exercices page 12", day1, null)));

        // No timetable snapshot: with the timetable feature off, views mode must not need one.
        env.runMain("--mode", "views", "--features", "assignments");

        assertTrue(Files.exists(env.assignmentViewDir.resolve("index.html")));
        assertFalse(Files.exists(env.timetableViewDir.resolve("index.html")));
    }

    @Test
    void missingTimetableSnapshotFailsWithClearError() {
        env.writeConfig();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> env.runMain("--mode", "views"));
        assertTrue(ex.getMessage().contains("No timetable snapshot found"),
                "unexpected message: " + ex.getMessage());
    }

    @Test
    void missingAssignmentSnapshotFailsWithClearError() {
        env.writeConfig();
        env.seedLatest("timetable", List.of());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> env.runMain("--mode", "views"));
        assertTrue(ex.getMessage().contains("No assignments snapshot found"),
                "unexpected message: " + ex.getMessage());
    }
}
