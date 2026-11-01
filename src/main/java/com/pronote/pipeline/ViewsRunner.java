package com.pronote.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pronote.config.AppConfig;
import com.pronote.config.ManualEntryLoader;
import com.pronote.config.SubjectEnricher;
import com.pronote.domain.Assignment;
import com.pronote.domain.CompetenceEvaluation;
import com.pronote.domain.SchoolLifeEvent;
import com.pronote.domain.TimetableEntry;
import com.pronote.persistence.SnapshotStore;
import com.pronote.scraper.AssignmentTeacherResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Offline mode: regenerate the static HTML views from the last snapshots, without touching
 * the network, the diff state, or notifications.
 *
 * <p>Manual entries are re-read from YAML on every run: entries persisted into the snapshot by
 * a previous fetch (recognisable by their {@code manual:} ID prefix) are stripped first, so YAML
 * edits take effect without a full fetch. Subject enrichment is re-applied too, so edits to
 * {@code subjectEnrichment.rules} show up without a fetch.
 */
public final class ViewsRunner {

    private static final Logger log = LoggerFactory.getLogger(ViewsRunner.class);

    private final AppConfig config;
    private final Path dataDir;

    public ViewsRunner(AppConfig config, Path dataDir) {
        this.config = config;
        this.dataDir = dataDir;
    }

    public void run() {
        ViewPipeline views = new ViewPipeline(config);
        if (!views.anyViewEnabled()) {
            log.info("All views disabled in config — nothing to do.");
            return;
        }

        AppConfig.FeaturesConfig features = config.getFeatures();
        SnapshotStore snapshotStore = new SnapshotStore(dataDir, config.getData().getArchiveRetainDays());
        SubjectEnricher enricher = new SubjectEnricher(config.getSubjectEnrichment());
        ManualEntryLoader.ManualEntries manualEntries = ManualEntryLoader.load(
                Path.of(config.getManualEntries().getFile()), enricher,
                Path.of(config.getManualEntries().getAttachmentDir()));

        // Assignments annotate the timetable views even when the standalone assignment view is
        // disabled, so load them regardless. Silent if the snapshot file is missing.
        Optional<List<Assignment>> assignmentsSnap =
                snapshotStore.loadLatest("assignments", new TypeReference<>() {});
        List<Assignment> assignmentsData = new ArrayList<>(assignmentsSnap.orElse(List.of()));
        if (features.isAssignments()) {
            assignmentsData = ManualEntryMerger.mergeAssignments(assignmentsData, manualEntries.getAssignments());
            // This is also what makes `make views` pick up a newly-added screenshot offline.
            ManualEntryMerger.stageAttachments(dataDir, assignmentsData);
        }

        // The timetable feeds the timetable views, the upcoming evals of the assignment view,
        // and the subject colours/icons of every view.
        Optional<List<TimetableEntry>> timetableSnap =
                snapshotStore.loadLatest("timetable", new TypeReference<>() {});
        List<TimetableEntry> timetableData = new ArrayList<>(timetableSnap.orElse(List.of()));
        if (features.isTimetable()) {
            timetableData = ManualEntryMerger.mergeUpcomingEvals(timetableData, manualEntries.getUpcomingEvals());
        }

        // Re-apply subject enrichment once, before any view is rendered, so the timetable and
        // assignment views agree on enrichedSubject — including for snapshots saved before
        // subjectEnrichment.rules was last edited.
        new AssignmentTeacherResolver(enricher).reEnrichFromConfig(assignmentsData, timetableData);

        if (views.timetableEnabled()) {
            if (timetableSnap.isEmpty()) {
                throw new RuntimeException("No timetable snapshot found at "
                        + dataDir.resolve("snapshots/timetable/latest.json")
                        + " — run 'make run' first to fetch data.");
            }
            log.info("Regenerating timetable views from snapshot ({} entries)...", timetableData.size());
            views.renderTimetable(timetableData, assignmentsData);
        }

        if (views.assignmentsEnabled()) {
            if (assignmentsSnap.isEmpty()) {
                throw new RuntimeException("No assignments snapshot found at "
                        + dataDir.resolve("snapshots/assignments/latest.json")
                        + " — run 'make run' first to fetch data.");
            }
            log.info("Regenerating assignment view from snapshot ({} entries)...", assignmentsData.size());
            views.renderAssignments(assignmentsData, timetableData);
        }

        if (views.evaluationsEnabled()) {
            // Past competence results only — never manual entries. See the terminology section
            // of CLAUDE.md: manual "evaluations" are upcoming timetable events.
            Optional<List<CompetenceEvaluation>> evaluationsSnap =
                    snapshotStore.loadLatest("evaluations", new TypeReference<>() {});
            if (evaluationsSnap.isEmpty()) {
                log.warn("No evaluations snapshot found — skipping evaluation view regeneration.");
            } else {
                List<CompetenceEvaluation> evaluationsData = new ArrayList<>(evaluationsSnap.get());
                evaluationsData.forEach(e -> e.setEnrichedSubject(enricher.enrich(e.getSubject(), e.getTeacher())));
                log.info("Regenerating evaluation views from snapshot ({} entries)...", evaluationsData.size());
                views.renderEvaluations(evaluationsData, timetableData);
            }
        }

        if (views.schoolLifeEnabled()) {
            Optional<List<SchoolLifeEvent>> schoolLife =
                    snapshotStore.loadLatest("school-life", new TypeReference<>() {});
            if (schoolLife.isEmpty()) {
                log.warn("No school-life snapshot found — skipping school-life view regeneration.");
            } else {
                log.info("Regenerating school-life view from snapshot ({} entries)...", schoolLife.get().size());
                views.renderSchoolLife(schoolLife.get());
            }
        }

        String portalHtml = views.writePortalIndex();
        log.info("Views regenerated successfully.");

        views.publish(dataDir, portalHtml);
    }
}
