package com.pronote.persistence;

import com.pronote.domain.Assignment;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AssignmentDiffFilterTest {

    /** A Monday — the day the fetch window rolls forward and last week drops out. */
    private static final LocalDate TODAY = LocalDate.of(2030, 5, 6);

    private final AssignmentDiffFilter filter = new AssignmentDiffFilter();

    private static Assignment assignment(String id, LocalDate dueDate) {
        Assignment a = new Assignment();
        a.setId(id);
        a.setSubject("SYN_MATHS");
        a.setDueDate(dueDate);
        return a;
    }

    private static DiffResult<Assignment> diff(List<Assignment> added,
                                               List<Assignment> removed,
                                               Map<Assignment, List<FieldChange>> modified) {
        return new DiffResult<>(added, removed, modified);
    }

    // -------------------------------------------------------------------------
    // isPast
    // -------------------------------------------------------------------------

    @Test
    void isPast_returnsTrue_whenDueDateIsBeforeToday() {
        assertTrue(AssignmentDiffFilter.isPast(assignment("a", TODAY.minusDays(1)), TODAY));
    }

    @Test
    void isPast_returnsFalse_whenDueToday() {
        assertFalse(AssignmentDiffFilter.isPast(assignment("a", TODAY), TODAY));
    }

    @Test
    void isPast_returnsFalse_whenDueDateIsNull() {
        assertFalse(AssignmentDiffFilter.isPast(assignment("a", null), TODAY));
    }

    // -------------------------------------------------------------------------
    // Removed
    // -------------------------------------------------------------------------

    @Test
    void dropsRemovedAssignmentsDueLastWeek() {
        Assignment lastWeek = assignment("last", TODAY.minusDays(3));
        Assignment thisWeek = assignment("this", TODAY.plusDays(2));

        DiffResult<Assignment> result = filter.filter(
                diff(List.of(), List.of(lastWeek, thisWeek), Map.of()), TODAY);

        assertEquals(List.of(thisWeek), result.removed());
    }

    @Test
    void keepsRemovedAssignmentDueToday() {
        Assignment today = assignment("today", TODAY);

        DiffResult<Assignment> result = filter.filter(
                diff(List.of(), List.of(today), Map.of()), TODAY);

        assertEquals(List.of(today), result.removed());
    }

    // -------------------------------------------------------------------------
    // Modified
    // -------------------------------------------------------------------------

    @Test
    void dropsModifiedAssignmentsDueInThePast() {
        Assignment past = assignment("past", TODAY.minusWeeks(1));
        Assignment future = assignment("future", TODAY.plusWeeks(1));
        FieldChange change = new FieldChange("description", "old", "new");

        DiffResult<Assignment> result = filter.filter(
                diff(List.of(), List.of(),
                        Map.of(past, List.of(change), future, List.of(change))),
                TODAY);

        assertEquals(1, result.modified().size());
        assertTrue(result.modified().containsKey(future));
    }

    // -------------------------------------------------------------------------
    // Added
    // -------------------------------------------------------------------------

    @Test
    void keepsAdditionsRegardlessOfDueDate() {
        Assignment latePublished = assignment("late", TODAY.minusDays(2));
        Assignment upcoming = assignment("upcoming", TODAY.plusDays(4));

        DiffResult<Assignment> result = filter.filter(
                diff(List.of(latePublished, upcoming), List.of(), Map.of()), TODAY);

        assertEquals(List.of(latePublished, upcoming), result.added());
    }

    @Test
    void weeklyWindowRoll_producesNoChangesAtAll() {
        // The exact shape of the Monday roll: the whole of last week disappears, nothing else.
        List<Assignment> lastWeek = List.of(
                assignment("a", TODAY.minusDays(1)),
                assignment("b", TODAY.minusDays(2)),
                assignment("c", TODAY.minusDays(5)));

        DiffResult<Assignment> result = filter.filter(
                diff(List.of(), lastWeek, Map.of()), TODAY);

        assertTrue(result.isEmpty(), "window roll-off must not notify");
    }
}
