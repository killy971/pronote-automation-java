package com.pronote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pronote.auth.PronoteAuthenticator;
import com.pronote.auth.PronoteSession;
import com.pronote.auth.SessionStore;
import com.pronote.client.PronoteHttpClient;
import com.pronote.config.AppConfig;
import com.pronote.config.ConfigLoader;
import com.pronote.config.ManualEntryLoader;
import com.pronote.config.SubjectEnricher;
import com.pronote.domain.Assignment;
import com.pronote.domain.EntryStatus;
import com.pronote.domain.CompetenceEvaluation;
import com.pronote.domain.Grade;
import com.pronote.domain.SchoolLifeEvent;
import com.pronote.domain.TimetableEntry;
import com.pronote.notification.*;
import com.pronote.persistence.DiffEngine;
import com.pronote.persistence.DiffReporter;
import com.pronote.persistence.DiffResult;
import com.pronote.persistence.FieldChange;
import com.pronote.persistence.SnapshotStore;
import com.pronote.persistence.TimetableDiffFilter;
import com.pronote.safety.LockoutGuard;
import com.pronote.safety.RateLimiter;
import com.pronote.domain.AttachmentRef;
import com.pronote.scraper.AssignmentScraper;
import com.pronote.scraper.AttachmentDownloader;
import com.pronote.scraper.EvaluationScraper;
import com.pronote.scraper.GradeScraper;
import com.pronote.scraper.SchoolLifeScraper;
import com.pronote.scraper.TimetableScraper;
import com.pronote.views.AssignmentViewRenderer;
import com.pronote.views.EvaluationViewRenderer;
import com.pronote.views.GitPublisher;
import com.pronote.views.PortalIndexHtmlGenerator;
import com.pronote.views.SchoolLifeViewRenderer;
import com.pronote.views.TimetableViewRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Entry point. Runs the full Pronote data pipeline:
 * <ol>
 *   <li>Load config and check lockout</li>
 *   <li>Establish or reuse session</li>
 *   <li>Fetch assignments and timetable</li>
 *   <li>Diff against previous snapshots</li>
 *   <li>Write persistent diff report (always, regardless of notification settings)</li>
 *   <li>Send notifications if channels are enabled and changes were found</li>
 *   <li>Persist new snapshots</li>
 * </ol>
 *
 * <p>On the <strong>first run</strong> (no previous snapshot), the diff is skipped and
 * only the baseline snapshot is saved. No notification is sent.
 *
 * <p>Exits 0 on success, 1 on any error.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            run(args);
            System.exit(0);
        } catch (Exception e) {
            log.error("Job failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void run(String[] args) {
        // ---- 1. Load configuration ----------------------------------------
        Path configPath = resolveConfigPath(args);
        AppConfig config = ConfigLoader.load(configPath);
        resolveFeatureOverride(args).ifPresent(enabled -> applyFeatureOverride(config.getFeatures(), enabled));
        Path dataDir = Path.of(config.getData().getDirectory());
        boolean dryRun = isDryRun(args);
        if (dryRun) log.info("--dry-run active: notifications will be previewed in the log, not sent.");

        String mode = resolveMode(args);
        switch (mode) {
            case "fetch" -> runFetch(config, dataDir, dryRun);
            case "views" -> runViews(config, dataDir);
            case "diff"  -> runDiff(config, dataDir, dryRun);
            default -> throw new IllegalArgumentException(
                    "Unknown --mode '" + mode + "'. Valid modes: fetch, views, diff");
        }
    }

    // -------------------------------------------------------------------------
    // Mode: fetch (default — full online pipeline)
    // -------------------------------------------------------------------------

    private static void runFetch(AppConfig config, Path dataDir, boolean dryRun) {
        // ---- 2. Check lockout ---------------------------------------------
        LockoutGuard lockoutGuard = new LockoutGuard(dataDir, config.getSafety().getMaxLoginFailures());
        lockoutGuard.checkAndThrowIfLocked();

        // ---- 3. Establish session -----------------------------------------
        RateLimiter rateLimiter = new RateLimiter(
                config.getSafety().getMinDelayMs(),
                config.getSafety().getJitterMs());
        PronoteHttpClient httpClient = new PronoteHttpClient(rateLimiter);
        SessionStore sessionStore = new SessionStore(dataDir);
        NotificationService errorNotifier = buildErrorNotifier(config);
        PronoteSession session;
        try {
            session = acquireSession(config, httpClient, sessionStore, lockoutGuard);
        } catch (RuntimeException e) {
            sendErrorAlert(errorNotifier, "authentification", e.getMessage());
            throw e;
        }

        AppConfig.FeaturesConfig features = config.getFeatures();
        SubjectEnricher subjectEnricher = new SubjectEnricher(config.getSubjectEnrichment());

        // ---- 4. Fetch data (only for enabled types) -----------------------
        AssignmentScraper assignmentScraper = new AssignmentScraper(subjectEnricher);
        List<Assignment> assignments = List.of();
        if (features.isAssignments()) {
            log.info("Fetching assignments...");
            try {
                assignments = assignmentScraper.fetch(session, httpClient, config);
            } catch (RuntimeException e) {
                sendErrorAlert(errorNotifier, "récupération des devoirs", e.getMessage());
                throw e;
            }
        }

        TimetableScraper timetableScraper = new TimetableScraper(subjectEnricher);
        List<TimetableEntry> timetable = List.of();
        if (features.isTimetable()) {
            log.info("Fetching timetable...");
            try {
                timetable = timetableScraper.fetch(session, httpClient, config);
            } catch (RuntimeException e) {
                sendErrorAlert(errorNotifier, "emploi du temps", e.getMessage());
                throw e;
            }
        }

        // Re-enrich assignments using timetable teacher info.
        // The Pronote homework API does not include teacher data, so AssignmentScraper applies
        // subject-only enrichment rules. Here we cross-reference each assignment's subject and
        // assignedDate against the timetable to resolve the teacher, then re-apply enrichment
        // so that teacher-specific rules (e.g. the Histoire/Géographie split) also fire.
        reEnrichAssignmentsWithTeacher(assignments, timetable, subjectEnricher);

        GradeScraper gradeScraper = new GradeScraper(subjectEnricher);
        List<Grade> grades = List.of();
        if (features.isGrades()) {
            log.info("Fetching grades...");
            try {
                grades = gradeScraper.fetch(session, httpClient, session.getPeriods());
            } catch (RuntimeException e) {
                sendErrorAlert(errorNotifier, "notes", e.getMessage());
                throw e;
            }
        } else {
            log.debug("Grades feature disabled — skipping.");
        }

        EvaluationScraper evaluationScraper = new EvaluationScraper(subjectEnricher);
        List<CompetenceEvaluation> evaluations = List.of();
        if (features.isEvaluations()) {
            log.info("Fetching competence evaluations...");
            try {
                evaluations = evaluationScraper.fetch(session, httpClient, session.getPeriods());
            } catch (RuntimeException e) {
                sendErrorAlert(errorNotifier, "évaluations", e.getMessage());
                throw e;
            }
        } else {
            log.debug("Evaluations feature disabled — skipping.");
        }

        SchoolLifeScraper schoolLifeScraper = new SchoolLifeScraper(subjectEnricher);
        List<SchoolLifeEvent> schoolLife = List.of();
        if (features.isSchoolLife()) {
            log.info("Fetching vie scolaire events...");
            try {
                schoolLife = schoolLifeScraper.fetch(session, httpClient, session.getPeriods());
            } catch (RuntimeException e) {
                sendErrorAlert(errorNotifier, "vie scolaire", e.getMessage());
                throw e;
            }
        } else {
            log.debug("School-life feature disabled — skipping.");
        }

        // ---- 4b. Merge manual entries (from manual-entries.yaml, if present) ----
        ManualEntryLoader.ManualEntries manualEntries = ManualEntryLoader.load(
                Path.of(config.getManualEntries().getFile()), subjectEnricher);
        if (features.isAssignments() && !manualEntries.getAssignments().isEmpty()) {
            List<Assignment> merged = new ArrayList<>(assignments);
            merged.addAll(manualEntries.getAssignments());
            assignments = merged;
        }
        if (features.isEvaluations() && !manualEntries.getEvaluations().isEmpty()) {
            List<CompetenceEvaluation> merged = new ArrayList<>(evaluations);
            merged.addAll(manualEntries.getEvaluations());
            evaluations = merged;
        }

        // ---- 5. Load previous snapshots (only for enabled types) ----------
        SnapshotStore snapshotStore = new SnapshotStore(dataDir, config.getData().getArchiveRetainDays());

        Optional<List<Assignment>> prevAssignments = features.isAssignments()
                ? snapshotStore.loadLatest("assignments", new TypeReference<>() {}) : Optional.empty();
        Optional<List<TimetableEntry>> prevTimetable = features.isTimetable()
                ? snapshotStore.loadLatest("timetable", new TypeReference<>() {}) : Optional.empty();
        Optional<List<Grade>> prevGrades = features.isGrades()
                ? snapshotStore.loadLatest("grades", new TypeReference<>() {}) : Optional.empty();
        Optional<List<CompetenceEvaluation>> prevEvaluations = features.isEvaluations()
                ? snapshotStore.loadLatest("evaluations", new TypeReference<>() {}) : Optional.empty();
        Optional<List<SchoolLifeEvent>> prevSchoolLife = features.isSchoolLife()
                ? snapshotStore.loadLatest("school-life", new TypeReference<>() {}) : Optional.empty();

        // First run: all enabled types have no snapshot yet.
        boolean isFirstRun = prevAssignments.isEmpty() && prevTimetable.isEmpty()
                && prevGrades.isEmpty() && prevEvaluations.isEmpty() && prevSchoolLife.isEmpty();

        // ---- 6. Diff -------------------------------------------------------
        // For data types enabled for the first time on an existing installation,
        // treat the missing snapshot as a silent baseline (empty diff, no notification).
        DiffEngine diffEngine = new DiffEngine();
        DiffResult<Assignment> assignmentDiff = prevAssignments.isPresent()
                ? diffEngine.diff(prevAssignments.get(), assignments) : emptyDiff();
        DiffResult<TimetableEntry> timetableDiff = prevTimetable.isPresent()
                ? diffEngine.diff(prevTimetable.get(), timetable) : emptyDiff();

        // Apply smart timetable filtering: suppress past items and bulk normal additions
        // for the furthest-future week when it first enters the retrieval window.
        if (features.isTimetable() && prevTimetable.isPresent()) {
            LocalDate furthestWeekStart = LocalDate.now()
                    .plusWeeks(config.getPronote().getWeeksAhead())
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            TimetableDiffFilter timetableFilter = new TimetableDiffFilter();
            timetableDiff = timetableFilter.filter(
                    timetableDiff,
                    prevTimetable.get(),
                    furthestWeekStart,
                    LocalDateTime.now());
        }
        DiffResult<Grade> gradeDiff = prevGrades.isPresent()
                ? diffEngine.diff(prevGrades.get(), grades) : emptyDiff();
        DiffResult<CompetenceEvaluation> evaluationDiff = prevEvaluations.isPresent()
                ? diffEngine.diff(prevEvaluations.get(), evaluations) : emptyDiff();
        DiffResult<SchoolLifeEvent> schoolLifeDiff = prevSchoolLife.isPresent()
                ? diffEngine.diff(prevSchoolLife.get(), schoolLife) : emptyDiff();

        // ---- 7. Download assignment attachments ----------------------------
        if (features.isAssignments()) {
            Path attachmentsDir = dataDir.resolve("snapshots").resolve("assignments").resolve("attachments");
            AttachmentDownloader attachmentDownloader = new AttachmentDownloader(httpClient, session, attachmentsDir);
            attachmentDownloader.processAttachments(assignments);
        }

        // ---- 8. Write persistent diff report (always) ---------------------
        DiffReporter reporter = new DiffReporter(dataDir);
        reporter.record(assignmentDiff, timetableDiff, gradeDiff, evaluationDiff,
                schoolLifeDiff, isFirstRun);

        if (!isFirstRun && (!assignmentDiff.isEmpty() || !timetableDiff.isEmpty()
                || !gradeDiff.isEmpty() || !evaluationDiff.isEmpty() || !schoolLifeDiff.isEmpty())) {
            boolean notificationsEnabled = config.getNotifications().getNtfy().isEnabled()
                    || config.getNotifications().getEmail().isEnabled();
            if (dryRun || notificationsEnabled) {
                log.info("Changes detected — {}...", dryRun ? "previewing notification (dry-run)" : "sending notifications");
                NotificationService notifier = buildNotifier(config, dryRun);
                NotificationPayload payload = buildPayload(assignmentDiff, timetableDiff,
                        gradeDiff, evaluationDiff, schoolLifeDiff);
                try {
                    notifier.send(payload);
                } catch (NotificationService.NotificationException e) {
                    // Non-fatal — diff report was already written
                    log.error("Notification delivery failed: {}", e.getMessage());
                }
            } else {
                log.info("Changes detected — notifications disabled, see diff-latest.json and diff-history.log.");
            }
        }

        // ---- 9. Persist new snapshots (only for enabled types) ------------
        if (features.isAssignments())  snapshotStore.saveSnapshot("assignments", assignments);
        if (features.isTimetable())    snapshotStore.saveSnapshot("timetable",   timetable);
        if (features.isGrades())       snapshotStore.saveSnapshot("grades",      grades);
        if (features.isEvaluations())  snapshotStore.saveSnapshot("evaluations", evaluations);
        if (features.isSchoolLife())   snapshotStore.saveSnapshot("school-life", schoolLife);

        // ---- 10. Generate static HTML timetable views ---------------------
        if (features.isTimetable() && config.getTimetableView().isEnabled()) {
            log.info("Generating timetable HTML views...");
            new TimetableViewRenderer(config.getTimetableView()).render(timetable, assignments);
        }

        // ---- 11. Generate static HTML assignment view ---------------------
        if (features.isAssignments() && config.getAssignmentView().isEnabled()) {
            log.info("Generating assignment HTML view...");
            new AssignmentViewRenderer(config.getAssignmentView()).render(assignments, timetable);
        }

        // ---- 12. Generate static HTML evaluation view --------------------
        if (features.isEvaluations() && config.getEvaluationView().isEnabled()) {
            log.info("Generating evaluation HTML views...");
            EvaluationViewRenderer evalRenderer = new EvaluationViewRenderer(config.getEvaluationView());
            evalRenderer.render(evaluations);
            evalRenderer.renderSummary(evaluations);
        }

        // ---- 13. Generate static HTML school-life view -------------------
        if (features.isSchoolLife() && config.getSchoolLifeView().isEnabled()) {
            log.info("Generating school-life HTML view...");
            new SchoolLifeViewRenderer(config.getSchoolLifeView()).render(schoolLife);
        }

        // ---- 14. Generate portal index -----------------------------------
        String portalHtml = new PortalIndexHtmlGenerator()
                .generate(buildPortalSections(config, features));
        writePortalIndex(portalHtml);

        // ---- 15. Publish view files to GitHub Pages repo (optional) -------
        if (config.getViewPublish().isEnabled()) {
            Map<String, Path> viewDirs = new LinkedHashMap<>();
            Map<Path, Path> recursiveMirrors = new LinkedHashMap<>();
            if (features.isTimetable() && config.getTimetableView().isEnabled()) {
                viewDirs.put("timetable", Path.of(config.getTimetableView().getOutputDirectory())
                        .toAbsolutePath().normalize());
            }
            if (features.isAssignments() && config.getAssignmentView().isEnabled()) {
                Path assignViewDir = Path.of(config.getAssignmentView().getOutputDirectory())
                        .toAbsolutePath().normalize();
                viewDirs.put("assignments", assignViewDir);
                if (config.getViewPublish().isPublishAttachments()) {
                    Path attachSource = dataDir.resolve("snapshots/assignments/attachments")
                            .toAbsolutePath().normalize();
                    Path relFromView = assignViewDir.relativize(attachSource);
                    Path destRelToRepo = Path.of(config.getViewPublish().getTargetSubdir())
                            .resolve("assignments").resolve(relFromView).normalize();
                    recursiveMirrors.put(attachSource, destRelToRepo);
                }
            }
            if (features.isEvaluations() && config.getEvaluationView().isEnabled()) {
                viewDirs.put("evaluations", Path.of(config.getEvaluationView().getOutputDirectory())
                        .toAbsolutePath().normalize());
            }
            if (features.isSchoolLife() && config.getSchoolLifeView().isEnabled()) {
                viewDirs.put("school-life", Path.of(config.getSchoolLifeView().getOutputDirectory())
                        .toAbsolutePath().normalize());
            }
            log.info("Publishing view files to GitHub Pages repo...");
            new GitPublisher().publish(viewDirs, recursiveMirrors,
                    Map.of("index.html", portalHtml), config.getViewPublish());
        }

        log.info("Job completed successfully.");
    }

    // -------------------------------------------------------------------------
    // Mode: views — regenerate HTML from the last timetable snapshot (offline)
    // -------------------------------------------------------------------------

    private static void runViews(AppConfig config, Path dataDir) {
        AppConfig.FeaturesConfig features = config.getFeatures();
        boolean timetableViewEnabled   = config.getTimetableView().isEnabled();
        boolean assignmentViewEnabled  = config.getAssignmentView().isEnabled();
        boolean evaluationViewEnabled  = config.getEvaluationView().isEnabled();
        boolean schoolLifeViewEnabled  = config.getSchoolLifeView().isEnabled();
        if (!timetableViewEnabled && !assignmentViewEnabled
                && !evaluationViewEnabled && !schoolLifeViewEnabled) {
            log.info("All views disabled in config — nothing to do.");
            return;
        }

        SnapshotStore snapshotStore = new SnapshotStore(dataDir, config.getData().getArchiveRetainDays());

        // Pre-load assignments snapshot — used to annotate timetable views even when the
        // standalone assignment view is disabled. Silent if the snapshot file is missing.
        Optional<List<Assignment>> assignmentsSnap =
                snapshotStore.loadLatest("assignments", new TypeReference<>() {});
        List<Assignment> assignmentsData = assignmentsSnap.orElse(List.of());

        // Pre-load timetable snapshot — needed for timetable views and to inject upcoming
        // competence evaluations into the assignment view.
        Optional<List<TimetableEntry>> timetableSnap =
                snapshotStore.loadLatest("timetable", new TypeReference<>() {});
        List<TimetableEntry> timetableData = timetableSnap.orElse(List.of());

        if (timetableViewEnabled) {
            if (timetableSnap.isEmpty()) {
                throw new RuntimeException("No timetable snapshot found at "
                        + dataDir.resolve("snapshots/timetable/latest.json")
                        + " — run 'make run' first to fetch data.");
            }
            log.info("Regenerating timetable views from snapshot ({} entries)...", timetableData.size());
            new TimetableViewRenderer(config.getTimetableView()).render(timetableData, assignmentsData);
        }

        if (assignmentViewEnabled) {
            if (assignmentsSnap.isEmpty()) {
                throw new RuntimeException("No assignments snapshot found at "
                        + dataDir.resolve("snapshots/assignments/latest.json")
                        + " — run 'make run' first to fetch data.");
            }
            log.info("Regenerating assignment view from snapshot ({} entries)...", assignmentsData.size());
            new AssignmentViewRenderer(config.getAssignmentView()).render(assignmentsData, timetableData);
        }

        if (evaluationViewEnabled) {
            Optional<List<CompetenceEvaluation>> evaluations =
                    snapshotStore.loadLatest("evaluations", new TypeReference<>() {});
            if (evaluations.isEmpty()) {
                log.warn("No evaluations snapshot found — skipping evaluation view regeneration.");
            } else {
                // Re-apply subject enrichment from current config rules so that snapshots
                // saved before a rule was added (or before teacherPrefixes was configured)
                // still render with correct enrichedSubject values in --mode views.
                SubjectEnricher enricher = new SubjectEnricher(config.getSubjectEnrichment());
                evaluations.get().forEach(e ->
                        e.setEnrichedSubject(enricher.enrich(e.getSubject(), e.getTeacher())));
                log.info("Regenerating evaluation views from snapshot ({} entries)...", evaluations.get().size());
                EvaluationViewRenderer evalRenderer = new EvaluationViewRenderer(config.getEvaluationView());
                evalRenderer.render(evaluations.get());
                evalRenderer.renderSummary(evaluations.get());
            }
        }

        if (schoolLifeViewEnabled) {
            Optional<List<SchoolLifeEvent>> schoolLife =
                    snapshotStore.loadLatest("school-life", new TypeReference<>() {});
            if (schoolLife.isEmpty()) {
                log.warn("No school-life snapshot found — skipping school-life view regeneration.");
            } else {
                log.info("Regenerating school-life view from snapshot ({} entries)...", schoolLife.get().size());
                new SchoolLifeViewRenderer(config.getSchoolLifeView()).render(schoolLife.get());
            }
        }

        // Portal index (always regenerated; reflects currently enabled sections)
        String portalHtml = new PortalIndexHtmlGenerator()
                .generate(buildPortalSections(config, features));
        writePortalIndex(portalHtml);

        log.info("Views regenerated successfully.");

        // Publish to GitHub Pages if configured
        if (config.getViewPublish().isEnabled()) {
            Map<String, Path> viewDirs = new LinkedHashMap<>();
            Map<Path, Path> recursiveMirrors = new LinkedHashMap<>();
            if (timetableViewEnabled) {
                viewDirs.put("timetable", Path.of(config.getTimetableView().getOutputDirectory())
                        .toAbsolutePath().normalize());
            }
            if (assignmentViewEnabled) {
                Path assignViewDir = Path.of(config.getAssignmentView().getOutputDirectory())
                        .toAbsolutePath().normalize();
                viewDirs.put("assignments", assignViewDir);
                if (config.getViewPublish().isPublishAttachments()) {
                    Path attachSource = dataDir.resolve("snapshots/assignments/attachments")
                            .toAbsolutePath().normalize();
                    Path relFromView = assignViewDir.relativize(attachSource);
                    Path destRelToRepo = Path.of(config.getViewPublish().getTargetSubdir())
                            .resolve("assignments").resolve(relFromView).normalize();
                    recursiveMirrors.put(attachSource, destRelToRepo);
                }
            }
            if (evaluationViewEnabled) {
                viewDirs.put("evaluations", Path.of(config.getEvaluationView().getOutputDirectory())
                        .toAbsolutePath().normalize());
            }
            if (schoolLifeViewEnabled) {
                viewDirs.put("school-life", Path.of(config.getSchoolLifeView().getOutputDirectory())
                        .toAbsolutePath().normalize());
            }
            log.info("Publishing view files to GitHub Pages repo...");
            new GitPublisher().publish(viewDirs, recursiveMirrors,
                    Map.of("index.html", portalHtml), config.getViewPublish());
        }
    }

    // -------------------------------------------------------------------------
    // Mode: diff — re-run diff between last two snapshots + notify (offline)
    // -------------------------------------------------------------------------

    private static void runDiff(AppConfig config, Path dataDir, boolean dryRun) {
        AppConfig.FeaturesConfig features = config.getFeatures();
        SnapshotStore snapshotStore = new SnapshotStore(dataDir, config.getData().getArchiveRetainDays());

        // Current = latest.json; previous = most recent archive entry
        Optional<List<Assignment>> currentAssignments = features.isAssignments()
                ? snapshotStore.loadLatest("assignments", new TypeReference<>() {}) : Optional.empty();
        Optional<List<TimetableEntry>> currentTimetable = features.isTimetable()
                ? snapshotStore.loadLatest("timetable", new TypeReference<>() {}) : Optional.empty();
        Optional<List<Grade>> currentGrades = features.isGrades()
                ? snapshotStore.loadLatest("grades", new TypeReference<>() {}) : Optional.empty();
        Optional<List<CompetenceEvaluation>> currentEvaluations = features.isEvaluations()
                ? snapshotStore.loadLatest("evaluations", new TypeReference<>() {}) : Optional.empty();
        Optional<List<SchoolLifeEvent>> currentSchoolLife = features.isSchoolLife()
                ? snapshotStore.loadLatest("school-life", new TypeReference<>() {}) : Optional.empty();

        Optional<List<Assignment>> prevAssignments = features.isAssignments()
                ? snapshotStore.loadPrevious("assignments", new TypeReference<>() {}) : Optional.empty();
        Optional<List<TimetableEntry>> prevTimetable = features.isTimetable()
                ? snapshotStore.loadPrevious("timetable", new TypeReference<>() {}) : Optional.empty();
        Optional<List<Grade>> prevGrades = features.isGrades()
                ? snapshotStore.loadPrevious("grades", new TypeReference<>() {}) : Optional.empty();
        Optional<List<CompetenceEvaluation>> prevEvaluations = features.isEvaluations()
                ? snapshotStore.loadPrevious("evaluations", new TypeReference<>() {}) : Optional.empty();
        Optional<List<SchoolLifeEvent>> prevSchoolLife = features.isSchoolLife()
                ? snapshotStore.loadPrevious("school-life", new TypeReference<>() {}) : Optional.empty();

        boolean isFirstRun = prevAssignments.isEmpty() && prevTimetable.isEmpty()
                && prevGrades.isEmpty() && prevEvaluations.isEmpty() && prevSchoolLife.isEmpty();
        if (isFirstRun) {
            log.info("No previous snapshots in archive — nothing to diff against. Run 'make run' at least twice first.");
        }

        List<Assignment> assignments = currentAssignments.orElse(List.of());
        List<TimetableEntry> timetable = currentTimetable.orElse(List.of());
        List<Grade> grades = currentGrades.orElse(List.of());
        List<CompetenceEvaluation> evaluations = currentEvaluations.orElse(List.of());
        List<SchoolLifeEvent> schoolLife = currentSchoolLife.orElse(List.of());

        DiffEngine diffEngine = new DiffEngine();
        DiffResult<Assignment> assignmentDiff = prevAssignments.isPresent()
                ? diffEngine.diff(prevAssignments.get(), assignments) : emptyDiff();
        DiffResult<TimetableEntry> timetableDiff = prevTimetable.isPresent()
                ? diffEngine.diff(prevTimetable.get(), timetable) : emptyDiff();

        if (features.isTimetable() && prevTimetable.isPresent()) {
            LocalDate furthestWeekStart = LocalDate.now()
                    .plusWeeks(config.getPronote().getWeeksAhead())
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            TimetableDiffFilter timetableFilter = new TimetableDiffFilter();
            timetableDiff = timetableFilter.filter(
                    timetableDiff, prevTimetable.get(), furthestWeekStart, LocalDateTime.now());
        }

        DiffResult<Grade> gradeDiff = prevGrades.isPresent()
                ? diffEngine.diff(prevGrades.get(), grades) : emptyDiff();
        DiffResult<CompetenceEvaluation> evaluationDiff = prevEvaluations.isPresent()
                ? diffEngine.diff(prevEvaluations.get(), evaluations) : emptyDiff();
        DiffResult<SchoolLifeEvent> schoolLifeDiff = prevSchoolLife.isPresent()
                ? diffEngine.diff(prevSchoolLife.get(), schoolLife) : emptyDiff();

        DiffReporter reporter = new DiffReporter(dataDir);
        reporter.record(assignmentDiff, timetableDiff, gradeDiff, evaluationDiff, schoolLifeDiff, isFirstRun);

        if (!isFirstRun && (!assignmentDiff.isEmpty() || !timetableDiff.isEmpty()
                || !gradeDiff.isEmpty() || !evaluationDiff.isEmpty() || !schoolLifeDiff.isEmpty())) {
            boolean notificationsEnabled = config.getNotifications().getNtfy().isEnabled()
                    || config.getNotifications().getEmail().isEnabled();
            if (dryRun || notificationsEnabled) {
                log.info("Changes detected — {}...", dryRun ? "previewing notification (dry-run)" : "sending notifications");
                NotificationService notifier = buildNotifier(config, dryRun);
                NotificationPayload payload = buildPayload(assignmentDiff, timetableDiff,
                        gradeDiff, evaluationDiff, schoolLifeDiff);
                try {
                    notifier.send(payload);
                } catch (NotificationService.NotificationException e) {
                    log.error("Notification delivery failed: {}", e.getMessage());
                }
            } else {
                log.info("Changes detected — notifications disabled, see diff-latest.json and diff-history.log.");
            }
        }

        if (features.isTimetable() && config.getTimetableView().isEnabled() && !timetable.isEmpty()) {
            log.info("Regenerating timetable HTML views...");
            new TimetableViewRenderer(config.getTimetableView()).render(timetable, assignments);
        }

        if (features.isAssignments() && config.getAssignmentView().isEnabled() && !assignments.isEmpty()) {
            log.info("Regenerating assignment HTML view...");
            new AssignmentViewRenderer(config.getAssignmentView()).render(assignments, timetable);
        }

        if (features.isEvaluations() && config.getEvaluationView().isEnabled() && !evaluations.isEmpty()) {
            log.info("Regenerating evaluation HTML views...");
            EvaluationViewRenderer evalRenderer = new EvaluationViewRenderer(config.getEvaluationView());
            evalRenderer.render(evaluations);
            evalRenderer.renderSummary(evaluations);
        }

        if (features.isSchoolLife() && config.getSchoolLifeView().isEnabled() && !schoolLife.isEmpty()) {
            log.info("Regenerating school-life HTML view...");
            new SchoolLifeViewRenderer(config.getSchoolLifeView()).render(schoolLife);
        }

        writePortalIndex(new PortalIndexHtmlGenerator().generate(buildPortalSections(config, features)));

        log.info("Offline diff completed.");
    }

    // -------------------------------------------------------------------------
    // Session management
    // -------------------------------------------------------------------------

    private static PronoteSession acquireSession(AppConfig config, PronoteHttpClient httpClient,
                                                  SessionStore sessionStore, LockoutGuard lockoutGuard) {
        Optional<PronoteSession> existing = sessionStore.load();
        if (existing.isPresent()) {
            log.info("Found existing session, probing...");
            if (httpClient.probe(existing.get())) {
                log.info("Session reuse successful — skipping login.");
                return existing.get();
            }
            log.info("Session probe failed — performing fresh login.");
            sessionStore.delete();
        }

        log.info("Performing full login...");
        PronoteAuthenticator authenticator = new PronoteAuthenticator();
        PronoteSession session;
        try {
            session = authenticator.login(config);
            lockoutGuard.recordSuccess();
        } catch (PronoteAuthenticator.AuthException e) {
            lockoutGuard.recordFailure();
            throw new RuntimeException("Login failed: " + e.getMessage(), e);
        }

        sessionStore.save(session);
        return session;
    }

    // -------------------------------------------------------------------------
    // Notification building
    // -------------------------------------------------------------------------

    /**
     * Returns an ntfy-based notifier for error alerts, or {@code null} if error alerts or ntfy
     * are disabled. Dry-run mode is intentionally ignored here: error alerts are always sent
     * in dry-run (they reflect real failures, not data changes).
     */
    private static NotificationService buildErrorNotifier(AppConfig config) {
        AppConfig.NotificationsConfig nc = config.getNotifications();
        if (!nc.getErrorAlerts().isEnabled() || !nc.getNtfy().isEnabled()) {
            return null;
        }
        return new NtfyNotifier(nc.getNtfy());
    }

    /**
     * Sends a HIGH-priority ntfy alert describing a pipeline failure. Swallows any delivery
     * error so that the original exception continues to propagate normally.
     *
     * @param notifier the error notifier; if {@code null} the call is a no-op
     * @param phase    short human-readable name of the phase that failed (e.g. "authentification")
     * @param detail   the error message; truncated if too long
     */
    private static void sendErrorAlert(NotificationService notifier, String phase, String detail) {
        if (notifier == null) return;
        String body = detail != null ? truncate(detail, 200) : "(aucun détail)";
        NotificationPayload payload = new NotificationPayload(
                "⚠ Pronote : échec — " + phase,
                body,
                NotificationPayload.Priority.HIGH,
                List.of("warning", "pronote"));
        try {
            notifier.send(payload);
            log.info("Error alert sent for phase '{}'", phase);
        } catch (NotificationService.NotificationException e) {
            log.warn("Failed to deliver error alert for phase '{}': {}", phase, e.getMessage());
        }
    }

    private static NotificationService buildNotifier(AppConfig config, boolean dryRun) {
        if (dryRun) {
            return payload -> {
                log.info("┌─ NOTIFICATION PREVIEW ─────────────────────────────────────────");
                log.info("│  Title   : {}", payload.title());
                log.info("│  Priority: {}", payload.priority());
                log.info("│  Tags    : {}", String.join(", ", payload.tags()));
                log.info("│  Body:");
                for (String line : payload.body().split("\n")) {
                    log.info("│    {}", line);
                }
                log.info("└────────────────────────────────────────────────────────────────");
            };
        }
        List<NotificationService> services = new ArrayList<>();
        AppConfig.NotificationsConfig nc = config.getNotifications();
        if (nc.getNtfy().isEnabled())  services.add(new NtfyNotifier(nc.getNtfy()));
        if (nc.getEmail().isEnabled()) services.add(new EmailNotifier(nc.getEmail()));
        return new CompositeNotifier(services);
    }

    private static boolean isDryRun(String[] args) {
        for (String arg : args) {
            if ("--dry-run".equals(arg)) return true;
        }
        return false;
    }

    private static <T extends com.pronote.persistence.Identifiable> DiffResult<T> emptyDiff() {
        return new DiffResult<>(List.of(), List.of(), Map.of());
    }

    private static NotificationPayload buildPayload(DiffResult<Assignment> assignmentDiff,
                                                    DiffResult<TimetableEntry> timetableDiff,
                                                    DiffResult<Grade> gradeDiff,
                                                    DiffResult<CompetenceEvaluation> evaluationDiff,
                                                    DiffResult<SchoolLifeEvent> schoolLifeDiff) {
        // Newly-cancelled entries: modified entries whose status flipped to CANCELLED,
        // plus any added entries already CANCELLED (rare but possible).
        List<TimetableEntry> cancelledEntries = new ArrayList<>();
        for (Map.Entry<TimetableEntry, List<FieldChange>> e : timetableDiff.modified().entrySet()) {
            if (e.getKey().getStatus() == EntryStatus.CANCELLED
                    && e.getValue().stream().anyMatch(fc -> "status".equals(fc.fieldName()))) {
                cancelledEntries.add(e.getKey());
            }
        }
        for (TimetableEntry e : timetableDiff.added()) {
            if (e.getStatus() == EntryStatus.CANCELLED) cancelledEntries.add(e);
        }

        String title = buildNtfyTitle(assignmentDiff, timetableDiff, gradeDiff, evaluationDiff,
                schoolLifeDiff, cancelledEntries);

        int sectionsWithChanges = (assignmentDiff.isEmpty() ? 0 : 1)
                + (timetableDiff.isEmpty() ? 0 : 1)
                + (gradeDiff.isEmpty() ? 0 : 1)
                + (evaluationDiff.isEmpty() ? 0 : 1)
                + (schoolLifeDiff.isEmpty() ? 0 : 1);
        boolean multiSection = sectionsWithChanges > 1;

        StringBuilder body = new StringBuilder();
        if (!assignmentDiff.isEmpty()) {
            if (multiSection) body.append("📚 Devoirs\n");
            appendAssignmentLines(body, assignmentDiff);
            if (multiSection) body.append("\n");
        }
        if (!timetableDiff.isEmpty()) {
            if (multiSection) body.append("📅 Emploi du temps\n");
            appendTimetableLines(body, timetableDiff);
            if (multiSection) body.append("\n");
        }
        if (!gradeDiff.isEmpty()) {
            if (multiSection) body.append("📊 Notes\n");
            appendGradeLines(body, gradeDiff);
            if (multiSection) body.append("\n");
        }
        if (!evaluationDiff.isEmpty()) {
            if (multiSection) body.append("📋 Évaluations\n");
            appendEvaluationLines(body, evaluationDiff);
            if (multiSection) body.append("\n");
        }
        if (!schoolLifeDiff.isEmpty()) {
            if (multiSection) body.append("🏫 Vie scolaire\n");
            appendSchoolLifeLines(body, schoolLifeDiff);
        }

        NotificationPayload.Priority priority =
                (!cancelledEntries.isEmpty() || !timetableDiff.removed().isEmpty()
                        || !schoolLifeDiff.added().isEmpty())
                ? NotificationPayload.Priority.HIGH
                : NotificationPayload.Priority.NORMAL;

        return new NotificationPayload(title, body.toString().trim(), priority,
                List.of("school", "pronote"));
    }

    private static String buildNtfyTitle(DiffResult<Assignment> asgn,
                                          DiffResult<TimetableEntry> tt,
                                          DiffResult<Grade> grades,
                                          DiffResult<CompetenceEvaluation> evals,
                                          DiffResult<SchoolLifeEvent> schoolLife,
                                          List<TimetableEntry> cancelledEntries) {
        List<String> tokens = new ArrayList<>();

        // 1. Cancellations (highest urgency)
        if (!cancelledEntries.isEmpty()) {
            if (cancelledEntries.size() == 1) {
                TimetableEntry e = cancelledEntries.get(0);
                String reason = e.getStatusLabel() != null && !e.getStatusLabel().isBlank()
                        ? e.getStatusLabel() : "annulé";
                tokens.add("✗ " + subject(e) + " " + reason + " · " + fmtDateTime(e.getStartTime()));
            } else {
                tokens.add("✗ " + cancelledEntries.size() + " cours annulés");
            }
        }

        // 2. New grades
        if (!grades.added().isEmpty()) {
            if (grades.added().size() == 1) {
                Grade g = grades.added().get(0);
                tokens.add("📊 " + subject(g) + ": " + g.getValue() + "/" + fmtOutOf(g.getOutOf()));
            } else {
                tokens.add("📊 " + grades.added().size() + " notes");
            }
        }

        // 3. New assignments
        if (!asgn.added().isEmpty()) {
            if (asgn.added().size() == 1) {
                Assignment a = asgn.added().get(0);
                tokens.add("📚 " + subject(a) + " · " + fmtDate(a.getDueDate()));
            } else {
                tokens.add("📚 " + asgn.added().size() + " devoirs");
            }
        }

        // 4. Other timetable changes (non-cancelled additions, removals, other modifications)
        long otherTtChanges = tt.added().stream().filter(e -> e.getStatus() != EntryStatus.CANCELLED).count()
                + tt.removed().size()
                + tt.modified().entrySet().stream()
                    .filter(e -> !cancelledEntries.contains(e.getKey())).count();
        if (otherTtChanges > 0) {
            tokens.add(cancelledEntries.isEmpty()
                    ? "📅 " + otherTtChanges + " modif. EDT"
                    : "📅 +" + otherTtChanges + " modif.");
        }

        // 5. New school life events
        if (!schoolLife.added().isEmpty()) {
            if (schoolLife.added().size() == 1) {
                SchoolLifeEvent e = schoolLife.added().get(0);
                tokens.add("🏫 " + fmtEventType(e.getType()) + " · " + fmtDate(e.getDate()));
            } else {
                tokens.add("🏫 " + schoolLife.added().size() + " événements");
            }
        }

        // 6. New evaluations
        if (!evals.added().isEmpty()) {
            if (evals.added().size() == 1) {
                tokens.add("📋 " + subject(evals.added().get(0)) + " éval.");
            } else {
                tokens.add("📋 " + evals.added().size() + " évals.");
            }
        }

        // 7. Catch-all: modifications/removals only
        if (tokens.isEmpty()) {
            int mods = asgn.modified().size() + tt.modified().size() + grades.modified().size()
                    + evals.modified().size() + schoolLife.modified().size()
                    + asgn.removed().size() + grades.removed().size()
                    + evals.removed().size() + schoolLife.removed().size();
            tokens.add("Pronote: " + mods + " modification(s)");
        }

        String joined = String.join(" · ", tokens.subList(0, Math.min(3, tokens.size())));
        return joined.length() <= 72 ? joined : joined.substring(0, 70) + "…";
    }

    // ---- per-category body renderers ----------------------------------------

    private static void appendAssignmentLines(StringBuilder b, DiffResult<Assignment> diff) {
        for (Assignment a : diff.added()) {
            b.append("+ ").append(subject(a)).append(" · ").append(fmtDate(a.getDueDate()));
            if (a.getDescription() != null && !a.getDescription().isBlank()) {
                b.append(" — ").append(truncate(a.getDescription(), 80));
            }
            List<AttachmentRef> att = a.getAttachments();
            if (att != null && !att.isEmpty()) {
                long files = att.stream().filter(AttachmentRef::isUploadedFile).count();
                long links = att.stream().filter(r -> !r.isUploadedFile()).count();
                if (files == 1) {
                    String fname = att.stream().filter(AttachmentRef::isUploadedFile)
                            .map(AttachmentRef::getFileName).findFirst().orElse("fichier");
                    b.append(" [📎 ").append(truncate(fname, 20)).append("]");
                } else if (files > 1) {
                    b.append(" [📎×").append(files).append("]");
                }
                if (links == 1) {
                    String fname = att.stream().filter(r -> !r.isUploadedFile())
                            .map(AttachmentRef::getFileName).findFirst().orElse("lien");
                    b.append(" [🔗 ").append(truncate(fname, 20)).append("]");
                } else if (links > 1) {
                    b.append(" [🔗×").append(links).append("]");
                }
            }
            b.append("\n");
        }
        for (Assignment a : diff.removed()) {
            b.append("- ").append(subject(a)).append(" · ").append(fmtDate(a.getDueDate()))
                    .append(" [supprimé]\n");
        }
        for (Map.Entry<Assignment, List<FieldChange>> entry : diff.modified().entrySet()) {
            Assignment a = entry.getKey();
            b.append("~ ").append(subject(a)).append(" · ").append(fmtDate(a.getDueDate()));
            List<FieldChange> changes = entry.getValue().stream()
                    .filter(fc -> !"enrichedSubject".equals(fc.fieldName())).toList();
            if (changes.size() == 1) {
                b.append(" — ").append(fmtAssignmentChange(changes.get(0)));
            } else if (!changes.isEmpty()) {
                b.append(" [").append(changes.size()).append(" champs modifiés]");
            }
            b.append("\n");
        }
    }

    private static void appendTimetableLines(StringBuilder b, DiffResult<TimetableEntry> diff) {
        for (TimetableEntry e : diff.added()) {
            String prefix = e.getStatus() == EntryStatus.CANCELLED ? "✗ " : "+ ";
            b.append(prefix).append(subject(e)).append(" — ").append(fmtDateTime(e.getStartTime()));
            if (e.getRoom() != null && !e.getRoom().isBlank()) b.append(" (").append(e.getRoom()).append(")");
            b.append("\n");
        }
        for (TimetableEntry e : diff.removed()) {
            b.append("- ").append(subject(e)).append(" — ").append(fmtDateTime(e.getStartTime()))
                    .append(" [supprimé]\n");
        }
        for (Map.Entry<TimetableEntry, List<FieldChange>> entry : diff.modified().entrySet()) {
            TimetableEntry e = entry.getKey();
            String prefix = e.getStatus() == EntryStatus.CANCELLED ? "✗ " : "~ ";
            b.append(prefix).append(subject(e)).append(" — ").append(fmtDateTime(e.getStartTime()));
            String detail = fmtTimetableChanges(e, entry.getValue());
            if (!detail.isBlank()) b.append(" · ").append(detail);
            b.append("\n");
        }
    }

    private static void appendGradeLines(StringBuilder b, DiffResult<Grade> diff) {
        for (Grade g : diff.added()) {
            b.append("+ ").append(subject(g)).append(": ").append(g.getValue())
                    .append("/").append(fmtOutOf(g.getOutOf()));
            if (g.getComment() != null && !g.getComment().isBlank()) {
                b.append(" — \"").append(truncate(g.getComment(), 40)).append("\"");
            }
            if (g.getCoefficient() != 1.0) {
                b.append(" (coef.").append(fmtCoef(g.getCoefficient())).append(")");
            }
            b.append("\n");
        }
        for (Grade g : diff.removed()) {
            b.append("- ").append(subject(g)).append(" · ").append(fmtDate(g.getDate()))
                    .append(" [supprimé]\n");
        }
        for (Map.Entry<Grade, List<FieldChange>> entry : diff.modified().entrySet()) {
            Grade g = entry.getKey();
            b.append("~ ").append(subject(g)).append(" · ").append(fmtDate(g.getDate()));
            FieldChange valChange = entry.getValue().stream()
                    .filter(fc -> "value".equals(fc.fieldName())).findFirst().orElse(null);
            if (valChange != null) {
                b.append(": ").append(valChange.oldValue()).append("→")
                        .append(valChange.newValue()).append("/").append(fmtOutOf(g.getOutOf()));
            } else {
                b.append(" [modifié]");
            }
            b.append("\n");
        }
    }

    private static void appendEvaluationLines(StringBuilder b, DiffResult<CompetenceEvaluation> diff) {
        for (CompetenceEvaluation e : diff.added()) {
            b.append("+ ").append(subject(e));
            if (e.getName() != null && !e.getName().isBlank()) {
                b.append(" \"").append(truncate(e.getName(), 40)).append("\"");
            }
            if (e.getDate() != null) b.append(" — ").append(fmtDate(e.getDate()));
            if (e.getPeriodName() != null && !e.getPeriodName().isBlank()) {
                b.append(" [").append(e.getPeriodName()).append("]");
            }
            b.append("\n");
        }
        for (CompetenceEvaluation e : diff.removed()) {
            b.append("- ").append(subject(e));
            if (e.getName() != null) b.append(" \"").append(truncate(e.getName(), 30)).append("\"");
            b.append(" [supprimé]\n");
        }
        for (Map.Entry<CompetenceEvaluation, List<FieldChange>> entry : diff.modified().entrySet()) {
            CompetenceEvaluation e = entry.getKey();
            b.append("~ ").append(subject(e));
            if (e.getName() != null) b.append(" \"").append(truncate(e.getName(), 30)).append("\"");
            b.append(" [modifié]\n");
        }
    }

    private static void appendSchoolLifeLines(StringBuilder b, DiffResult<SchoolLifeEvent> diff) {
        for (SchoolLifeEvent e : diff.added()) {
            b.append("+ ").append(fmtEventType(e.getType())).append(" ").append(fmtDate(e.getDate()));
            if (e.getTime() != null && !e.getTime().isBlank()) {
                b.append(" ").append(e.getTime().replace(":", "h"));
            }
            if (e.getMinutes() > 0) b.append(" (").append(e.getMinutes()).append(" min)");
            String label = e.getNature() != null && !e.getNature().isBlank() ? e.getNature()
                    : (e.getLabel() != null && !e.getLabel().isBlank() ? e.getLabel() : null);
            if (label != null) b.append(" — ").append(label);
            if (e.getReasons() != null && !e.getReasons().isBlank()) {
                b.append(" [").append(truncate(e.getReasons(), 40)).append("]");
            }
            if (e.isJustified()) b.append(" ✓");
            b.append("\n");
        }
        for (SchoolLifeEvent e : diff.removed()) {
            b.append("- ").append(fmtEventType(e.getType())).append(" ").append(fmtDate(e.getDate()))
                    .append(" [supprimé]\n");
        }
        for (Map.Entry<SchoolLifeEvent, List<FieldChange>> entry : diff.modified().entrySet()) {
            SchoolLifeEvent e = entry.getKey();
            b.append("~ ").append(fmtEventType(e.getType())).append(" ").append(fmtDate(e.getDate()))
                    .append(" [modifié]\n");
        }
    }

    // ---- field-change formatters --------------------------------------------

    private static String fmtAssignmentChange(FieldChange fc) {
        return switch (fc.fieldName()) {
            case "description" -> "description modifiée";
            case "dueDate"     -> "date: " + fc.oldValue() + "→" + fc.newValue();
            case "done"        -> Boolean.parseBoolean(String.valueOf(fc.newValue()))
                                  ? "marqué fait" : "marqué non fait";
            default            -> fc.fieldName() + " modifié";
        };
    }

    private static String fmtTimetableChanges(TimetableEntry entry, List<FieldChange> changes) {
        // Prefer the human-readable status label from Pronote when available
        if (entry.getStatusLabel() != null && !entry.getStatusLabel().isBlank()) {
            return entry.getStatusLabel();
        }
        List<String> parts = new ArrayList<>();
        for (FieldChange fc : changes) {
            switch (fc.fieldName()) {
                case "room"                  -> parts.add("salle " + fc.oldValue() + "→" + fc.newValue());
                case "teacher"               -> parts.add("prof. modifié");
                case "startTime", "endTime"  -> parts.add("horaire modifié");
                // status change reflected by the ✗/~ prefix; statusLabel already handled above
                case "status", "statusLabel", "enrichedSubject", "subject", "isTest", "memo" -> { }
                default                      -> parts.add(fc.fieldName() + " modifié");
            }
        }
        return String.join(", ", parts.subList(0, Math.min(2, parts.size())));
    }

    // ---- date / time formatters ---------------------------------------------

    private static final String[] DAYS_FR = {"lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim."};

    private static String fmtDate(LocalDate d) {
        if (d == null) return "?";
        return DAYS_FR[d.getDayOfWeek().getValue() - 1]
                + " " + d.getDayOfMonth() + "/" + String.format("%02d", d.getMonthValue());
    }

    private static String fmtDateTime(LocalDateTime dt) {
        if (dt == null) return "?";
        return fmtDate(dt.toLocalDate())
                + " " + String.format("%02dh%02d", dt.getHour(), dt.getMinute());
    }

    private static String fmtOutOf(double outOf) {
        return outOf == (long) outOf ? String.valueOf((long) outOf) : String.valueOf(outOf);
    }

    private static String fmtCoef(double coef) {
        return coef == (long) coef ? String.valueOf((long) coef) : String.valueOf(coef);
    }

    private static String fmtEventType(SchoolLifeEvent.EventType type) {
        if (type == null) return "Événement";
        return switch (type) {
            case ABSENCE     -> "Absence";
            case DELAY       -> "Retard";
            case INFIRMARY   -> "Infirmerie";
            case PUNISHMENT  -> "Punition";
            case OBSERVATION -> "Observation";
            case OTHER       -> "Événement";
        };
    }

    // ---- subject helpers (enrichedSubject with fallback) --------------------

    private static String subject(Assignment a) {
        String s = a.getEnrichedSubject();
        return s != null && !s.isBlank() ? s : (a.getSubject() != null ? a.getSubject() : "?");
    }

    private static String subject(TimetableEntry e) {
        String s = e.getEnrichedSubject();
        return s != null && !s.isBlank() ? s : (e.getSubject() != null ? e.getSubject() : "?");
    }

    private static String subject(Grade g) {
        String s = g.getEnrichedSubject();
        return s != null && !s.isBlank() ? s : (g.getSubject() != null ? g.getSubject() : "?");
    }

    private static String subject(CompetenceEvaluation ev) {
        String s = ev.getEnrichedSubject();
        return s != null && !s.isBlank() ? s : (ev.getSubject() != null ? ev.getSubject() : "?");
    }

    // -------------------------------------------------------------------------

    private static Path resolveConfigPath(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--config".equals(args[i])) return Path.of(args[i + 1]);
        }
        return Path.of("config.yaml");
    }

    private static String resolveMode(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--mode".equals(args[i])) return args[i + 1];
        }
        return "fetch";
    }

    private static final Set<String> KNOWN_FEATURES =
            Set.of("assignments", "timetable", "grades", "evaluations", "schoolLife");

    private static Optional<Set<String>> resolveFeatureOverride(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--features".equals(args[i])) {
                return Optional.of(new HashSet<>(List.of(args[i + 1].split(","))));
            }
        }
        return Optional.empty();
    }

    private static void applyFeatureOverride(AppConfig.FeaturesConfig features, Set<String> enabled) {
        Set<String> unknown = new HashSet<>(enabled);
        unknown.removeAll(KNOWN_FEATURES);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown --features values: " + unknown + ". Valid: " + KNOWN_FEATURES);
        }
        features.setAssignments(enabled.contains("assignments"));
        features.setTimetable(enabled.contains("timetable"));
        features.setGrades(enabled.contains("grades"));
        features.setEvaluations(enabled.contains("evaluations"));
        features.setSchoolLife(enabled.contains("schoolLife"));
        log.info("Feature override applied via --features flag: {}", enabled);
    }

    /**
     * Re-enriches assignments using timetable teacher information.
     *
     * <p>The Pronote homework API does not expose the teacher for each assignment.
     * This method resolves the teacher by matching the assignment's {@code subject}
     * and {@code assignedDate} against timetable entries. When a match is found,
     * {@link SubjectEnricher#enrich(String, String)} is called again with the resolved
     * teacher so that teacher-specific rules (e.g. splitting "HISTOIRE-GEOGRAPHIE" by
     * teacher) apply to assignments as well.
     *
     * <p>If no timetable entry matches (e.g. the assignment date falls outside the
     * fetched timetable range), the enrichment set by the scraper is kept unchanged.
     */
    private static void reEnrichAssignmentsWithTeacher(
            List<Assignment> assignments,
            List<TimetableEntry> timetable,
            SubjectEnricher subjectEnricher) {
        if (assignments.isEmpty() || timetable.isEmpty()) return;
        for (Assignment a : assignments) {
            if (a.getSubject() == null || a.getAssignedDate() == null) continue;
            String teacher = timetable.stream()
                    .filter(e -> e.getStartTime() != null
                            && a.getAssignedDate().equals(e.getStartTime().toLocalDate())
                            && a.getSubject().equals(e.getSubject())
                            && e.getTeacher() != null && !e.getTeacher().isBlank())
                    .map(TimetableEntry::getTeacher)
                    .findFirst()
                    .orElse(null);
            if (teacher != null) {
                String enriched = subjectEnricher.enrich(a.getSubject(), teacher);
                log.debug("Assignment '{}' (assigned {}) resolved teacher '{}' → enrichedSubject='{}'",
                        a.getSubject(), a.getAssignedDate(), teacher, enriched);
                a.setEnrichedSubject(enriched);
            }
        }
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }

    // -------------------------------------------------------------------------
    // Portal index helpers
    // -------------------------------------------------------------------------

    private static List<PortalIndexHtmlGenerator.Section> buildPortalSections(
            AppConfig config, AppConfig.FeaturesConfig features) {
        List<PortalIndexHtmlGenerator.Section> sections = new ArrayList<>();
        if (features.isTimetable() && config.getTimetableView().isEnabled()) {
            sections.add(new PortalIndexHtmlGenerator.Section(
                    "\uD83D\uDCC5", "Emploi du temps", "Planning des prochains jours",
                    "timetable/index.html"));
        }
        if (features.isAssignments() && config.getAssignmentView().isEnabled()) {
            sections.add(new PortalIndexHtmlGenerator.Section(
                    "\uD83D\uDCDD", "Devoirs", "Travail \u00e0 venir",
                    "assignments/index.html"));
        }
        if (features.isEvaluations() && config.getEvaluationView().isEnabled()) {
            sections.add(new PortalIndexHtmlGenerator.Section(
                    "\uD83D\uDCCA", "\u00c9valuations", "Comp\u00e9tences \u00e9valu\u00e9es",
                    "evaluations/index.html"));
            sections.add(new PortalIndexHtmlGenerator.Section(
                    "\uD83D\uDCCB", "Bilan", "Par trimestre et par mati\u00e8re",
                    "evaluations/summary.html"));
        }
        if (features.isSchoolLife() && config.getSchoolLifeView().isEnabled()) {
            sections.add(new PortalIndexHtmlGenerator.Section(
                    "\uD83C\uDFEB", "Vie scolaire", "Absences, retards et observations",
                    "school-life/index.html"));
        }
        return sections;
    }

    private static void writePortalIndex(String html) {
        try {
            Path dir = Path.of("./data/views");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("index.html"), html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write portal index to ./data/views/index.html", e);
        }
    }
}
