package com.pronote.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pronote.config.AppConfig;
import com.pronote.domain.Assignment;
import com.pronote.domain.CompetenceEvaluation;
import com.pronote.domain.DataSet;
import com.pronote.domain.Grade;
import com.pronote.domain.SchoolLifeEvent;
import com.pronote.domain.TimetableEntry;
import com.pronote.persistence.SnapshotStore;

import java.util.List;
import java.util.Optional;

/**
 * One snapshot per data type, loaded according to the feature flags.
 * A {@code null} list means the type is disabled or has no snapshot on disk —
 * downstream that distinction does not matter, only "nothing to compare against".
 */
final class SnapshotSet {

    private final List<Assignment> assignments;
    private final List<TimetableEntry> timetable;
    private final List<Grade> grades;
    private final List<CompetenceEvaluation> evaluations;
    private final List<SchoolLifeEvent> schoolLife;

    private SnapshotSet(List<Assignment> assignments, List<TimetableEntry> timetable,
                        List<Grade> grades, List<CompetenceEvaluation> evaluations,
                        List<SchoolLifeEvent> schoolLife) {
        this.assignments = assignments;
        this.timetable = timetable;
        this.grades = grades;
        this.evaluations = evaluations;
        this.schoolLife = schoolLife;
    }

    /** Loads each enabled type's {@code latest.json}. */
    static SnapshotSet latest(SnapshotStore store, AppConfig.FeaturesConfig features) {
        return new SnapshotSet(
                features.isAssignments() ? store.loadLatest("assignments", new TypeReference<List<Assignment>>() {}).orElse(null) : null,
                features.isTimetable()   ? store.loadLatest("timetable",   new TypeReference<List<TimetableEntry>>() {}).orElse(null) : null,
                features.isGrades()      ? store.loadLatest("grades",      new TypeReference<List<Grade>>() {}).orElse(null) : null,
                features.isEvaluations() ? store.loadLatest("evaluations", new TypeReference<List<CompetenceEvaluation>>() {}).orElse(null) : null,
                features.isSchoolLife()  ? store.loadLatest("school-life", new TypeReference<List<SchoolLifeEvent>>() {}).orElse(null) : null);
    }

    /** Loads each enabled type's most recent archive entry (the previous run's snapshot). */
    static SnapshotSet previous(SnapshotStore store, AppConfig.FeaturesConfig features) {
        return new SnapshotSet(
                features.isAssignments() ? store.loadPrevious("assignments", new TypeReference<List<Assignment>>() {}).orElse(null) : null,
                features.isTimetable()   ? store.loadPrevious("timetable",   new TypeReference<List<TimetableEntry>>() {}).orElse(null) : null,
                features.isGrades()      ? store.loadPrevious("grades",      new TypeReference<List<Grade>>() {}).orElse(null) : null,
                features.isEvaluations() ? store.loadPrevious("evaluations", new TypeReference<List<CompetenceEvaluation>>() {}).orElse(null) : null,
                features.isSchoolLife()  ? store.loadPrevious("school-life", new TypeReference<List<SchoolLifeEvent>>() {}).orElse(null) : null);
    }

    Optional<List<Assignment>> assignments() { return Optional.ofNullable(assignments); }
    Optional<List<TimetableEntry>> timetable() { return Optional.ofNullable(timetable); }
    Optional<List<Grade>> grades() { return Optional.ofNullable(grades); }
    Optional<List<CompetenceEvaluation>> evaluations() { return Optional.ofNullable(evaluations); }
    Optional<List<SchoolLifeEvent>> schoolLife() { return Optional.ofNullable(schoolLife); }

    /** True when no enabled type has a snapshot — the "first run" condition. */
    boolean allAbsent() {
        return assignments == null && timetable == null && grades == null
                && evaluations == null && schoolLife == null;
    }

    /** Returns the snapshots as a {@link DataSet}, substituting empty lists for absent types. */
    DataSet orEmpty() {
        return new DataSet(
                assignments != null ? assignments : List.of(),
                timetable   != null ? timetable   : List.of(),
                grades      != null ? grades      : List.of(),
                evaluations != null ? evaluations : List.of(),
                schoolLife  != null ? schoolLife  : List.of());
    }
}
