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

    /** An entry with its own hours, for the spans a one-hour period cannot express. */
    private static TimetableEntry span(String id, String subject, EntryStatus status,
                                       int fromHour, int toHour) {
        TimetableEntry e = entry(id, subject, status, null);
        e.setStartTime(SLOT.withHour(fromHour));
        e.setEndTime(SLOT.withHour(toHour));
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
    void aLongEntryTakesOverEveryLessonItRunsAcross() {
        // A "sortie pédagogique" runs 11:00–13:00 over two cancelled one-hour lessons. Matching
        // on a shared start time saw only the 11:00 one as taken over and called the 12:00 one a
        // plain cancellation.
        TimetableEntry outing = span("o", "Sortie pédagogique", EntryStatus.NORMAL, 11, 13);
        TimetableEntry first   = span("1", "SYN_MATHS", EntryStatus.CANCELLED, 11, 12);
        TimetableEntry second  = span("2", "SYN_FRANCAIS", EntryStatus.CANCELLED, 12, 13);
        TimetableSlots slots = TimetableSlots.index(List.of(first, outing, second));

        assertSame(outing, slots.supersederOf(first));
        assertSame(outing, slots.supersederOf(second));
        assertEquals(TimetableSlots.SlotOutcome.REPLACED, slots.outcomeOf(first));
        assertEquals(TimetableSlots.SlotOutcome.REPLACED, slots.outcomeOf(second));
    }

    @Test
    void anOverlapThatDoesNotCoverTheStart_isNotATakeover() {
        // Two lessons at 14:00 and 14:30 overlap without either standing in for the other;
        // pairing them would read an ordinary half-hour offset as a replacement.
        TimetableEntry off   = span("1", "SYN_MATHS", EntryStatus.CANCELLED, 14, 15);
        TimetableEntry later = span("2", "SYN_PHYSIQUE", EntryStatus.NORMAL, 14, 15);
        later.setStartTime(SLOT.withHour(14).withMinute(30));
        later.setEndTime(SLOT.withHour(15).withMinute(30));
        TimetableSlots slots = TimetableSlots.index(List.of(off, later));

        assertNull(slots.supersederOf(off));
        assertEquals(TimetableSlots.SlotOutcome.CANCELLED, slots.outcomeOf(off));
    }

    @Test
    void theOwnLessonWinsOverALongerEntryCoveringTheSameHour() {
        // A room change inside the hours an outing also covers must still resolve to its own
        // lesson, or the notification calls a maintained lesson a replacement.
        TimetableEntry ghost  = span("1", "SYN_MATHS", EntryStatus.CANCELLED, 11, 12);
        TimetableEntry outing = span("o", "Sortie pédagogique", EntryStatus.NORMAL, 11, 13);
        TimetableEntry twin   = span("2", "SYN_MATHS", EntryStatus.NORMAL, 11, 12);
        TimetableSlots slots = TimetableSlots.index(List.of(ghost, outing, twin));

        assertSame(twin, slots.supersederOf(ghost));
        assertEquals(TimetableSlots.SlotOutcome.MAINTAINED, slots.outcomeOf(ghost));
    }

    @Test
    void aCancelledEntryNeverTakesOverAnother() {
        // Both halves of a whole morning being called off: neither covers the other.
        TimetableEntry longOff  = span("1", "SYN_SORTIE", EntryStatus.CANCELLED, 11, 13);
        TimetableEntry shortOff = span("2", "SYN_MATHS", EntryStatus.CANCELLED, 12, 13);
        TimetableSlots slots = TimetableSlots.index(List.of(longOff, shortOff));

        assertNull(slots.supersederOf(shortOff));
        assertNull(slots.supersederOf(longOff));
    }

    @Test
    void entriesOnOtherDaysAreNeverCandidates() {
        TimetableEntry off      = entry("1", "SYN_MATHS", EntryStatus.CANCELLED, "B303");
        TimetableEntry nextWeek = entry("2", "SYN_MATHS", EntryStatus.NORMAL, "B203");
        nextWeek.setStartTime(SLOT.plusWeeks(1));
        nextWeek.setEndTime(SLOT.plusWeeks(1).plusHours(1));
        TimetableSlots slots = TimetableSlots.index(List.of(off, nextWeek));

        assertNull(slots.supersederOf(off));
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
