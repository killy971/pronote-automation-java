package com.pronote.scraper;

import com.pronote.config.AppConfig;
import com.pronote.config.SubjectEnricher;
import com.pronote.domain.Assignment;
import com.pronote.domain.TimetableEntry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AssignmentTeacherResolverTest {

    private static TimetableEntry tt(String subject, String teacher,
                                      LocalDateTime start, LocalDateTime end) {
        TimetableEntry e = new TimetableEntry();
        e.setSubject(subject);
        e.setTeacher(teacher);
        e.setStartTime(start);
        e.setEndTime(end);
        e.setId(subject + "@" + start);
        return e;
    }

    private static Assignment newAssignment(String subject, LocalDate dueDate,
                                             LocalDate assignedDate) {
        Assignment a = new Assignment();
        a.setSubject(subject);
        a.setDueDate(dueDate);
        a.setAssignedDate(assignedDate);
        a.setId(subject + "@" + dueDate);
        return a;
    }

    private static SubjectEnricher enricherWithSplit() {
        AppConfig.SubjectEnrichmentConfig cfg = new AppConfig.SubjectEnrichmentConfig();
        AppConfig.SubjectEnrichmentRule r1 = new AppConfig.SubjectEnrichmentRule();
        r1.setSubject("SYN_COMBO");
        r1.setTeacher("TEACHER_ALPHA");
        r1.setEnrichedSubject("Alpha");
        AppConfig.SubjectEnrichmentRule r2 = new AppConfig.SubjectEnrichmentRule();
        r2.setSubject("SYN_COMBO");
        r2.setTeacher("TEACHER_BETA");
        r2.setEnrichedSubject("Beta");
        cfg.setRules(List.of(r1, r2));
        return new SubjectEnricher(cfg);
    }

    // -------------------------------------------------------------------------
    // findTeacherInTimetable
    // -------------------------------------------------------------------------

    @Test
    void findTeacher_returnsNull_whenDateIsNull() {
        List<TimetableEntry> tt = List.of(
                tt("MATH", "TEACHER_A",
                   LocalDateTime.of(2030, 5, 1, 8, 0),
                   LocalDateTime.of(2030, 5, 1, 9, 0)));
        assertNull(AssignmentTeacherResolver.findTeacherInTimetable("MATH", null, tt));
    }

    @Test
    void findTeacher_returnsTeacher_whenSingleMatch() {
        List<TimetableEntry> tt = List.of(
                tt("MATH", "TEACHER_A",
                   LocalDateTime.of(2030, 5, 1, 8, 0),
                   LocalDateTime.of(2030, 5, 1, 9, 0)));
        assertEquals("TEACHER_A",
                AssignmentTeacherResolver.findTeacherInTimetable(
                        "MATH", LocalDate.of(2030, 5, 1), tt));
    }

    @Test
    void findTeacher_returnsNull_whenAmbiguous() {
        List<TimetableEntry> tt = List.of(
                tt("SYN_COMBO", "TEACHER_ALPHA",
                   LocalDateTime.of(2030, 5, 1, 8, 0),
                   LocalDateTime.of(2030, 5, 1, 9, 0)),
                tt("SYN_COMBO", "TEACHER_BETA",
                   LocalDateTime.of(2030, 5, 1, 10, 0),
                   LocalDateTime.of(2030, 5, 1, 11, 0)));
        assertNull(AssignmentTeacherResolver.findTeacherInTimetable(
                "SYN_COMBO", LocalDate.of(2030, 5, 1), tt));
    }

    @Test
    void findTeacher_ignoresEntriesWithBlankTeacher() {
        List<TimetableEntry> tt = List.of(
                tt("MATH", "",
                   LocalDateTime.of(2030, 5, 1, 8, 0),
                   LocalDateTime.of(2030, 5, 1, 9, 0)),
                tt("MATH", "TEACHER_A",
                   LocalDateTime.of(2030, 5, 1, 10, 0),
                   LocalDateTime.of(2030, 5, 1, 11, 0)));
        assertEquals("TEACHER_A",
                AssignmentTeacherResolver.findTeacherInTimetable(
                        "MATH", LocalDate.of(2030, 5, 1), tt));
    }

    @Test
    void findTeacher_returnsNull_whenNoMatchOnDate() {
        List<TimetableEntry> tt = List.of(
                tt("MATH", "TEACHER_A",
                   LocalDateTime.of(2030, 5, 1, 8, 0),
                   LocalDateTime.of(2030, 5, 1, 9, 0)));
        assertNull(AssignmentTeacherResolver.findTeacherInTimetable(
                "MATH", LocalDate.of(2030, 6, 1), tt));
    }

    // -------------------------------------------------------------------------
    // reEnrichAssignmentsWithTeacher
    // -------------------------------------------------------------------------

    @Test
    void reEnrich_skipsWhenTimetableEmpty() {
        Assignment a = newAssignment("SYN_COMBO",
                LocalDate.of(2030, 5, 1), LocalDate.of(2030, 4, 25));
        a.setEnrichedSubject("ORIGINAL");
        new AssignmentTeacherResolver(enricherWithSplit())
                .reEnrichAssignmentsWithTeacher(List.of(a), List.of());
        assertEquals("ORIGINAL", a.getEnrichedSubject());
    }

    @Test
    void reEnrich_appliesTeacherRule_whenDueDateMatchesUniquely() {
        Assignment a = newAssignment("SYN_COMBO",
                LocalDate.of(2030, 5, 1), LocalDate.of(2030, 4, 25));
        a.setEnrichedSubject("ORIGINAL");

        List<TimetableEntry> tt = List.of(
                tt("SYN_COMBO", "TEACHER_BETA",
                   LocalDateTime.of(2030, 5, 1, 8, 0),
                   LocalDateTime.of(2030, 5, 1, 9, 0)));

        new AssignmentTeacherResolver(enricherWithSplit())
                .reEnrichAssignmentsWithTeacher(new ArrayList<>(List.of(a)), tt);

        assertEquals("Beta", a.getEnrichedSubject());
    }

    @Test
    void reEnrich_fallsBackToAssignedDate_whenDueDateAmbiguous() {
        Assignment a = newAssignment("SYN_COMBO",
                LocalDate.of(2030, 5, 1), LocalDate.of(2030, 4, 25));
        a.setEnrichedSubject("ORIGINAL");

        List<TimetableEntry> tt = List.of(
                // dueDate has two teachers — ambiguous
                tt("SYN_COMBO", "TEACHER_ALPHA",
                   LocalDateTime.of(2030, 5, 1, 8, 0),
                   LocalDateTime.of(2030, 5, 1, 9, 0)),
                tt("SYN_COMBO", "TEACHER_BETA",
                   LocalDateTime.of(2030, 5, 1, 10, 0),
                   LocalDateTime.of(2030, 5, 1, 11, 0)),
                // assignedDate has only TEACHER_ALPHA
                tt("SYN_COMBO", "TEACHER_ALPHA",
                   LocalDateTime.of(2030, 4, 25, 8, 0),
                   LocalDateTime.of(2030, 4, 25, 9, 0))
        );

        new AssignmentTeacherResolver(enricherWithSplit())
                .reEnrichAssignmentsWithTeacher(new ArrayList<>(List.of(a)), tt);

        assertEquals("Alpha", a.getEnrichedSubject());
    }

    @Test
    void reEnrich_explicitTeacherWins_overTimetable() {
        Assignment a = newAssignment("SYN_COMBO",
                LocalDate.of(2030, 5, 1), LocalDate.of(2030, 4, 25));
        a.setTeacher("TEACHER_ALPHA"); // explicit on the manual entry
        a.setEnrichedSubject("ORIGINAL");

        List<TimetableEntry> tt = List.of(
                // timetable says BETA on the same day, but explicit teacher should win
                tt("SYN_COMBO", "TEACHER_BETA",
                   LocalDateTime.of(2030, 5, 1, 8, 0),
                   LocalDateTime.of(2030, 5, 1, 9, 0)));

        new AssignmentTeacherResolver(enricherWithSplit())
                .reEnrichAssignmentsWithTeacher(new ArrayList<>(List.of(a)), tt);

        assertEquals("Alpha", a.getEnrichedSubject());
    }

    @Test
    void reEnrich_leavesUnchanged_whenNoTeacherResolvable() {
        Assignment a = newAssignment("SYN_COMBO",
                LocalDate.of(2030, 5, 1), LocalDate.of(2030, 4, 25));
        a.setEnrichedSubject("ORIGINAL");

        List<TimetableEntry> tt = List.of(
                tt("OTHER", "TEACHER_X",
                   LocalDateTime.of(2030, 5, 1, 8, 0),
                   LocalDateTime.of(2030, 5, 1, 9, 0)));

        new AssignmentTeacherResolver(enricherWithSplit())
                .reEnrichAssignmentsWithTeacher(new ArrayList<>(List.of(a)), tt);

        assertEquals("ORIGINAL", a.getEnrichedSubject());
    }

    // -------------------------------------------------------------------------
    // resolveManualEvalTimes
    // -------------------------------------------------------------------------

    @Test
    void resolveManualEvalTimes_overridesTimes_whenSubjectMatchesOnDate() {
        TimetableEntry eval = new TimetableEntry();
        eval.setSubject("MATH");
        eval.setEval(true);
        // default placeholder set by ManualEntryLoader
        eval.setStartTime(LocalDateTime.of(2030, 5, 1, 8, 0));
        eval.setEndTime(LocalDateTime.of(2030, 5, 1, 9, 0));

        List<TimetableEntry> tt = List.of(
                tt("MATH", "TEACHER_A",
                   LocalDateTime.of(2030, 5, 1, 10, 30),
                   LocalDateTime.of(2030, 5, 1, 11, 30)));

        AssignmentTeacherResolver.resolveManualEvalTimes(List.of(eval), tt);

        assertEquals(LocalDateTime.of(2030, 5, 1, 10, 30), eval.getStartTime());
        assertEquals(LocalDateTime.of(2030, 5, 1, 11, 30), eval.getEndTime());
    }

    @Test
    void resolveManualEvalTimes_prefersTeacherSpecificMatch() {
        TimetableEntry eval = new TimetableEntry();
        eval.setSubject("SYN_COMBO");
        eval.setTeacher("TEACHER_BETA");
        eval.setStartTime(LocalDateTime.of(2030, 5, 1, 8, 0));
        eval.setEndTime(LocalDateTime.of(2030, 5, 1, 9, 0));

        List<TimetableEntry> tt = List.of(
                tt("SYN_COMBO", "TEACHER_ALPHA",
                   LocalDateTime.of(2030, 5, 1, 10, 0),
                   LocalDateTime.of(2030, 5, 1, 11, 0)),
                tt("SYN_COMBO", "TEACHER_BETA",
                   LocalDateTime.of(2030, 5, 1, 14, 0),
                   LocalDateTime.of(2030, 5, 1, 15, 0)));

        AssignmentTeacherResolver.resolveManualEvalTimes(List.of(eval), tt);

        assertEquals(LocalDateTime.of(2030, 5, 1, 14, 0), eval.getStartTime());
        assertEquals(LocalDateTime.of(2030, 5, 1, 15, 0), eval.getEndTime());
    }

    @Test
    void resolveManualEvalTimes_fallsBackToSubjectOnly_whenTeacherMisses() {
        TimetableEntry eval = new TimetableEntry();
        eval.setSubject("MATH");
        eval.setTeacher("TEACHER_X"); // not in timetable
        eval.setStartTime(LocalDateTime.of(2030, 5, 1, 8, 0));
        eval.setEndTime(LocalDateTime.of(2030, 5, 1, 9, 0));

        List<TimetableEntry> tt = List.of(
                tt("MATH", "TEACHER_A",
                   LocalDateTime.of(2030, 5, 1, 14, 0),
                   LocalDateTime.of(2030, 5, 1, 15, 0)));

        AssignmentTeacherResolver.resolveManualEvalTimes(List.of(eval), tt);

        // Subject-only fallback succeeded → time updated
        assertEquals(LocalDateTime.of(2030, 5, 1, 14, 0), eval.getStartTime());
        assertEquals(LocalDateTime.of(2030, 5, 1, 15, 0), eval.getEndTime());
    }

    @Test
    void resolveManualEvalTimes_leavesPlaceholderUnchanged_whenNoMatchAtAll() {
        TimetableEntry eval = new TimetableEntry();
        eval.setSubject("PHYS");
        eval.setStartTime(LocalDateTime.of(2030, 5, 1, 8, 0));
        eval.setEndTime(LocalDateTime.of(2030, 5, 1, 9, 0));

        List<TimetableEntry> tt = List.of(
                tt("MATH", "TEACHER_A",
                   LocalDateTime.of(2030, 5, 1, 10, 0),
                   LocalDateTime.of(2030, 5, 1, 11, 0)));

        AssignmentTeacherResolver.resolveManualEvalTimes(List.of(eval), tt);

        // No subject match → original placeholder preserved
        assertEquals(LocalDateTime.of(2030, 5, 1, 8, 0), eval.getStartTime());
        assertEquals(LocalDateTime.of(2030, 5, 1, 9, 0), eval.getEndTime());
    }

    // -------------------------------------------------------------------------
    // reEnrichFromConfig — applies rule edits to an already-persisted snapshot
    // -------------------------------------------------------------------------

    /** Enricher with a subject-only rule, standing in for a freshly edited config. */
    private static SubjectEnricher enricherWithSubjectOnlyRule() {
        AppConfig.SubjectEnrichmentConfig cfg = new AppConfig.SubjectEnrichmentConfig();
        AppConfig.SubjectEnrichmentRule r = new AppConfig.SubjectEnrichmentRule();
        r.setSubject("SYN_SCIENCES");
        r.setEnrichedSubject("Sciences");
        cfg.setRules(List.of(r));
        return new SubjectEnricher(cfg);
    }

    @Test
    void reEnrichFromConfig_refreshesStaleTimetableEnrichment() {
        TimetableEntry e = tt("SYN_SCIENCES", "TEACHER_A",
                LocalDateTime.of(2030, 5, 1, 8, 0), LocalDateTime.of(2030, 5, 1, 9, 0));
        e.setEnrichedSubject("SYN_SCIENCES");   // stale value persisted before the rule existed

        new AssignmentTeacherResolver(enricherWithSubjectOnlyRule())
                .reEnrichFromConfig(new ArrayList<>(), new ArrayList<>(List.of(e)));

        assertEquals("Sciences", e.getEnrichedSubject());
    }

    @Test
    void reEnrichFromConfig_refreshesAssignment_evenWhenTeacherCannotBeResolved() {
        // Subject absent from the timetable, so the teacher lookup yields nothing; the
        // subject-only rule must still be applied.
        Assignment a = newAssignment("SYN_SCIENCES", LocalDate.of(2030, 5, 1), null);
        a.setEnrichedSubject("SYN_SCIENCES");
        TimetableEntry unrelated = tt("SYN_OTHER", "TEACHER_A",
                LocalDateTime.of(2030, 5, 1, 8, 0), LocalDateTime.of(2030, 5, 1, 9, 0));

        new AssignmentTeacherResolver(enricherWithSubjectOnlyRule())
                .reEnrichFromConfig(new ArrayList<>(List.of(a)), new ArrayList<>(List.of(unrelated)));

        assertEquals("Sciences", a.getEnrichedSubject());
    }

    @Test
    void reEnrichFromConfig_stillPrefersTheTeacherResolvedFromTheTimetable() {
        // The teacher-specific rule must win over the baseline pass.
        TimetableEntry lesson = tt("SYN_COMBO", "TEACHER_BETA",
                LocalDateTime.of(2030, 5, 1, 8, 0), LocalDateTime.of(2030, 5, 1, 9, 0));
        Assignment a = newAssignment("SYN_COMBO", LocalDate.of(2030, 5, 1), null);

        new AssignmentTeacherResolver(enricherWithSplit())
                .reEnrichFromConfig(new ArrayList<>(List.of(a)), new ArrayList<>(List.of(lesson)));

        assertEquals("Beta", a.getEnrichedSubject());
        assertEquals("Beta", lesson.getEnrichedSubject());
    }

    @Test
    void reEnrichFromConfig_leavesUnmatchedSubjectsAsTheRawString() {
        TimetableEntry e = tt("SYN_UNMAPPED", "TEACHER_A",
                LocalDateTime.of(2030, 5, 1, 8, 0), LocalDateTime.of(2030, 5, 1, 9, 0));

        new AssignmentTeacherResolver(enricherWithSubjectOnlyRule())
                .reEnrichFromConfig(new ArrayList<>(), new ArrayList<>(List.of(e)));

        assertEquals("SYN_UNMAPPED", e.getEnrichedSubject());
    }
}
