package com.pronote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pronote.auth.PronoteAuthenticator;
import com.pronote.auth.PronoteSession;
import com.pronote.auth.SessionStore;
import com.pronote.client.PronoteHttpClient;
import com.pronote.config.AppConfig;
import com.pronote.config.ConfigLoader;
import com.pronote.domain.Assignment;
import com.pronote.domain.TimetableEntry;
import com.pronote.notification.*;
import com.pronote.persistence.DiffEngine;
import com.pronote.persistence.DiffReporter;
import com.pronote.persistence.DiffResult;
import com.pronote.persistence.FieldChange;
import com.pronote.persistence.SnapshotStore;
import com.pronote.safety.LockoutGuard;
import com.pronote.safety.RateLimiter;
import com.pronote.domain.AttachmentRef;
import com.pronote.scraper.AssignmentScraper;
import com.pronote.scraper.AttachmentDownloader;
import com.pronote.scraper.TimetableScraper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

        // ---- 4. Fetch data -----------------------------------------------
        log.info("Fetching assignments...");
        AssignmentScraper assignmentScraper = new AssignmentScraper();
        List<Assignment> assignments = assignmentScraper.fetch(session, httpClient, config);

        log.info("Fetching timetable...");
        TimetableScraper timetableScraper = new TimetableScraper();
        List<TimetableEntry> timetable = timetableScraper.fetch(session, httpClient, config);

        // ---- 5. Load previous snapshots -----------------------------------
        SnapshotStore snapshotStore = new SnapshotStore(dataDir, config.getData().getArchiveRetainDays());

        Optional<List<Assignment>> prevAssignments = snapshotStore.loadLatest(
                "assignments", new TypeReference<>() {});
        Optional<List<TimetableEntry>> prevTimetable = snapshotStore.loadLatest(
                "timetable", new TypeReference<>() {});

        // First run: no previous snapshot exists yet — just save the baseline.
        boolean isFirstRun = prevAssignments.isEmpty() && prevTimetable.isEmpty();

        // ---- 6. Diff -------------------------------------------------------
        DiffEngine diffEngine = new DiffEngine();
        DiffResult<Assignment> assignmentDiff = diffEngine.diff(
                prevAssignments.orElse(List.of()), assignments);
        DiffResult<TimetableEntry> timetableDiff = diffEngine.diff(
                prevTimetable.orElse(List.of()), timetable);

        // ---- 7. Download assignment attachments ----------------------------
        // Only G=1 (uploaded files) are downloaded; G=0 hyperlinks are stored as-is.
        // AttachmentDownloader is idempotent: checks Files.exists() before each download,
        // so unchanged files are skipped and previously-failed downloads are retried.
        Path attachmentsDir = dataDir.resolve("snapshots").resolve("assignments").resolve("attachments");
        AttachmentDownloader attachmentDownloader = new AttachmentDownloader(httpClient, session, attachmentsDir);
        attachmentDownloader.processAttachments(assignments);

        // ---- 8. Write persistent diff report (always) ---------------------
        DiffReporter reporter = new DiffReporter(dataDir);
        reporter.record(assignmentDiff, timetableDiff, isFirstRun);

        if (!isFirstRun && (!assignmentDiff.isEmpty() || !timetableDiff.isEmpty())) {
            boolean notificationsEnabled = config.getNotifications().getNtfy().isEnabled()
                    || config.getNotifications().getEmail().isEnabled();
            if (notificationsEnabled) {
                log.info("Changes detected — sending notifications...");
                NotificationService notifier = buildNotifier(config);
                NotificationPayload payload = buildPayload(assignmentDiff, timetableDiff);
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

        // ---- 10. Persist new snapshots ------------------------------------
        snapshotStore.saveSnapshot("assignments", assignments);
        snapshotStore.saveSnapshot("timetable", timetable);

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

    private static NotificationPayload buildPayload(DiffResult<Assignment> assignmentDiff,
                                                    DiffResult<TimetableEntry> timetableDiff) {
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

        int totalChanges = assignmentDiff.getAdded().size() + assignmentDiff.getRemoved().size()
                + assignmentDiff.getModified().size() + timetableDiff.getAdded().size()
                + timetableDiff.getRemoved().size() + timetableDiff.getModified().size();

        NotificationPayload.Priority priority = timetableDiff.getAdded().isEmpty()
                && timetableDiff.getRemoved().isEmpty()
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

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
