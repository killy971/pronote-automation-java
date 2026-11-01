package com.pronote.pipeline;

import com.pronote.auth.PronoteAuthenticator;
import com.pronote.auth.PronoteSession;
import com.pronote.auth.SessionStore;
import com.pronote.client.PronoteHttpClient;
import com.pronote.config.AppConfig;
import com.pronote.config.ManualEntryLoader;
import com.pronote.config.SubjectEnricher;
import com.pronote.domain.Assignment;
import com.pronote.domain.CompetenceEvaluation;
import com.pronote.domain.DataSet;
import com.pronote.domain.Grade;
import com.pronote.domain.SchoolLifeEvent;
import com.pronote.domain.TimetableEntry;
import com.pronote.notification.NotificationService;
import com.pronote.persistence.DiffBundle;
import com.pronote.persistence.DiffReporter;
import com.pronote.persistence.SnapshotStore;
import com.pronote.safety.EmptyFetchGuard;
import com.pronote.safety.LockoutGuard;
import com.pronote.safety.RateLimiter;
import com.pronote.scraper.AssignmentTeacherResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Default mode: the full online pipeline.
 * <ol>
 *   <li>Check the login lockout, establish or reuse a session</li>
 *   <li>Fetch every enabled data type, merge manual entries</li>
 *   <li>Refuse an all-empty fetch (a dead session, not a mass deletion)</li>
 *   <li>Diff against the previous snapshots; write the diff report (always)</li>
 *   <li>Notify when something changed</li>
 *   <li>Persist new snapshots, regenerate views and portal, publish (optional)</li>
 * </ol>
 *
 * <p>On the <strong>first run</strong> (no previous snapshot), only the baseline is saved —
 * no notification is sent.
 */
public final class FetchRunner {

    private static final Logger log = LoggerFactory.getLogger(FetchRunner.class);

    private final AppConfig config;
    private final Path dataDir;
    private final boolean dryRun;

    public FetchRunner(AppConfig config, Path dataDir, boolean dryRun) {
        this.config = config;
        this.dataDir = dataDir;
        this.dryRun = dryRun;
    }

    /** Logs in (or reuses the stored session), then runs {@link #runWith} against Pronote. */
    public void run() {
        // The error notifier is built *before* the lockout check so that a lockout is announced
        // like every other pipeline failure. Previously this exception unwound straight to
        // main()'s catch with no alert, so the sync stopped silently and the first symptom was
        // stale views. Only the first run of a lockout episode alerts — cron runs 27x/day.
        NotificationService errorNotifier = NotificationDispatcher.errorNotifier(config);
        LockoutGuard lockoutGuard = new LockoutGuard(
                dataDir,
                config.getSafety().getMaxLoginFailures(),
                config.getSafety().getLockoutCooldownMinutes());
        try {
            lockoutGuard.checkAndThrowIfLocked();
        } catch (LockoutGuard.LockoutException e) {
            if (e.isAlertPending()
                    && NotificationDispatcher.sendErrorAlert(errorNotifier, "verrouillage", e.getMessage())) {
                lockoutGuard.markLockoutAlerted();
            }
            throw e;
        }

        RateLimiter rateLimiter = new RateLimiter(
                config.getSafety().getMinDelayMs(),
                config.getSafety().getJitterMs());
        PronoteHttpClient httpClient = new PronoteHttpClient(rateLimiter);
        SessionStore sessionStore = new SessionStore(dataDir);
        PronoteSession session;
        try {
            session = acquireSession(httpClient, sessionStore, lockoutGuard);
        } catch (RuntimeException e) {
            NotificationDispatcher.sendErrorAlert(errorNotifier, "authentification", e.getMessage());
            throw e;
        }

        SubjectEnricher enricher = new SubjectEnricher(config.getSubjectEnrichment());
        runWith(new PronoteFetchSource(config, session, httpClient, sessionStore, enricher), errorNotifier);
    }

