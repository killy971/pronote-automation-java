package com.pronote.persistence;

import com.pronote.domain.EntryStatus;
import com.pronote.domain.TimetableEntry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TimetableDiffFilterTest {

    private static final LocalDate MONDAY_CURRENT = LocalDate.of(2026, 3, 30); // a Monday
    private static final LocalDate MONDAY_FUTURE  = MONDAY_CURRENT.plusWeeks(2);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 30, 12, 0);

    private final TimetableDiffFilter filter = new TimetableDiffFilter();

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static TimetableEntry entry(String id, LocalDate day, EntryStatus status) {
        TimetableEntry e = new TimetableEntry();
        e.setId(id);
        e.setSubject("Maths");
        e.setStatus(status);
        e.setStartTime(day.atTime(8, 0));
        e.setEndTime(day.atTime(9, 0));
        return e;
    }

    private static TimetableEntry entryWithLabel(String id, LocalDate day, String statusLabel) {
        TimetableEntry e = entry(id, day, EntryStatus.NORMAL);
        e.setStatusLabel(statusLabel);
        return e;
    }

    private static DiffResult<TimetableEntry> diff(
            List<TimetableEntry> added,
            List<TimetableEntry> removed,
            Map<TimetableEntry, List<FieldChange>> modified) {
        return new DiffResult<>(added, removed, modified);
    }

    // -------------------------------------------------------------------------
    // isPast
    // -------------------------------------------------------------------------

    @Test
    void isPast_returnsFalse_whenEndTimeIsAfterNow() {
        TimetableEntry e = entry("x", MONDAY_CURRENT.plusDays(1), EntryStatus.NORMAL);
        assertFalse(TimetableDiffFilter.isPast(e, NOW));
    }

    @Test
    void isPast_returnsTrue_whenEndTimeIsBeforeNow() {
        TimetableEntry e = entry("x", MONDAY_CURRENT.minusDays(1), EntryStatus.NORMAL);
        assertTrue(TimetableDiffFilter.isPast(e, NOW));
    }

    @Test
    void isPast_returnsFalse_whenEndTimeIsNull() {
        TimetableEntry e = entry("x", MONDAY_CURRENT.minusDays(5), EntryStatus.NORMAL);
        e.setEndTime(null);
        assertFalse(TimetableDiffFilter.isPast(e, NOW));
    }

    // -------------------------------------------------------------------------
    // isNoteworthyTimetableEntry
    // -------------------------------------------------------------------------

    @Test
    void isNoteworthy_normal_isFalse() {
        assertFalse(TimetableDiffFilter.isNoteworthyTimetableEntry(
                entry("x", MONDAY_FUTURE, EntryStatus.NORMAL)));
    }

    @Test
    void isNoteworthy_cancelled_isTrue() {
        assertTrue(TimetableDiffFilter.isNoteworthyTimetableEntry(
                entry("x", MONDAY_FUTURE, EntryStatus.CANCELLED)));
    }

    @Test
    void isNoteworthy_modified_isTrue() {
        assertTrue(TimetableDiffFilter.isNoteworthyTimetableEntry(
                entry("x", MONDAY_FUTURE, EntryStatus.MODIFIED)));
    }

    @Test
    void isNoteworthy_exempted_isTrue() {
        assertTrue(TimetableDiffFilter.isNoteworthyTimetableEntry(
                entry("x", MONDAY_FUTURE, EntryStatus.EXEMPTED)));
    }

    @Test
    void isNoteworthy_normalWithStatusLabel_isTrue() {
        assertTrue(TimetableDiffFilter.isNoteworthyTimetableEntry(
                entryWithLabel("x", MONDAY_FUTURE, "Prof. absent")));
    }

    @Test
    void isNoteworthy_normalWithBlankLabel_isFalse() {
        assertTrue(!TimetableDiffFilter.isNoteworthyTimetableEntry(
                entryWithLabel("x", MONDAY_FUTURE, "   ")) == true
                || !TimetableDiffFilter.isNoteworthyTimetableEntry(
                entryWithLabel("x", MONDAY_FUTURE, "")));
        // Both blank and empty label → not noteworthy
        assertFalse(TimetableDiffFilter.isNoteworthyTimetableEntry(entryWithLabel("x", MONDAY_FUTURE, "")));
        assertFalse(TimetableDiffFilter.isNoteworthyTimetableEntry(entryWithLabel("x", MONDAY_FUTURE, "  ")));
    }

    // -------------------------------------------------------------------------
    // Past items suppressed in removed/modified
    // -------------------------------------------------------------------------

    @Test
    void pastRemovedItems_areSuppressed() {
        TimetableEntry past = entry("past", MONDAY_CURRENT.minusDays(2), EntryStatus.NORMAL);
        TimetableEntry future = entry("future", MONDAY_CURRENT.plusDays(2), EntryStatus.NORMAL);

        DiffResult<TimetableEntry> raw = diff(List.of(), List.of(past, future), Map.of());
        DiffResult<TimetableEntry> result = filter.filter(raw, List.of(), MONDAY_FUTURE, NOW);

        assertEquals(1, result.getRemoved().size());
        assertEquals("future", result.getRemoved().get(0).getId());
    }

    @Test
    void pastModifiedItems_areSuppressed() {
        TimetableEntry past = entry("past", MONDAY_CURRENT.minusDays(2), EntryStatus.NORMAL);
        TimetableEntry future = entry("future", MONDAY_CURRENT.plusDays(2), EntryStatus.NORMAL);

        List<FieldChange> changes = List.of(new FieldChange("room", "A", "B"));
        DiffResult<TimetableEntry> raw = diff(List.of(), List.of(),
                Map.of(past, changes, future, changes));
        DiffResult<TimetableEntry> result = filter.filter(raw, List.of(), MONDAY_FUTURE, NOW);

        assertEquals(1, result.getModified().size());
        assertTrue(result.getModified().containsKey(future));
        assertFalse(result.getModified().containsKey(past));
    }

    // -------------------------------------------------------------------------
    // Newly discovered furthest week: normal entries suppressed
    // -------------------------------------------------------------------------

    @Test
    void normalAdditions_inNewlyDiscoveredWeek_areSuppressed() {
        TimetableEntry newNormal = entry("n1", MONDAY_FUTURE, EntryStatus.NORMAL);
        TimetableEntry newNormal2 = entry("n2", MONDAY_FUTURE.plusDays(1), EntryStatus.NORMAL);

        DiffResult<TimetableEntry> raw = diff(List.of(newNormal, newNormal2), List.of(), Map.of());
        // previousSnapshot has NO entries in MONDAY_FUTURE week → newly discovered
        DiffResult<TimetableEntry> result = filter.filter(raw, List.of(), MONDAY_FUTURE, NOW);

        assertTrue(result.getAdded().isEmpty(),
                "Normal entries in newly discovered week must be suppressed");
    }

    @Test
    void noteworthyAdditions_inNewlyDiscoveredWeek_areKept() {
        TimetableEntry cancelled = entry("c1", MONDAY_FUTURE, EntryStatus.CANCELLED);
        TimetableEntry normal    = entry("n1", MONDAY_FUTURE.plusDays(1), EntryStatus.NORMAL);

        DiffResult<TimetableEntry> raw = diff(List.of(cancelled, normal), List.of(), Map.of());
        DiffResult<TimetableEntry> result = filter.filter(raw, List.of(), MONDAY_FUTURE, NOW);

        assertEquals(1, result.getAdded().size());
        assertEquals("c1", result.getAdded().get(0).getId());
    }

    @Test
    void additions_inAlreadyKnownFurthestWeek_areNotSuppressed() {
        TimetableEntry existing = entry("e1", MONDAY_FUTURE, EntryStatus.NORMAL);
        TimetableEntry newEntry = entry("n1", MONDAY_FUTURE.plusDays(1), EntryStatus.NORMAL);

        // previousSnapshot already has an entry in MONDAY_FUTURE week → not newly discovered
        DiffResult<TimetableEntry> raw = diff(List.of(newEntry), List.of(), Map.of());
        DiffResult<TimetableEntry> result = filter.filter(raw, List.of(existing), MONDAY_FUTURE, NOW);

        assertEquals(1, result.getAdded().size(),
                "Week was already known — additions must not be suppressed");
    }

    // -------------------------------------------------------------------------
    // Additions outside the furthest week are never suppressed
    // -------------------------------------------------------------------------

    @Test
    void normalAdditions_inCurrentWeek_areNeverSuppressed() {
        TimetableEntry curr = entry("c1", MONDAY_CURRENT.plusDays(2), EntryStatus.NORMAL);

        DiffResult<TimetableEntry> raw = diff(List.of(curr), List.of(), Map.of());
        // furthest week is MONDAY_FUTURE, which has nothing in previous snapshot
        DiffResult<TimetableEntry> result = filter.filter(raw, List.of(), MONDAY_FUTURE, NOW);

        assertEquals(1, result.getAdded().size(),
                "Additions in current/intermediate weeks must never be suppressed");
    }

    // -------------------------------------------------------------------------
    // Idempotency: running twice with no data change produces empty diff
    // -------------------------------------------------------------------------

    @Test
    void idempotent_emptyRawDiff_remainsEmpty() {
        DiffResult<TimetableEntry> raw = diff(List.of(), List.of(), Map.of());
        DiffResult<TimetableEntry> result = filter.filter(raw, List.of(), MONDAY_FUTURE, NOW);
        assertTrue(result.isEmpty());
    }
}
