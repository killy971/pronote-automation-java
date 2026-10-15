package com.pronote.views;

import com.pronote.domain.EntryStatus;
import com.pronote.domain.TimetableEntry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins how the day view reads a slot Pronote has cancelled and refilled — in particular the
 * refill that does not share the cancelled lesson's hours, which is what a "sortie pédagogique"
 * across the lunch break is.
 */
class TimetableHtmlGeneratorTest {

    private static final LocalDate DAY = LocalDate.of(2030, 5, 6);

    private static TimetableEntry entry(String id, String subject, EntryStatus status,
                                        int fromHour, int toHour) {
        TimetableEntry e = new TimetableEntry();
        e.setId(id);
        e.setSubject(subject);
        e.setEnrichedSubject(subject);
        e.setStartTime(LocalDateTime.of(DAY, java.time.LocalTime.of(fromHour, 0)));
        e.setEndTime(LocalDateTime.of(DAY, java.time.LocalTime.of(toHour, 0)));
        e.setStatus(status);
        e.setTeacher("SYN_PROF");
        e.setRoom("");
        return e;
    }

    private static String render(List<TimetableEntry> entries) {
        return new TimetableHtmlGenerator().generate(DAY, null, null, entries, Map.of());
    }

    @Test
    void aLongReplacementAbsorbsEveryLessonItCovers() {
        TimetableEntry outing = entry("o", "Sortie pédagogique", EntryStatus.NORMAL, 11, 13);
        outing.setOuting(true);
        TimetableEntry maths = entry("m", "SYN_MATHS", EntryStatus.CANCELLED, 11, 12);
        TimetableEntry francais = entry("f", "SYN_FRANCAIS", EntryStatus.CANCELLED, 12, 13);

        List<TimetableHtmlGenerator.MergedEntry> merged =
                TimetableHtmlGenerator.collapseSlots(List.of(maths, outing, francais));

        assertEquals(1, merged.size(), "both cancellations fold into the outing's card: " + merged);
        assertSame(outing, merged.get(0).entry());
        assertEquals(List.of(maths, francais), merged.get(0).cancelledSiblings());
    }

    @Test
    void aStandaloneCancellationIsStillShown() {
        TimetableEntry maths = entry("m", "SYN_MATHS", EntryStatus.CANCELLED, 11, 12);
        TimetableEntry anglais = entry("a", "SYN_ANGLAIS", EntryStatus.NORMAL, 14, 15);

        List<TimetableHtmlGenerator.MergedEntry> merged =
                TimetableHtmlGenerator.collapseSlots(List.of(maths, anglais));

        assertEquals(2, merged.size());
        assertTrue(merged.stream().anyMatch(m -> m.entry() == maths));
        assertTrue(merged.stream().allMatch(m -> m.cancelledSiblings().isEmpty()));
    }

    @Test
    void aManualEvalInTheSlotDoesNotHideTheCancellation() {
        // A synthetic eval marker is not a lesson, so it cannot make a cancelled slot look filled.
        TimetableEntry maths = entry("m", "SYN_MATHS", EntryStatus.CANCELLED, 11, 12);
        TimetableEntry manual = entry("manual:SYN_MATHS@2030-05-06", "SYN_MATHS",
                EntryStatus.NORMAL, 11, 12);
        manual.setEval(true);
        manual.setLessonLabel("Éval. de compétences");

        List<TimetableHtmlGenerator.MergedEntry> merged =
                TimetableHtmlGenerator.collapseSlots(List.of(maths, manual));

        assertTrue(merged.stream().anyMatch(m -> m.entry() == maths),
                "the cancellation must survive: " + merged);
    }

    @Test
    void aSameSubjectTakeoverCarriesNoReplacesNote() {
        TimetableEntry ghost = entry("g", "SYN_MATHS", EntryStatus.CANCELLED, 11, 12);
        TimetableEntry twin = entry("t", "SYN_MATHS", EntryStatus.NORMAL, 11, 12);

        List<TimetableHtmlGenerator.MergedEntry> merged =
                TimetableHtmlGenerator.collapseSlots(List.of(ghost, twin));

        assertEquals(1, merged.size());
        assertSame(twin, merged.get(0).entry());
        assertTrue(merged.get(0).cancelledSiblings().isEmpty(),
                "the lesson still happens, so there is nothing to annotate");
    }

    @Test
    void aMultiHourEntryShowsBothEndsOfItsSpan() {
        TimetableEntry outing = entry("o", "Sortie pédagogique", EntryStatus.NORMAL, 11, 13);
        outing.setOuting(true);

        String html = render(List.of(outing));

        assertTrue(html.contains(">11h00</time>"), "start time missing");
        assertTrue(html.contains("<span class=\"slot__time-end\">"), "span end markup missing");
        assertTrue(html.contains(">13h00</time>"), "end time missing");
        assertTrue(html.contains("Sortie pédagogique"), "outing badge/label missing");
    }

    @Test
    void anOrdinaryPeriodShowsOnlyItsStart() {
        String html = render(List.of(entry("m", "SYN_MATHS", EntryStatus.NORMAL, 11, 12)));

        assertTrue(html.contains(">11h00</time>"));
        // The stylesheet always defines the rule; what must be absent is the element.
        assertFalse(html.contains("<span class=\"slot__time-end\">"),
                "a one-hour lesson needs no span");
    }

    @Test
    void theReplacesNoteNamesEveryLessonTheCardTookOver() {
        TimetableEntry outing = entry("o", "Sortie pédagogique", EntryStatus.NORMAL, 11, 13);
        TimetableEntry maths = entry("m", "SYN_MATHS", EntryStatus.CANCELLED, 11, 12);
        TimetableEntry francais = entry("f", "SYN_FRANCAIS", EntryStatus.CANCELLED, 12, 13);

        String html = render(List.of(maths, outing, francais));

        assertEquals(2, html.split("<span class=\"lesson__replaced-detail\">", -1).length - 1,
                "one note per lesson taken over");
        assertTrue(html.contains("SYN_MATHS"), "got: " + html);
        assertTrue(html.contains("SYN_FRANCAIS"), "got: " + html);
    }
}
