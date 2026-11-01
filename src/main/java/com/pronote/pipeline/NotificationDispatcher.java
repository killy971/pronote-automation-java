package com.pronote.pipeline;

import com.pronote.config.AppConfig;
import com.pronote.domain.TimetableEntry;
import com.pronote.notification.CompositeNotifier;
import com.pronote.notification.EmailNotifier;
import com.pronote.notification.NotificationFormatter;
import com.pronote.notification.NotificationPayload;
import com.pronote.notification.NotificationPayloadBuilder;
import com.pronote.notification.NotificationService;
import com.pronote.notification.NtfyNotifier;
import com.pronote.persistence.DiffBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds notifiers from config and delivers change notifications and error alerts.
 * Shared by the fetch and diff modes, which gate and send identically.
 */
public final class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private NotificationDispatcher() {}

    /**
     * Sends (or, in dry-run, previews) a change notification when the run produced changes.
     * No-op on the first run or when nothing changed. Delivery failure is logged, not fatal —
     * the diff report has already been written at this point.
     *
     * @param currentTimetable the timetable the diff produced; it lets the payload tell a real
     *                         cancellation from the cancelled ghost of a room change
     */
    static void dispatchIfChanged(AppConfig config, boolean dryRun, DiffBundle diffs,
                                  boolean isFirstRun, List<TimetableEntry> currentTimetable) {
        if (isFirstRun || !diffs.hasChanges()) return;

        boolean notificationsEnabled = config.getNotifications().getNtfy().isEnabled()
                || config.getNotifications().getEmail().isEnabled();
        if (!dryRun && !notificationsEnabled) {
            log.info("Changes detected — notifications disabled, see diff-latest.json and diff-history.log.");
            return;
        }

        log.info("Changes detected — {}...", dryRun ? "previewing notification (dry-run)" : "sending notifications");
        NotificationService notifier = buildNotifier(config, dryRun);
        NotificationPayload payload = NotificationPayloadBuilder.build(diffs, currentTimetable);
        try {
            notifier.send(payload);
        } catch (NotificationService.NotificationException e) {
            log.error("Notification delivery failed: {}", e.getMessage());
        }
    }

    /**
     * Returns an ntfy-based notifier for error alerts, or {@code null} if error alerts or ntfy
     * are disabled. Dry-run mode is intentionally ignored here: error alerts are always sent
     * in dry-run (they reflect real failures, not data changes).
     */
    public static NotificationService errorNotifier(AppConfig config) {
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
     * @return true if the alert was actually delivered
     */
    static boolean sendErrorAlert(NotificationService notifier, String phase, String detail) {
        if (notifier == null) return false;
        String body = detail != null ? NotificationFormatter.truncate(detail, 200) : "(aucun détail)";
        NotificationPayload payload = new NotificationPayload(
                "⚠ Pronote : échec — " + phase,
                body,
                NotificationPayload.Priority.HIGH,
                List.of("warning", "pronote"));
        try {
            notifier.send(payload);
            log.info("Error alert sent for phase '{}'", phase);
            return true;
        } catch (NotificationService.NotificationException e) {
            log.warn("Failed to deliver error alert for phase '{}': {}", phase, e.getMessage());
            return false;
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
}
