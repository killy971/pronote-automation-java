package com.pronote.scraper;

import com.pronote.config.SubjectEnricher;
import com.pronote.domain.Assignment;
import com.pronote.domain.TimetableEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Cross-references assignments and manual evaluation entries against the timetable
 * to resolve teacher names and lesson times.
 *
 * <p>Extracted from {@code Main.java} to make the logic unit-testable and to keep
 * orchestration code free of business rules. Pure (no I/O, no static state).
 */
public class AssignmentTeacherResolver {

    private static final Logger log = LoggerFactory.getLogger(AssignmentTeacherResolver.class);

    private final SubjectEnricher subjectEnricher;

    public AssignmentTeacherResolver(SubjectEnricher subjectEnricher) {
        this.subjectEnricher = subjectEnricher;
    }

    /**
     * Re-applies subject enrichment to each assignment using a teacher name resolved
     * from the timetable.
     *
     * <p>The Pronote homework API does not expose the teacher for each assignment.
     * This method resolves the teacher by matching the assignment's {@code subject}
     * against timetable entries. The {@code dueDate} is tried first as it is most likely
     * to be a class day for that teacher. If the due date has multiple distinct teachers
     * for the same subject (ambiguous), the {@code assignedDate} is used as a tiebreaker —
     * it identifies the teacher who actually gave the assignment.
     *
     * <p>Manual assignments may carry an explicit {@code teacher} — that takes precedence
     * over the timetable lookup so the user's intent is not overwritten.
     */
    public void reEnrichAssignmentsWithTeacher(List<Assignment> assignments,
                                               List<TimetableEntry> timetable) {
        if (assignments.isEmpty() || timetable.isEmpty()) return;
        for (Assignment a : assignments) {
            if (a.getSubject() == null) continue;
            String teacher = a.getTeacher();
            if (teacher == null) {
                teacher = findTeacherInTimetable(a.getSubject(), a.getDueDate(), timetable);
            }
            if (teacher == null) {
                teacher = findTeacherInTimetable(a.getSubject(), a.getAssignedDate(), timetable);
            }
            if (teacher != null) {
                String enriched = subjectEnricher.enrich(a.getSubject(), teacher);
                log.debug("Assignment '{}' (due {}) resolved teacher '{}' → enrichedSubject='{}'",
                        a.getSubject(), a.getDueDate(), teacher, enriched);
                a.setEnrichedSubject(enriched);
            }
        }
    }

    /**
     * Resolves the teacher for a given subject on a given date.
     * Returns {@code null} unless exactly one distinct teacher is found.
     */
    public static String findTeacherInTimetable(String subject, LocalDate date,
                                                 List<TimetableEntry> timetable) {
        if (date == null) return null;
        List<String> teachers = timetable.stream()
                .filter(e -> e.getStartTime() != null
                        && date.equals(e.getStartTime().toLocalDate())
                        && subject.equals(e.getSubject())
                        && e.getTeacher() != null && !e.getTeacher().isBlank())
                .map(TimetableEntry::getTeacher)
                .distinct()
                .toList();
        return teachers.size() == 1 ? teachers.get(0) : null;
    }

    /**
     * Resolves start/end times for synthetic manual eval entries by finding the matching
     * timetable slot on the same date and subject. Teacher is used for disambiguation when set.
     *
     * <p>When no timetable match is found, the eval's existing start/end times are left
     * untouched — {@code ManualEntryLoader} already populates a default 08:00–09:00
     * placeholder when the YAML omits a time.
     */
    public static void resolveManualEvalTimes(List<TimetableEntry> manualEvals,
                                              List<TimetableEntry> timetable) {
        for (TimetableEntry eval : manualEvals) {
            LocalDate date = eval.getStartTime().toLocalDate();

            Optional<TimetableEntry> match = timetable.stream()
                    .filter(e -> e.getStartTime() != null
                            && date.equals(e.getStartTime().toLocalDate())
                            && eval.getSubject().equals(e.getSubject())
                            && (eval.getTeacher() == null || eval.getTeacher().equals(e.getTeacher())))
                    .findFirst();

            // Subject-only fallback when teacher was set but produced no match
            if (match.isEmpty() && eval.getTeacher() != null) {
                match = timetable.stream()
                        .filter(e -> e.getStartTime() != null
                                && date.equals(e.getStartTime().toLocalDate())
                                && eval.getSubject().equals(e.getSubject()))
                        .findFirst();
            }

            if (match.isPresent() && match.get().getEndTime() != null) {
                eval.setStartTime(match.get().getStartTime());
                eval.setEndTime(match.get().getEndTime());
                log.debug("Manual eval '{}' on {}: resolved time {}–{} from timetable",
                        eval.getLessonLabel(), date,
                        match.get().getStartTime().toLocalTime(),
                        match.get().getEndTime().toLocalTime());
            } else {
                log.info("Manual eval '{}' on {}: no timetable slot found — using default 08:00–09:00",
                        eval.getLessonLabel(), date);
            }
        }
    }
}
