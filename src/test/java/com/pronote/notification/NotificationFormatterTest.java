package com.pronote.notification;

import com.pronote.domain.Assignment;
import com.pronote.domain.SchoolLifeEvent;
import com.pronote.domain.TimetableEntry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class NotificationFormatterTest {

    @Test
    void fmtDate_formatsAbbreviatedFrenchWeekday() {
        // 2030-05-06 is a Monday
        assertEquals("lun. 6/05", NotificationFormatter.fmtDate(LocalDate.of(2030, 5, 6)));
    }

    @Test
    void fmtDate_zeroPadsMonth() {
        // 2030-01-01 is a Tuesday
        assertEquals("mar. 1/01", NotificationFormatter.fmtDate(LocalDate.of(2030, 1, 1)));
    }

    @Test
    void fmtDate_nullReturnsPlaceholder() {
        assertEquals("?", NotificationFormatter.fmtDate(null));
    }

    @Test
    void fmtDateTime_includesHourAndMinute() {
        assertEquals("lun. 6/05 08h30",
                NotificationFormatter.fmtDateTime(LocalDateTime.of(2030, 5, 6, 8, 30)));
    }

    @Test
    void fmtDateTime_zeroPadsTime() {
        assertEquals("lun. 6/05 09h05",
                NotificationFormatter.fmtDateTime(LocalDateTime.of(2030, 5, 6, 9, 5)));
    }

    @Test
    void fmtOutOf_dropsTrailingDotZero() {
        assertEquals("20",   NotificationFormatter.fmtOutOf(20.0));
        assertEquals("12.5", NotificationFormatter.fmtOutOf(12.5));
    }

    @Test
    void fmtCoef_dropsTrailingDotZero() {
        assertEquals("2",    NotificationFormatter.fmtCoef(2.0));
        assertEquals("0.5",  NotificationFormatter.fmtCoef(0.5));
    }

    @Test
    void fmtEventType_handlesAllVariants() {
        assertEquals("Absence",     NotificationFormatter.fmtEventType(SchoolLifeEvent.EventType.ABSENCE));
        assertEquals("Retard",      NotificationFormatter.fmtEventType(SchoolLifeEvent.EventType.DELAY));
        assertEquals("Infirmerie",  NotificationFormatter.fmtEventType(SchoolLifeEvent.EventType.INFIRMARY));
        assertEquals("Punition",    NotificationFormatter.fmtEventType(SchoolLifeEvent.EventType.PUNISHMENT));
        assertEquals("Observation", NotificationFormatter.fmtEventType(SchoolLifeEvent.EventType.OBSERVATION));
        assertEquals("Événement",   NotificationFormatter.fmtEventType(SchoolLifeEvent.EventType.OTHER));
        assertEquals("Événement",   NotificationFormatter.fmtEventType(null));
    }

    @Test
    void truncate_shortString_unchanged() {
        assertEquals("abc", NotificationFormatter.truncate("abc", 10));
    }

    @Test
    void truncate_longString_appendsEllipsis() {
        assertEquals("abcde…", NotificationFormatter.truncate("abcdefghij", 5));
    }

    @Test
    void subject_assignment_prefersEnrichedWhenSet() {
        Assignment a = new Assignment();
        a.setSubject("RAW");
        a.setEnrichedSubject("Enriched");
        assertEquals("Enriched", NotificationFormatter.subject(a));
    }

    @Test
    void subject_assignment_fallsBackToRaw_whenEnrichedBlank() {
        Assignment a = new Assignment();
        a.setSubject("RAW");
        a.setEnrichedSubject("   ");
        assertEquals("RAW", NotificationFormatter.subject(a));
    }

    @Test
    void subject_timetableEntry_returnsQuestionMarkWhenBothNull() {
        TimetableEntry e = new TimetableEntry();
        assertEquals("?", NotificationFormatter.subject(e));
    }
}
