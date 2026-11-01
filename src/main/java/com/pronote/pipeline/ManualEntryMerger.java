package com.pronote.pipeline;

import com.pronote.config.ManualEntryLoader;
import com.pronote.domain.Assignment;
import com.pronote.domain.TimetableEntry;
import com.pronote.scraper.AssignmentTeacherResolver;
import com.pronote.scraper.ManualAttachmentStager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Merges manual entries from {@code manual-entries.yaml} into a data list — shared by the
 * fetch and views modes.
 *
 * <p>Strip-then-inject: entries already carrying the {@code manual:} ID prefix are removed
 * first, then the freshly parsed YAML entries are added. This makes the merge idempotent —
 * in views mode, YAML edits (including deletions) take effect on snapshot data without a
 * full fetch; in fetch mode, the strip is a no-op guard since scraped data never carries
 * manual IDs. {@code ManualEntryLoaderTest} pins the prefix invariant.
 */
final class ManualEntryMerger {

    private ManualEntryMerger() {}

    /** Returns a new list: {@code base} without stale manual entries, plus {@code manual}. */
    static List<Assignment> mergeAssignments(List<Assignment> base, List<Assignment> manual) {
        List<Assignment> merged = new ArrayList<>(base);
        merged.removeIf(a -> ManualEntryLoader.isManualId(a.getId()));
        merged.addAll(manual);
        return merged;
    }

    /**
     * Returns a new list: {@code base} without stale manual entries, plus {@code manualEvals}
     * with their placeholder times resolved against the real timetable slots in {@code base}.
     */
    static List<TimetableEntry> mergeUpcomingEvals(List<TimetableEntry> base,
                                                   List<TimetableEntry> manualEvals) {
        List<TimetableEntry> merged = new ArrayList<>(base);
        merged.removeIf(e -> ManualEntryLoader.isManualId(e.getId()));
        AssignmentTeacherResolver.resolveManualEvalTimes(manualEvals, merged);
        merged.addAll(manualEvals);
        return merged;
    }

    /**
     * Copies the files declared by manual assignments into the attachments tree and prunes the
     * copies of entries no longer in the YAML. Pass the complete, merged assignment list — even
     * when it holds no manual entry, since that is what clears out deleted ones.
     *
     * <p>{@code localPath} is transient-by-consequence for manual attachments: the refs are
     * rebuilt from YAML on every run, so nothing points at the staged files until this runs.
     */
    static void stageAttachments(Path dataDir, List<Assignment> mergedAssignments) {
        new ManualAttachmentStager(DataLayout.attachmentsDir(dataDir)).stage(mergedAssignments);
    }
}
