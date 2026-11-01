package com.pronote.persistence;

import com.pronote.domain.Assignment;
import com.pronote.domain.CompetenceEvaluation;
import com.pronote.domain.Grade;
import com.pronote.domain.SchoolLifeEvent;
import com.pronote.domain.TimetableEntry;

/**
 * The diff results of one run, one per data type. Groups the five parallel
 * {@link DiffResult} values that travel together from the diff stage to the
 * diff report and the notification payload.
 */
public record DiffBundle(
        DiffResult<Assignment> assignments,
        DiffResult<TimetableEntry> timetable,
        DiffResult<Grade> grades,
        DiffResult<CompetenceEvaluation> evaluations,
        DiffResult<SchoolLifeEvent> schoolLife) {

    /** Returns true if any data type has at least one change. */
    public boolean hasChanges() {
        return !assignments.isEmpty() || !timetable.isEmpty() || !grades.isEmpty()
                || !evaluations.isEmpty() || !schoolLife.isEmpty();
    }
}
