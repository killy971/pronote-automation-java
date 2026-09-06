package com.pronote.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimetableSlotsTest {

    private static final LocalDateTime SLOT = LocalDateTime.of(2030, 5, 6, 8, 0);

    private static TimetableEntry entry(String id, String subject, EntryStatus status, String room) {
        TimetableEntry e = new TimetableEntry();
        e.setId(id);
        e.setSubject(subject);
        e.setEnrichedSubject(subject);
        e.setStartTime(SLOT);
        e.setEndTime(SLOT.plusHours(1));
        e.setStatus(status);
        e.setRoom(room);
        return e;
    }

    @Test
    void cancelledAlone_isARealCancellation() {
        TimetableEntry off = entry("1", "SYN_MATHS", EntryStatus.CANCELLED, "B303");
        TimetableSlots slots = TimetableSlots.index(List.of(off));

        assertNull(slots.supersederOf(off));
        assertEquals(TimetableSlots.SlotOutcome.CANCELLED, slots.outcomeOf(off));
    }

    @Test
    void sameSubjectInTheSlot_meansTheLessonIsMaintained() {
        // Pronote's shape for a room change: the original slot cancelled, a twin carrying the new room.
        TimetableEntry ghost   = entry("1", "SYN_MATHS", EntryStatus.CANCELLED, "B303");
        TimetableEntry current = entry("2", "SYN_MATHS", EntryStatus.NORMAL, "B203");
        TimetableSlots slots = TimetableSlots.index(List.of(ghost, current));

        assertSame(current, slots.supersederOf(ghost));
        assertEquals(TimetableSlots.SlotOutcome.MAINTAINED, slots.outcomeOf(ghost));
    }

    @Test
    void differentSubjectInTheSlot_meansReplacement() {
        TimetableEntry ghost   = entry("1", "SYN_MATHS", EntryStatus.CANCELLED, "B303");
        TimetableEntry current = entry("2", "SYN_ANGLAIS", EntryStatus.NORMAL, "A101");
        TimetableSlots slots = TimetableSlots.index(List.of(ghost, current));

        assertSame(current, slots.supersederOf(ghost));
        assertEquals(TimetableSlots.SlotOutcome.REPLACED, slots.outcomeOf(ghost));
    }

    @Test
    void sameSubjectWinsOverAnotherLessonAtTheSameHour() {
        TimetableEntry ghost    = entry("1", "SYN_MATHS", EntryStatus.CANCELLED, "B303");
        TimetableEntry unrelated = entry("2", "SYN_ANGLAIS", EntryStatus.NORMAL, "A101");
        TimetableEntry twin      = entry("3", "SYN_MATHS", EntryStatus.NORMAL, "B203");
        TimetableSlots slots = TimetableSlots.index(List.of(ghost, unrelated, twin));

        assertSame(twin, slots.supersederOf(ghost));
        assertEquals(TimetableSlots.SlotOutcome.MAINTAINED, slots.outcomeOf(ghost));
    }

    @Test
    void manualEvalDoesNotMaskARealCancellation() {
        TimetableEntry ghost = entry("1", "SYN_MATHS", EntryStatus.CANCELLED, "B303");
        TimetableEntry manualEval = entry("manual:SYN_MATHS@2030-05-06", "SYN_MATHS",
                EntryStatus.NORMAL, null);
        manualEval.setEval(true);
        TimetableSlots slots = TimetableSlots.index(List.of(ghost, manualEval));

        assertNull(slots.supersederOf(ghost));
        assertEquals(TimetableSlots.SlotOutcome.CANCELLED, slots.outcomeOf(ghost));
    }

    @Test
    void exemptedCountsAsInactiveOnBothSides() {
        TimetableEntry exempt = entry("1", "SYN_SPORT", EntryStatus.EXEMPTED, "GYM");
        TimetableEntry other  = entry("2", "SYN_ANGLAIS", EntryStatus.NORMAL, "A101");

        assertTrue(TimetableSlots.isInactive(exempt));
        assertFalse(TimetableSlots.isActive(exempt));
        // An exempted entry never supersedes anything either.
        assertNull(TimetableSlots.index(List.of(other, exempt)).supersederOf(other));
    }

    @Test
    void entriesWithoutAStartTimeAreIgnored() {
        TimetableEntry orphan = entry("1", "SYN_MATHS", EntryStatus.CANCELLED, "B303");
        orphan.setStartTime(null);
        TimetableSlots slots = TimetableSlots.index(List.of(orphan));

        assertTrue(slots.slots().isEmpty());
        assertEquals(TimetableSlots.SlotOutcome.CANCELLED, slots.outcomeOf(orphan));
    }
}
