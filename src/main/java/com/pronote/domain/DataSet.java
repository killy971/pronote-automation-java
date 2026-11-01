package com.pronote.domain;

import java.util.List;

/**
 * The five data-type lists of one run, as fetched from Pronote (plus merged manual
 * entries) or loaded from snapshots. Disabled data types carry an empty list.
 */
public record DataSet(
        List<Assignment> assignments,
        List<TimetableEntry> timetable,
        List<Grade> grades,
        List<CompetenceEvaluation> evaluations,
        List<SchoolLifeEvent> schoolLife) {

    public static DataSet empty() {
        return new DataSet(List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