    /**
     * Everything after the login, reading Pronote data from {@code source}. Public so the
     * end-to-end tests can run the production pipeline against canned data.
     *
     * @param errorNotifier receives the failure alerts; {@code null} disables them
     */
    public void runWith(FetchSource source, NotificationService errorNotifier) {
        AppConfig.FeaturesConfig features = config.getFeatures();
        SubjectEnricher enricher = new SubjectEnricher(config.getSubjectEnrichment());

        // ---- Fetch (only enabled types) -----------------------------------
        List<Assignment> assignments = fetchIfEnabled(features.isAssignments(), "assignments",
                "récupération des devoirs", source::fetchAssignments, errorNotifier);
        List<TimetableEntry> timetable = fetchIfEnabled(features.isTimetable(), "timetable",
                "emploi du temps", source::fetchTimetable, errorNotifier);

        // The Pronote homework API carries no teacher, so AssignmentScraper applies subject-only
        // rules. Cross-reference each assignment against the timetable to find its teacher, then
        // re-enrich so teacher-specific rules (e.g. the Histoire/Géographie split) also fire.
        new AssignmentTeacherResolver(enricher).reEnrichAssignmentsWithTeacher(assignments, timetable);

        List<Grade> grades = fetchIfEnabled(features.isGrades(), "grades",
                "notes", source::fetchGrades, errorNotifier);
        List<CompetenceEvaluation> evaluations = fetchIfEnabled(features.isEvaluations(), "competence evaluations",
                "évaluations", source::fetchEvaluations, errorNotifier);
        List<SchoolLifeEvent> schoolLife = fetchIfEnabled(features.isSchoolLife(), "vie scolaire events",
                "vie scolaire", source::fetchSchoolLife, errorNotifier);

        // Keep what Pronote itself returned: the empty-fetch guard reads these to tell a dead
        // session from a genuinely empty week, and the manual entries merged below would mask it.
        DataSet fetched = new DataSet(assignments, timetable, grades, evaluations, schoolLife);

        // ---- Merge manual entries (manual-entries.yaml, if present) -------
        ManualEntryLoader.ManualEntries manual = ManualEntryLoader.load(
                Path.of(config.getManualEntries().getFile()), enricher,
                Path.of(config.getManualEntries().getAttachmentDir()));
        if (features.isAssignments()) {
            assignments = ManualEntryMerger.mergeAssignments(assignments, manual.getAssignments());
            // Stage before the diff, so the stager — not the Pronote downloader — owns the files,
            // and localPath is set for the snapshot write and the views. Runs even with no manual
            // assignment: that is what prunes the files of entries just deleted from the YAML.
            ManualEntryMerger.stageAttachments(dataDir, assignments);
        }
        if (features.isTimetable()) {
            timetable = ManualEntryMerger.mergeUpcomingEvals(timetable, manual.getUpcomingEvals());
        }
        DataSet current = new DataSet(assignments, timetable, grades, evaluations, schoolLife);

        // ---- Previous snapshots (only enabled types) ----------------------
        SnapshotStore snapshotStore = new SnapshotStore(dataDir, config.getData().getArchiveRetainDays());
        SnapshotSet previous = SnapshotSet.latest(snapshotStore, features);
        boolean isFirstRun = previous.allAbsent();

        refuseAllEmptyFetch(features, fetched, previous, errorNotifier);

        // ---- Diff ---------------------------------------------------------
        DiffBundle diffs = new DiffStage().diff(previous, current);

        // ---- Attachments, then the session (last Pronote request is done) --
        if (features.isAssignments()) {
            source.downloadAttachments(assignments, DataLayout.attachmentsDir(dataDir));
        }
        // Saving again here stamps lastUsedAt and captures the order counter the run advanced.
        // Without it the stored counter stays at its post-login value, which the server has
        // already consumed — so even a back-to-back run could never reuse the session.
        source.saveSession();

        // ---- Diff report (always), then notification ----------------------
        new DiffReporter(dataDir).record(diffs, isFirstRun);
        NotificationDispatcher.dispatchIfChanged(config, dryRun, diffs, isFirstRun, timetable);

        // ---- Persist new snapshots (only enabled types) -------------------
        if (features.isAssignments())  snapshotStore.saveSnapshot("assignments", assignments);
        if (features.isTimetable())    snapshotStore.saveSnapshot("timetable",   timetable);
        if (features.isGrades())       snapshotStore.saveSnapshot("grades",      grades);
        if (features.isEvaluations())  snapshotStore.saveSnapshot("evaluations", evaluations);
        if (features.isSchoolLife())   snapshotStore.saveSnapshot("school-life", schoolLife);

        // ---- Views, portal, publication -----------------------------------
        ViewPipeline views = new ViewPipeline(config);
        if (views.timetableEnabled()) {
            log.info("Generating timetable HTML views...");
            views.renderTimetable(current.timetable(), current.assignments());
        }
        if (views.assignmentsEnabled()) {
            log.info("Generating assignment HTML view...");
            views.renderAssignments(current.assignments(), current.timetable());
        }
        if (views.evaluationsEnabled()) {
            log.info("Generating evaluation HTML views...");
            views.renderEvaluations(current.evaluations(), current.timetable());
        }
        if (views.schoolLifeEnabled()) {
            log.info("Generating school-life HTML view...");
            views.renderSchoolLife(current.schoolLife());
        }
        String portalHtml = views.writePortalIndex();
        views.publish(dataDir, portalHtml);

        log.info("Job completed successfully.");
    }

