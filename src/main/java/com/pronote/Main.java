package com.pronote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pronote.auth.PronoteAuthenticator;
import com.pronote.auth.PronoteSession;
import com.pronote.auth.SessionStore;
import com.pronote.client.PronoteHttpClient;
import com.pronote.config.AppConfig;
import com.pronote.config.ConfigLoader;
import com.pronote.config.SubjectEnricher;
import com.pronote.domain.Assignment;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
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

        // ---- 2. Check lockout ---------------------------------------------
        LockoutGuard lockoutGuard = new LockoutGuard(dataDir, config.getSafety().getMaxLoginFailures());
        lockoutGuard.checkAndThrowIfLocked();

        // ---- 3. Establish session -----------------------------------------
        RateLimiter rateLimiter = new RateLimiter(
                config.getSafety().getMinDelayMs(),
                config.getSafety().getJitterMs());
        PronoteHttpClient httpClient = new PronoteHttpClient(rateLimiter);
        SessionStore sessionStore = new SessionStore(dataDir);
        PronoteSession session = acquireSession(config, httpClient, sessionStore, lockoutGuard);

        AppConfig.FeaturesConfig features = config.getFeatures();
        SubjectEnricher subjectEnricher = new SubjectEnricher(config.getSubjectEnrichment());

        // ---- 4. Fetch data (only for enabled types) -----------------------
        log.info("Fetching assignments...");
        AssignmentScraper assignmentScraper = new AssignmentScraper(subjectEnricher);
        List<Assignment> assignments = features.isAssignments()
                ? assignmentScraper.fetch(session, httpClient, config) : List.of();

        log.info("Fetching timetable...");
        TimetableScraper timetableScraper = new TimetableScraper(subjectEnricher);
        List<TimetableEntry> timetable = features.isTimetable()
                ? timetableScraper.fetch(session, httpClient, config) : List.of();

        GradeScraper gradeScraper = new GradeScraper(subjectEnricher);
        List<Grade> grades = List.of();
        if (features.isGrades()) {
            log.info("Fetching grades...");
            grades = gradeScraper.fetch(session, httpClient, session.getPeriods());
        } else {
            log.debug("Grades feature disabled — skipping.");
        }

        EvaluationScraper evaluationScraper = new EvaluationScraper(subjectEnricher);
        List<CompetenceEvaluation> evaluations = List.of();
        if (features.isEvaluations()) {
            log.info("Fetching competence evaluations...");
            evaluations = evaluationScraper.fetch(session, httpClient, session.getPeriods());
        } else {
            log.debug("Evaluations feature disabled — skipping.");
        }

        SchoolLifeScraper schoolLifeScraper = new SchoolLifeScraper(subjectEnricher);
        List<SchoolLifeEvent> schoolLife = List.of();
        if (features.isSchoolLife()) {
            log.info("Fetching vie scolaire events...");
            schoolLife = schoolLifeScraper.fetch(session, httpClient, session.getPeriods());
        } else {
            log.debug("School-life feature disabled — skipping.");
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
            if (notificationsEnabled) {
                log.info("Changes detected — sending notifications...");
                NotificationService notifier = buildNotifier(config);
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

        // ---- 10. Persist new snapshots (only for enabled types) -----------
        if (features.isAssignments())  snapshotStore.saveSnapshot("assignments", assignments);
        if (features.isTimetable())    snapshotStore.saveSnapshot("timetable",   timetable);
        if (features.isGrades())       snapshotStore.saveSnapshot("grades",      grades);
        if (features.isEvaluations())  snapshotStore.saveSnapshot("evaluations", evaluations);
        if (features.isSchoolLife())   snapshotStore.saveSnapshot("school-life", schoolLife);

        log.info("Job completed successfully.");
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

    private static NotificationService buildNotifier(AppConfig config) {
        List<NotificationService> services = new ArrayList<>();
        AppConfig.NotificationsConfig nc = config.getNotifications();
        if (nc.getNtfy().isEnabled())  services.add(new NtfyNotifier(nc.getNtfy()));
        if (nc.getEmail().isEnabled()) services.add(new EmailNotifier(nc.getEmail()));
        return new CompositeNotifier(services);
    }

    private static <T extends com.pronote.persistence.Identifiable> DiffResult<T> emptyDiff() {
        return new DiffResult<>(List.of(), List.of(), Map.of());
    }

    private static NotificationPayload buildPayload(DiffResult<Assignment> assignmentDiff,
                                                    DiffResult<TimetableEntry> timetableDiff,
                                                    DiffResult<Grade> gradeDiff,
                                                    DiffResult<CompetenceEvaluation> evaluationDiff,
                                                    DiffResult<SchoolLifeEvent> schoolLifeDiff) {
        StringBuilder body = new StringBuilder();

        if (!assignmentDiff.isEmpty()) {
            body.append("=== ASSIGNMENTS ===\n");
            for (Assignment a : assignmentDiff.getAdded()) {
                body.append("+ NEW: ").append(a.getSubject()).append(" — due ").append(a.getDueDate()).append("\n");
                if (a.getDescription() != null && !a.getDescription().isBlank()) {
                    body.append("  ").append(truncate(a.getDescription(), 100)).append("\n");
                }
                List<AttachmentRef> attachments = a.getAttachments();
                if (attachments != null && !attachments.isEmpty()) {
                    for (AttachmentRef ref : attachments) {
                        if (ref.isUploadedFile() && ref.getLocalPath() != null) {
                            body.append("  [file] ").append(ref.getFileName()).append("\n");
                        } else if (!ref.isUploadedFile()) {
                            body.append("  [link] ").append(ref.getFileName())
                                    .append(": ").append(ref.getUrl()).append("\n");
                        }
                    }
                }
            }
            for (Assignment a : assignmentDiff.getRemoved()) {
                body.append("- REMOVED: ").append(a.getSubject()).append(" (due ").append(a.getDueDate()).append(")\n");
            }
            for (Map.Entry<Assignment, List<FieldChange>> entry : assignmentDiff.getModified().entrySet()) {
                body.append("~ MODIFIED: ").append(entry.getKey().getSubject())
                        .append(" (due ").append(entry.getKey().getDueDate()).append(")\n");
                for (FieldChange fc : entry.getValue()) {
                    body.append("  ").append(fc).append("\n");
                }
            }
        }

        if (!timetableDiff.isEmpty()) {
            if (!body.isEmpty()) body.append("\n");
            body.append("=== TIMETABLE ===\n");
            for (TimetableEntry e : timetableDiff.getAdded()) {
                body.append("+ NEW: ").append(e.getSubject()).append(" — ").append(e.getStartTime()).append("\n");
            }
            for (TimetableEntry e : timetableDiff.getRemoved()) {
                body.append("- REMOVED: ").append(e.getSubject()).append(" — ").append(e.getStartTime()).append("\n");
            }
            for (Map.Entry<TimetableEntry, List<FieldChange>> entry : timetableDiff.getModified().entrySet()) {
                body.append("~ MODIFIED: ").append(entry.getKey().getSubject())
                        .append(" (").append(entry.getKey().getStartTime()).append(")\n");
                for (FieldChange fc : entry.getValue()) {
                    body.append("  ").append(fc).append("\n");
                }
            }
        }

        if (!gradeDiff.isEmpty()) {
            if (!body.isEmpty()) body.append("\n");
            body.append("=== GRADES ===\n");
            for (Grade g : gradeDiff.getAdded()) {
                body.append("+ NEW: ").append(g.getSubject()).append(" — ").append(g.getValue())
                        .append("/").append(g.getOutOf());
                if (g.getComment() != null && !g.getComment().isBlank()) {
                    body.append(" (").append(truncate(g.getComment(), 60)).append(")");
                }
                body.append(" [").append(g.getPeriodName()).append("]\n");
            }
            for (Grade g : gradeDiff.getRemoved()) {
                body.append("- REMOVED: ").append(g.getSubject()).append(" (").append(g.getDate()).append(")\n");
            }
            for (Map.Entry<Grade, List<FieldChange>> entry : gradeDiff.getModified().entrySet()) {
                body.append("~ MODIFIED: ").append(entry.getKey().getSubject())
                        .append(" (").append(entry.getKey().getDate()).append(")\n");
                for (FieldChange fc : entry.getValue()) {
                    body.append("  ").append(fc).append("\n");
                }
            }
        }

        if (!evaluationDiff.isEmpty()) {
            if (!body.isEmpty()) body.append("\n");
            body.append("=== EVALUATIONS ===\n");
            for (CompetenceEvaluation e : evaluationDiff.getAdded()) {
                body.append("+ NEW: ").append(e.getSubject()).append(" — \"").append(e.getName()).append("\"");
                if (e.getDate() != null) body.append(" on ").append(e.getDate());
                body.append(" [").append(e.getPeriodName()).append("]\n");
            }
            for (CompetenceEvaluation e : evaluationDiff.getRemoved()) {
                body.append("- REMOVED: ").append(e.getSubject()).append(" \"")
                        .append(e.getName()).append("\" (").append(e.getDate()).append(")\n");
            }
            for (Map.Entry<CompetenceEvaluation, List<FieldChange>> entry : evaluationDiff.getModified().entrySet()) {
                body.append("~ MODIFIED: ").append(entry.getKey().getSubject())
                        .append(" \"").append(entry.getKey().getName()).append("\"\n");
                for (FieldChange fc : entry.getValue()) {
                    body.append("  ").append(fc).append("\n");
                }
            }
        }

        if (!schoolLifeDiff.isEmpty()) {
            if (!body.isEmpty()) body.append("\n");
            body.append("=== VIE SCOLAIRE ===\n");
            for (SchoolLifeEvent e : schoolLifeDiff.getAdded()) {
                body.append("+ NEW: [").append(e.getType()).append("] ").append(e.getDate());
                if (e.getMinutes() > 0) body.append(" (").append(e.getMinutes()).append(" min)");
                if (e.getNature() != null && !e.getNature().isBlank())
                    body.append(" — ").append(e.getNature());
                if (e.getReasons() != null && !e.getReasons().isBlank())
                    body.append(" — ").append(e.getReasons());
                if (e.isJustified()) body.append(" [justified]");
                body.append("\n");
            }
            for (SchoolLifeEvent e : schoolLifeDiff.getRemoved()) {
                body.append("- REMOVED: [").append(e.getType()).append("] ").append(e.getDate()).append("\n");
            }
            for (Map.Entry<SchoolLifeEvent, List<FieldChange>> entry : schoolLifeDiff.getModified().entrySet()) {
                body.append("~ MODIFIED: [").append(entry.getKey().getType())
                        .append("] ").append(entry.getKey().getDate()).append("\n");
                for (FieldChange fc : entry.getValue()) {
                    body.append("  ").append(fc).append("\n");
                }
            }
        }

        int totalChanges = assignmentDiff.getAdded().size() + assignmentDiff.getRemoved().size()
                + assignmentDiff.getModified().size() + timetableDiff.getAdded().size()
                + timetableDiff.getRemoved().size() + timetableDiff.getModified().size()
                + gradeDiff.getAdded().size() + gradeDiff.getRemoved().size()
                + gradeDiff.getModified().size() + evaluationDiff.getAdded().size()
                + evaluationDiff.getRemoved().size() + evaluationDiff.getModified().size()
                + schoolLifeDiff.getAdded().size() + schoolLifeDiff.getRemoved().size()
                + schoolLifeDiff.getModified().size();

        // Priority HIGH if timetable changed or new school-life event (immediate-attention items);
        // NORMAL otherwise.
        NotificationPayload.Priority priority = (timetableDiff.getAdded().isEmpty()
                && timetableDiff.getRemoved().isEmpty()
                && schoolLifeDiff.getAdded().isEmpty())
                ? NotificationPayload.Priority.NORMAL
                : NotificationPayload.Priority.HIGH;

        return new NotificationPayload(
                "Pronote update: " + totalChanges + " change(s)",
                body.toString().trim(),
                priority,
                List.of("school", "pronote"));
    }

    // -------------------------------------------------------------------------

    private static Path resolveConfigPath(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--config".equals(args[i])) return Path.of(args[i + 1]);
        }
        return Path.of("config.yaml");
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

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
