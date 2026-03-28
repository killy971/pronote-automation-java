package com.pronote.persistence;

import com.pronote.domain.Assignment;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffEngineTest {

    private final DiffEngine engine = new DiffEngine();

    private static Assignment assignment(String id, String subject, boolean done) {
        Assignment a = new Assignment();
        a.setId(id);
        a.setSubject(subject);
        a.setDone(done);
        a.setDueDate(LocalDate.of(2025, 4, 1));
        return a;
    }

    @Test
    void noDiff_whenSnapshotsAreEqual() {
        List<Assignment> a = List.of(assignment("1", "Math", false));
        List<Assignment> b = List.of(assignment("1", "Math", false));
        DiffResult<Assignment> result = engine.diff(a, b);
        assertTrue(result.isEmpty());
    }

    @Test
    void detectsAddedItem() {
        List<Assignment> prev = List.of(assignment("1", "Math", false));
        List<Assignment> curr = List.of(
                assignment("1", "Math", false),
                assignment("2", "French", false));

        DiffResult<Assignment> result = engine.diff(prev, curr);
        assertEquals(1, result.getAdded().size());
        assertEquals("2", result.getAdded().get(0).getId());
        assertTrue(result.getRemoved().isEmpty());
        assertTrue(result.getModified().isEmpty());
    }

    @Test
    void detectsRemovedItem() {
        List<Assignment> prev = List.of(
                assignment("1", "Math", false),
                assignment("2", "French", false));
        List<Assignment> curr = List.of(assignment("1", "Math", false));

        DiffResult<Assignment> result = engine.diff(prev, curr);
        assertEquals(1, result.getRemoved().size());
        assertEquals("2", result.getRemoved().get(0).getId());
    }

    @Test
    void detectsModifiedField_done() {
        Assignment before = assignment("1", "Math", false);
        Assignment after  = assignment("1", "Math", true);

        DiffResult<Assignment> result = engine.diff(List.of(before), List.of(after));
        assertTrue(result.getAdded().isEmpty());
        assertTrue(result.getRemoved().isEmpty());
        assertEquals(1, result.getModified().size());

        List<FieldChange> changes = result.getModified().values().iterator().next();
        assertEquals(1, changes.size());
        assertEquals("done", changes.get(0).getFieldName());
        assertEquals("false", changes.get(0).getOldValue());
        assertEquals("true", changes.get(0).getNewValue());
    }

    @Test
    void detectsModifiedField_subject() {
        Assignment before = assignment("1", "Math", false);
        Assignment after  = assignment("1", "Mathematics", false);

        DiffResult<Assignment> result = engine.diff(List.of(before), List.of(after));
        assertFalse(result.getModified().isEmpty());
        List<FieldChange> changes = result.getModified().values().iterator().next();
        assertTrue(changes.stream().anyMatch(c -> "subject".equals(c.getFieldName())));
    }

    @Test
    void emptyPreviousIsAllAdded() {
        List<Assignment> curr = List.of(
                assignment("1", "Math", false),
                assignment("2", "French", false));

        DiffResult<Assignment> result = engine.diff(List.of(), curr);
        assertEquals(2, result.getAdded().size());
        assertTrue(result.getRemoved().isEmpty());
        assertTrue(result.getModified().isEmpty());
    }

    @Test
    void emptyCurrentIsAllRemoved() {
        List<Assignment> prev = List.of(
                assignment("1", "Math", false),
                assignment("2", "French", false));

        DiffResult<Assignment> result = engine.diff(prev, List.of());
        assertEquals(2, result.getRemoved().size());
        assertTrue(result.getAdded().isEmpty());
    }
}