    // -------------------------------------------------------------------------
    // Steps
    // -------------------------------------------------------------------------

    /**
     * Runs one scraper when its feature is enabled; a failure is alerted with {@code alertPhase}
     * and rethrown — one failed scraper fails the run, nothing is written.
     */
    private static <T> List<T> fetchIfEnabled(boolean enabled, String what, String alertPhase,
                                              Supplier<List<T>> fetch, NotificationService errorNotifier) {
        if (!enabled) {
            log.debug("Feature '{}' disabled — skipping.", what);
            return List.of();
        }
        log.info("Fetching {}...", what);
        try {
            return fetch.get();
        } catch (RuntimeException e) {
            NotificationDispatcher.sendErrorAlert(errorNotifier, alertPhase, e.getMessage());
            throw e;
        }
    }

    /**
     * A session the server has stopped honouring still answers 200, and the body decrypts to
     * {@code {}}; each scraper logs a WARN and returns nothing. Without this check the diff reads
     * that as the school deleting the entire timetable, notifies a hundred removals, and persists
     * the empty snapshot — so the next healthy run announces them all back.
     */
    private static void refuseAllEmptyFetch(AppConfig.FeaturesConfig features, DataSet fetched,
                                            SnapshotSet previous, NotificationService errorNotifier) {
        EmptyFetchGuard guard = new EmptyFetchGuard();
        if (features.isAssignments()) guard.observe("devoirs", fetched.assignments(), previous.assignments());
        if (features.isTimetable())   guard.observe("emploi du temps", fetched.timetable(), previous.timetable());
        if (features.isGrades())      guard.observe("notes", fetched.grades(), previous.grades());
        if (features.isEvaluations()) guard.observe("évaluations", fetched.evaluations(), previous.evaluations());
        if (features.isSchoolLife())  guard.observe("vie scolaire", fetched.schoolLife(), previous.schoolLife());
        try {
            guard.verify();
        } catch (EmptyFetchGuard.EmptyFetchException e) {
            NotificationDispatcher.sendErrorAlert(errorNotifier, "récupération vide", e.getMessage());
            throw e;
        }
    }

    private PronoteSession acquireSession(PronoteHttpClient httpClient, SessionStore sessionStore,
                                          LockoutGuard lockoutGuard) {
        Optional<PronoteSession> existing = sessionStore.load();
        if (existing.isPresent()) {
            Instant now = Instant.now();
            int maxAge = config.getSafety().getSessionProbeMaxAgeSeconds();
            long age = existing.get().secondsSinceLastUse(now);
            if (SessionStore.shouldProbe(existing.get(), now, maxAge)) {
                log.info("Found existing session ({}s old), probing...", age);
                if (httpClient.probe(existing.get())) {
                    log.info("Session reuse successful — skipping login.");
                    return existing.get();
                }
                log.info("Session probe failed — performing fresh login.");
                sessionStore.delete();
            } else {
                // Pronote drops an idle session after ~2 minutes, so at this job's 30-minute
                // cadence the probe was a guaranteed-to-fail request (plus a rate-limiter wait)
                // on every single run, and it logged a misleading "probe failed" line.
                log.info("Stored session is {}s old (probe limit: {}s) — logging in without probing.",
                        age, maxAge);
            }
        }

        long waitSeconds = lockoutGuard.secondsUntilNextAttemptAllowed(config.getSafety().getMinLoginIntervalSeconds());
        if (waitSeconds > 0) {
            log.info("Waiting {}s before login attempt to avoid rapid re-authentication...", waitSeconds);
            try {
                Thread.sleep(waitSeconds * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        lockoutGuard.recordAttempt();

        // One attempt per run — never add a retry loop here (see LockoutGuard).
        log.info("Performing full login...");
        PronoteSession session;
        try {
            session = new PronoteAuthenticator().login(config);
            lockoutGuard.recordSuccess();
        } catch (RuntimeException e) {
            lockoutGuard.recordFailure();
            throw new RuntimeException("Login failed: " + e.getMessage(), e);
        }

        sessionStore.save(session);
        return session;
    }
}
