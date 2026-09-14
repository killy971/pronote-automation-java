package com.pronote.persistence;

import com.pronote.domain.Assignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Post-diff filtering layer for assignment diffs — the counterpart of
 * {@link TimetableDiffFilter} for homework.
 *
 * <p>Assignments are fetched for the week range
 * {@code [current week - pronote.weeksBefore, current week + pronote.weeksAhead]}. With the
 * default {@code weeksBefore: 0} that range starts on the current week's Monday, so every Monday
 * the whole of the previous week drops out of the response at once. The diff reads that as a
 * batch of removals and the notification announces last week's homework as
 * {@code "- Mathématiques · mar. 08/09 [supprimé]"} — noise, not news: nothing was deleted, the
 * retrieval window simply moved.
 *
 * <p>So a removed or modified assignment whose due date has already passed is dropped. Additions
 * are left alone: a teacher publishing work late is still worth surfacing, and with
 * {@code weeksBefore: 0} a past-due addition cannot come from the window sliding anyway.
 *
 * <p>The timetable side of the same weekly roll is handled by {@link TimetableDiffFilter#isPast}.
 */
public class AssignmentDiffFilter {

    private static final Logger log = LoggerFactory.getLogger(AssignmentDiffFilter.class);

    /**
     * Filters a raw assignment diff to suppress items that only left the retrieval window.
     *
     * @param raw   diff as computed by {@link DiffEngine}
     * @param today the current date; an assignment due strictly before it counts as past
     * @return filtered diff safe for notification
     */
    public DiffResult<Assignment> filter(DiffResult<Assignment> raw, LocalDate today) {
        List<Assignment> removed = raw.removed().stream()
                .filter(a -> !isPast(a, today))
                .toList();

        Map<Assignment, List<FieldChange>> modified = new LinkedHashMap<>();
        for (Map.Entry<Assignment, List<FieldChange>> entry : raw.modified().entrySet()) {
            if (!isPast(entry.getKey(), today)) {
                modified.put(entry.getKey(), entry.getValue());
            }
        }

        int suppressedRemoved  = raw.removed().size()  - removed.size();
        int suppressedModified = raw.modified().size() - modified.size();
        if (suppressedRemoved > 0 || suppressedModified > 0) {
            log.info("Assignment diff filter suppressed {} removed, {} modified (past due date)",
                    suppressedRemoved, suppressedModified);
        }

        return new DiffResult<>(raw.added(), removed, modified);
    }

    /**
     * Returns true if the assignment was due before {@code today}. Work due today still counts as
     * current — the school day is not over. An assignment with no due date is never past.
     */
    public static boolean isPast(Assignment item, LocalDate today) {
        return item.getDueDate() != null && item.getDueDate().isBefore(today);
    }
}
