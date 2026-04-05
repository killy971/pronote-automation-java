package com.pronote.persistence;

import com.pronote.domain.EntryStatus;
import com.pronote.domain.TimetableEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Post-diff filtering layer for timetable diffs.
 *
 * <p>Applied after {@link DiffEngine#diff} and before notification to suppress
 * noise in two scenarios:
 *
 * <ol>
 *   <li><b>Past items</b>: removed/modified entries whose lesson has already ended
 *       are silently discarded — they carry no actionable information.</li>
 *   <li><b>Newly discovered furthest-future week</b>: when the retrieval window
 *       slides forward, the week that enters the window for the first time appears
 *       as a bulk addition. Normal entries in that week are suppressed; only
 *       noteworthy entries (cancelled, modified, or otherwise exceptional) are kept.</li>
 * </ol>
 *
 * <p>All other diff behaviour is preserved unchanged.
 */
public class TimetableDiffFilter {

    private static final Logger log = LoggerFactory.getLogger(TimetableDiffFilter.class);

    /**
     * Filters a raw timetable diff to suppress noise.
     *
     * @param raw              diff as computed by {@link DiffEngine}
     * @param previousSnapshot previous snapshot (used to detect newly discovered weeks)
     * @param furthestWeekStart Monday of the furthest week in the current retrieval window
     * @param now              current time (used to detect past items)
     * @return filtered diff safe for notification
     */
    public DiffResult<TimetableEntry> filter(
            DiffResult<TimetableEntry> raw,
            List<TimetableEntry> previousSnapshot,
            LocalDate furthestWeekStart,
            LocalDateTime now) {

        // 1. Filter removed: drop past items (lesson already over — not actionable)
        List<TimetableEntry> removed = raw.removed().stream()
                .filter(e -> !isPast(e, now))
                .toList();

        // 2. Filter modified: drop past items
        Map<TimetableEntry, List<FieldChange>> modified = new LinkedHashMap<>();
        for (Map.Entry<TimetableEntry, List<FieldChange>> entry : raw.modified().entrySet()) {
            if (!isPast(entry.getKey(), now)) {
                modified.put(entry.getKey(), entry.getValue());
            }
        }

        // 3. Filter added: suppress normal entries in a newly discovered furthest week
        boolean furthestWeekIsNew = isWeekAbsentFromSnapshot(furthestWeekStart, previousSnapshot);
        if (furthestWeekIsNew) {
            log.info("Furthest week starting {} is newly in retrieval window — suppressing normal additions",
                    furthestWeekStart);
        }

        List<TimetableEntry> added = raw.added().stream()
                .filter(e -> {
                    if (furthestWeekIsNew
                            && belongsToWeek(e, furthestWeekStart)
                            && !isNoteworthyTimetableEntry(e)) {
                        log.debug("Suppressed normal new-week addition: {}", e);
                        return false;
                    }
                    return true;
                })
                .toList();

        int suppressedAdded    = raw.added().size()    - added.size();
        int suppressedRemoved  = raw.removed().size()  - removed.size();
        int suppressedModified = raw.modified().size() - modified.size();
        if (suppressedAdded > 0 || suppressedRemoved > 0 || suppressedModified > 0) {
            log.info("Timetable diff filter suppressed {} added, {} removed, {} modified",
                    suppressedAdded, suppressedRemoved, suppressedModified);
        }

        return new DiffResult<>(added, removed, modified);
    }

    /**
     * Returns true if the entry's lesson has already ended before {@code now}.
     * Entries with a null end time are never considered past.
     */
    public static boolean isPast(TimetableEntry item, LocalDateTime now) {
        return item.getEndTime() != null && item.getEndTime().isBefore(now);
    }

    /**
     * Returns true if the item's start time falls within the given week (Mon–Sun)
     * <em>and</em> that week has no entries at all in the previous snapshot.
     *
     * <p>Use this to detect whether the furthest week has just entered the retrieval window.
     * Pass the furthest week's Monday as {@code weekStart}.
     */
    public static boolean belongsToNewlyDiscoveredFurthestWeek(
            TimetableEntry item,
            List<TimetableEntry> previousSnapshot,
            LocalDate weekStart) {
        return belongsToWeek(item, weekStart)
                && isWeekAbsentFromSnapshot(weekStart, previousSnapshot);
    }

    /**
     * Returns true if the entry represents a non-normal timetable event that warrants
     * notification even when it falls in a newly discovered week.
     *
     * <p>Classifies as noteworthy when:
     * <ul>
     *   <li>status is anything other than {@link EntryStatus#NORMAL} (cancelled, modified,
     *       exempted), or</li>
     *   <li>a non-blank {@code statusLabel} is present — Pronote populates this for
     *       teacher-absent events, exceptional sessions, etc. (e.g. "Prof. absent",
     *       "Exceptionnel", "Cours modifié").</li>
     * </ul>
     */
    public static boolean isNoteworthyTimetableEntry(TimetableEntry item) {
        if (item.getStatus() != null && item.getStatus() != EntryStatus.NORMAL) {
            return true;
        }
        return item.getStatusLabel() != null && !item.getStatusLabel().isBlank();
    }

    // -------------------------------------------------------------------------
    // Package-private helpers (visible to tests)
    // -------------------------------------------------------------------------

    static boolean belongsToWeek(TimetableEntry item, LocalDate weekStart) {
        if (item.getStartTime() == null) return false;
        LocalDate d = item.getStartTime().toLocalDate();
        return !d.isBefore(weekStart) && !d.isAfter(weekStart.plusDays(6));
    }

    private static boolean isWeekAbsentFromSnapshot(LocalDate weekStart, List<TimetableEntry> snapshot) {
        return snapshot.stream().noneMatch(e -> belongsToWeek(e, weekStart));
    }
}
