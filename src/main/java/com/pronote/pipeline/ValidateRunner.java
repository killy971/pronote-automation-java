package com.pronote.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pronote.config.AppConfig;
import com.pronote.config.ManualEntryLoader;
import com.pronote.config.SubjectEnricher;
import com.pronote.domain.Assignment;
import com.pronote.domain.AttachmentRef;
import com.pronote.domain.TimetableEntry;
import com.pronote.persistence.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Offline mode: load {@code manual-entries.yaml}, run subject enrichment, and print a per-entry
 * summary of how each entry would be merged on the next {@code fetch}/{@code views} run —
 * without touching the network, snapshots, attachments, or notifications.
 *
 * <p>Each entry is cross-checked against the latest timetable snapshot to flag subjects that
 * never appear there (likely a typo in the YAML's all-caps {@code subject:} string, the most
 * common source of silent failure for manual entries), and each {@code file:} attachment is
 * checked to exist.
 *
 * <p>Fails (exit 1) when the file does not parse or a required field is missing. The subject
 * and attachment checks produce warnings, not failures — some subjects (e.g. one-off events)
 * legitimately won't appear in the timetable.
 */
public final class ValidateRunner {

    private static final Logger log = LoggerFactory.getLogger(ValidateRunner.class);

    private final AppConfig config;
    private final Path dataDir;

    public ValidateRunner(AppConfig config, Path dataDir) {
        this.config = config;
        this.dataDir = dataDir;
    }

    public void run() {
        SubjectEnricher enricher = new SubjectEnricher(config.getSubjectEnrichment());
        Path file = Path.of(config.getManualEntries().getFile());

        log.info("Validating manual entries from {}", file.toAbsolutePath());
        if (!Files.exists(file)) {
            log.warn("Manual entries file not found: {} (this is fine; the file is optional)",
                    file.toAbsolutePath());
            return;
        }

        // ManualEntryLoader.load throws ConfigException on parse/required-field errors —
        // let it propagate to main(), which logs and exits 1.
        Path attachmentBaseDir = Path.of(config.getManualEntries().getAttachmentDir());
        ManualEntryLoader.ManualEntries manual =
                ManualEntryLoader.load(file, enricher, attachmentBaseDir);

        // Subjects seen in the latest timetable snapshot, to flag typos. Empty when the user has
        // never run --mode fetch — the check is then skipped rather than warning on every entry.
        SnapshotStore snapshotStore = new SnapshotStore(dataDir, config.getData().getArchiveRetainDays());
        Optional<List<TimetableEntry>> timetableSnap =
                snapshotStore.loadLatest("timetable", new TypeReference<>() {});
        Set<String> knownSubjects = timetableSnap.map(list -> {
            Set<String> s = new HashSet<>();
            for (TimetableEntry e : list) if (e.getSubject() != null) s.add(e.getSubject());
            return s;
        }).orElse(Set.of());

        int warnings = 0;

        log.info("─── Assignments ({}) ───", manual.getAssignments().size());
        for (Assignment a : manual.getAssignments()) {
            String suffix = "";
            if (!knownSubjects.isEmpty() && !knownSubjects.contains(a.getSubject())) {
                suffix = "  ⚠ subject not seen in latest timetable snapshot";
                warnings++;
            }
            log.info("  {} | subject='{}' → '{}' | dueDate={} | done={}{}",
                    a.getId(), a.getSubject(), a.getEnrichedSubject(),
                    a.getDueDate(), a.isDone(), suffix);
            warnings += reportAttachments(a);
        }

        log.info("─── Upcoming evaluations ({}) ───", manual.getUpcomingEvals().size());
        for (TimetableEntry e : manual.getUpcomingEvals()) {
            String suffix = "";
            if (!knownSubjects.isEmpty() && !knownSubjects.contains(e.getSubject())) {
                suffix = "  ⚠ subject not seen in latest timetable snapshot";
                warnings++;
            }
            log.info("  {} | subject='{}' → '{}' | date={} | name='{}'{}",
                    e.getId(), e.getSubject(), e.getEnrichedSubject(),
                    e.getStartTime().toLocalDate(), e.getLessonLabel(), suffix);
        }

        if (knownSubjects.isEmpty()) {
            log.info("(No timetable snapshot found — subject existence check skipped.)");
        }
        if (warnings == 0) {
            log.info("Manual entries valid.");
        } else {
            log.warn("Manual entries valid — {} warning(s); review the subject strings above.", warnings);
        }
    }

    /**
     * Logs one line per declared attachment of a manual assignment and returns the number of
     * warnings raised. A {@code file:} attachment whose source is missing is the one manual-entry
     * mistake that silently produces a card with no attachment at all, so validate resolves the
     * path and checks it here rather than waiting for the WARN in the next scheduled run.
     */
    private static int reportAttachments(Assignment a) {
        int warnings = 0;
        for (AttachmentRef ref : a.getAttachments()) {
            if (!ref.isUploadedFile()) {
                log.info("      🔗 {} → {}", ref.getFileName(), ref.getUrl());
                continue;
            }
            Path source = Path.of(ref.getSourcePath());
            if (Files.isRegularFile(source)) {
                log.info("      📎 {} ← {}", ref.getFileName(), source);
            } else {
                log.warn("      📎 {} ← {}  ⚠ file not found", ref.getFileName(), source);
                warnings++;
            }
        }
        return warnings;
    }
}
