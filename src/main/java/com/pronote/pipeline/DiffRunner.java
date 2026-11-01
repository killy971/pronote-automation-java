package com.pronote.pipeline;

import com.pronote.config.AppConfig;
import com.pronote.domain.DataSet;
import com.pronote.persistence.DiffBundle;
import com.pronote.persistence.DiffReporter;
import com.pronote.persistence.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Offline mode: re-run the diff between the most recent archive entry (previous run) and
 * {@code latest.json} (current run), write the diff report, re-notify, and re-render views.
 * Useful to preview a notification ({@code --dry-run}) without touching Pronote.
 */
public final class DiffRunner {

    private static final Logger log = LoggerFactory.getLogger(DiffRunner.class);

    private final AppConfig config;
    private final Path dataDir;
    private final boolean dryRun;

    public DiffRunner(AppConfig config, Path dataDir, boolean dryRun) {
        this.config = config;
        this.dataDir = dataDir;
        this.dryRun = dryRun;
    }

    public void run() {
        AppConfig.FeaturesConfig features = config.getFeatures();
        SnapshotStore snapshotStore = new SnapshotStore(dataDir, config.getData().getArchiveRetainDays());

        DataSet current = SnapshotSet.latest(snapshotStore, features).orEmpty();
        SnapshotSet previous = SnapshotSet.previous(snapshotStore, features);

        boolean isFirstRun = previous.allAbsent();
        if (isFirstRun) {
            log.info("No previous snapshots in archive — nothing to diff against. Run 'make run' at least twice first.");
        }

        DiffBundle diffs = new DiffStage().diff(previous, current);
        new DiffReporter(dataDir).record(diffs, isFirstRun);
        NotificationDispatcher.dispatchIfChanged(config, dryRun, diffs, isFirstRun, current.timetable());

        // Unlike fetch, an empty list leaves the existing view in place: it means there is no
        // snapshot to render from, not that the school emptied the data.
        ViewPipeline views = new ViewPipeline(config);
        if (views.timetableEnabled() && !current.timetable().isEmpty()) {
            log.info("Regenerating timetable HTML views...");
            views.renderTimetable(current.timetable(), current.assignments());
        }
        if (views.assignmentsEnabled() && !current.assignments().isEmpty()) {
            log.info("Regenerating assignment HTML view...");
            views.renderAssignments(current.assignments(), current.timetable());
        }
        if (views.evaluationsEnabled() && !current.evaluations().isEmpty()) {
            log.info("Regenerating evaluation HTML views...");
            views.renderEvaluations(current.evaluations(), current.timetable());
        }
        if (views.schoolLifeEnabled() && !current.schoolLife().isEmpty()) {
            log.info("Regenerating school-life HTML view...");
            views.renderSchoolLife(current.schoolLife());
        }
        views.writePortalIndex();

        log.info("Offline diff completed.");
    }
}
