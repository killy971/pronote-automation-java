package com.pronote.pipeline;

import com.pronote.domain.Assignment;
import com.pronote.domain.DataSet;
import com.pronote.domain.TimetableEntry;
import com.pronote.persistence.AssignmentDiffFilter;
import com.pronote.persistence.DiffBundle;
import com.pronote.persistence.DiffEngine;
import com.pronote.persistence.DiffResult;
import com.pronote.persistence.Identifiable;
import com.pronote.persistence.TimetableDiffFilter;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Computes the per-type diffs of one run — shared by the fetch and diff modes.
 *
 * <p>Types without a previous snapshot get an empty diff: a data type enabled for the first
 * time on an existing installation establishes a silent baseline, no notification.
 * Two types then go through a post-diff filter that removes noise caused by the retrieval
 * window moving rather than by the school changing anything:
 * <ul>
 *   <li>assignments — {@link AssignmentDiffFilter} drops past-due removals/modifications;</li>
 *   <li>timetable — {@link TimetableDiffFilter#filterForWindow} drops past lessons and the bulk
 *       additions of a newly discovered furthest week.</li>
 * </ul>
 */
final class DiffStage {

    private final DiffEngine engine = new DiffEngine();

    DiffBundle diff(SnapshotSet previous, DataSet current) {
        return new DiffBundle(
                diffAssignments(previous.assignments(), current.assignments()),
                diffTimetable(previous.timetable(), current.timetable()),
                diffOne(previous.grades(), current.grades()),
                diffOne(previous.evaluations(), current.evaluations()),
                diffOne(previous.schoolLife(), current.schoolLife()));
    }

    private <T extends Identifiable> DiffResult<T> diffOne(Optional<List<T>> previous, List<T> current) {
        return previous.map(p -> engine.diff(p, current)).orElseGet(DiffResult::empty);
    }

    private DiffResult<Assignment> diffAssignments(Optional<List<Assignment>> previous,
                                                   List<Assignment> current) {
        if (previous.isEmpty()) return DiffResult.empty();
        // Every Monday the fetch range starts a week later, so last week's homework vanishes
        // from the response at once and would otherwise be announced as deleted.
        return new AssignmentDiffFilter().filter(engine.diff(previous.get(), current), LocalDate.now());
    }

    private DiffResult<TimetableEntry> diffTimetable(Optional<List<TimetableEntry>> previous,
                                                     List<TimetableEntry> current) {
        if (previous.isEmpty()) return DiffResult.empty();
        DiffResult<TimetableEntry> raw = engine.diff(previous.get(), current);
        return new TimetableDiffFilter().filterForWindow(raw, previous.get(), current);
    }
}
