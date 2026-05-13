package com.pronote.notification;

import com.pronote.domain.Assignment;
import com.pronote.domain.AttachmentRef;
import com.pronote.domain.CompetenceEvaluation;
import com.pronote.domain.EntryStatus;
import com.pronote.domain.Grade;
import com.pronote.domain.SchoolLifeEvent;
import com.pronote.domain.TimetableEntry;
import com.pronote.persistence.DiffResult;
import com.pronote.persistence.FieldChange;
import com.pronote.persistence.Identifiable;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NotificationPayloadBuilderTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static <T extends Identifiable> DiffResult<T> empty() {
        return new DiffResult<>(List.of(), List.of(), Map.of());
    }

    private static Assignment newAssignment(String id, String enrichedSubject, LocalDate due) {
        Assignment a = new Assignment();
        a.setId(id);
        a.setSubject("RAW");
        a.setEnrichedSubject(enrichedSubject);
        a.setDueDate(due);
        return a;
    }

    private static TimetableEntry newTimetableEntry(String subject, LocalDateTime start,
                                                     EntryStatus status) {
        TimetableEntry e = new TimetableEntry();
        e.setId(subject + "@" + start);
        e.setSubject(subject);
        e.setEnrichedSubject(subject);
        e.setStartTime(start);
        e.setEndTime(start.plusHours(1));
        e.setStatus(status);
        return e;
    }

    private static Grade newGrade(String subject, String value, double outOf, LocalDate date) {
        Grade g = new Grade();
        g.setId(subject + "@" + date);
        g.setSubject(subject);
        g.setEnrichedSubject(subject);
        g.setValue(value);
        g.setOutOf(outOf);
        g.setCoefficient(1.0);
        g.setDate(date);
        return g;
    }

    private static SchoolLifeEvent newAbsence(LocalDate date) {
        SchoolLifeEvent e = new SchoolLifeEvent();
        e.setId("ABSENCE@" + date);
        e.setType(SchoolLifeEvent.EventType.ABSENCE);
        e.setDate(date);
        return e;
    }

    // -------------------------------------------------------------------------
    // Title shape
    // -------------------------------------------------------------------------

    @Test
    void emptyDiff_producesCatchAllTitle() {
        NotificationPayload p = NotificationPayloadBuilder.build(
                empty(), empty(), empty(), empty(), empty());
        assertEquals("Pronote: 0 modification(s)", p.title());
        assertEquals("", p.body());
        assertEquals(NotificationPayload.Priority.NORMAL, p.priority());
        assertEquals(List.of("school", "pronote"), p.tags());
    }

    @Test
    void singleAddedAssignment_titleIncludesSubjectAndDate() {
        DiffResult<Assignment> diff = new DiffResult<>(
                List.of(newAssignment("a1", "Maths", LocalDate.of(2030, 5, 6))),
                List.of(), Map.of());

        NotificationPayload p = NotificationPayloadBuilder.build(
                diff, empty(), empty(), empty(), empty());
        assertTrue(p.title().startsWith("📚 Maths · "),
                "title should lead with the book emoji + subject; was: " + p.title());
        assertTrue(p.title().contains("6/05"));
    }

    @Test
    void multipleAddedAssignments_titleCollapsesToCount() {
        DiffResult<Assignment> diff = new DiffResult<>(
                List.of(
                        newAssignment("a1", "Maths",   LocalDate.of(2030, 5, 6)),
                        newAssignment("a2", "Anglais", LocalDate.of(2030, 5, 7)),
                        newAssignment("a3", "Histoire", LocalDate.of(2030, 5, 8))),
                List.of(), Map.of());

        NotificationPayload p = NotificationPayloadBuilder.build(
                diff, empty(), empty(), empty(), empty());
        assertTrue(p.title().contains("📚 3 devoirs"), "got: " + p.title());
    }

    @Test
    void singleAddedGrade_titleIncludesValueAndOutOf() {
        DiffResult<Grade> diff = new DiffResult<>(
                List.of(newGrade("Maths", "15", 20.0, LocalDate.of(2030, 5, 6))),
                List.of(), Map.of());
        NotificationPayload p = NotificationPayloadBuilder.build(
                empty(), empty(), diff, empty(), empty());
        assertTrue(p.title().contains("📊 Maths: 15/20"), "got: " + p.title());
    }

    @Test
    void cancelledTimetable_triggersHighPriority() {
        TimetableEntry cancelled = newTimetableEntry(
                "Maths", LocalDateTime.of(2030, 5, 6, 8, 0), EntryStatus.CANCELLED);
        cancelled.setStatusLabel("Prof. absent");

        // Modified entry whose status field flipped to CANCELLED
        Map<TimetableEntry, List<FieldChange>> modified = new LinkedHashMap<>();
        modified.put(cancelled, List.of(new FieldChange("status", "NORMAL", "CANCELLED")));
        DiffResult<TimetableEntry> ttDiff = new DiffResult<>(List.of(), List.of(), modified);

        NotificationPayload p = NotificationPayloadBuilder.build(
                empty(), ttDiff, empty(), empty(), empty());

        assertEquals(NotificationPayload.Priority.HIGH, p.priority());
        assertTrue(p.title().startsWith("✗ "), "got: " + p.title());
        assertTrue(p.title().contains("Prof. absent"));
    }

    @Test
    void addedUpcomingEval_getsDistinctEvalToken() {
        TimetableEntry eval = newTimetableEntry(
                "Maths", LocalDateTime.of(2030, 5, 8, 10, 0), EntryStatus.NORMAL);
        eval.setEval(true);
        eval.setLessonLabel("DS révision");
        DiffResult<TimetableEntry> ttDiff = new DiffResult<>(
                List.of(eval), List.of(), Map.of());

        NotificationPayload p = NotificationPayloadBuilder.build(
                empty(), ttDiff, empty(), empty(), empty());

        assertTrue(p.title().contains("📝 Maths éval · "), "got: " + p.title());
        // The body should NOT count this eval addition as a generic timetable modification
        assertFalse(p.title().contains("modif. EDT"));
    }

    @Test
    void schoolLifeAddition_triggersHighPriority() {
        DiffResult<SchoolLifeEvent> slDiff = new DiffResult<>(
                List.of(newAbsence(LocalDate.of(2030, 5, 6))),
                List.of(), Map.of());

        NotificationPayload p = NotificationPayloadBuilder.build(
                empty(), empty(), empty(), empty(), slDiff);
        assertEquals(NotificationPayload.Priority.HIGH, p.priority());
        assertTrue(p.title().contains("🏫 Absence · "), "got: " + p.title());
    }

    @Test
    void title_capsAtSeventyTwoCharacters() {
        // Construct many diffs to push the title over the 72-char cap
        DiffResult<Assignment> asgnDiff = new DiffResult<>(
                List.of(newAssignment("a", "VeryLongSubjectName", LocalDate.of(2030, 5, 6))),
                List.of(), Map.of());
        DiffResult<Grade> gradeDiff = new DiffResult<>(
                List.of(newGrade("OtherLongSubject", "17.5", 20.0, LocalDate.of(2030, 5, 6))),
                List.of(), Map.of());
        TimetableEntry cancelled = newTimetableEntry(
                "AnotherLongSubject", LocalDateTime.of(2030, 5, 6, 8, 0), EntryStatus.CANCELLED);
        cancelled.setStatusLabel("explication très longue qui va déborder");
        Map<TimetableEntry, List<FieldChange>> modified = new LinkedHashMap<>();
        modified.put(cancelled, List.of(new FieldChange("status", "NORMAL", "CANCELLED")));
        DiffResult<TimetableEntry> ttDiff = new DiffResult<>(List.of(), List.of(), modified);

        NotificationPayload p = NotificationPayloadBuilder.build(
                asgnDiff, ttDiff, gradeDiff, empty(), empty());

        assertTrue(p.title().length() <= 72, "title exceeds 72 chars: " + p.title());
        assertTrue(p.title().endsWith("…"), "title should end with ellipsis when truncated");
    }

    // -------------------------------------------------------------------------
    // Body shape
    // -------------------------------------------------------------------------

    @Test
    void addedAssignment_body_includesPrefixSubjectAndDate() {
        Assignment a = newAssignment("a1", "Maths", LocalDate.of(2030, 5, 6));
        a.setDescription("synthetic description");
        DiffResult<Assignment> diff = new DiffResult<>(List.of(a), List.of(), Map.of());

        NotificationPayload p = NotificationPayloadBuilder.build(
                diff, empty(), empty(), empty(), empty());

        assertTrue(p.body().contains("+ Maths · "), "body: " + p.body());
        assertTrue(p.body().contains("synthetic description"));
        // No section header when only one section has content
        assertFalse(p.body().startsWith("📚"));
    }

    @Test
    void multiSection_body_includesEmojiHeaders() {
        DiffResult<Assignment> asgnDiff = new DiffResult<>(
                List.of(newAssignment("a", "Maths", LocalDate.of(2030, 5, 6))),
                List.of(), Map.of());
        DiffResult<Grade> gradeDiff = new DiffResult<>(
                List.of(newGrade("Maths", "15", 20.0, LocalDate.of(2030, 5, 6))),
                List.of(), Map.of());

        NotificationPayload p = NotificationPayloadBuilder.build(
                asgnDiff, empty(), gradeDiff, empty(), empty());

        assertTrue(p.body().contains("📚 Devoirs"));
        assertTrue(p.body().contains("📊 Notes"));
    }

    @Test
    void assignmentWithFileAttachment_renderedInBody() {
        Assignment a = newAssignment("a1", "Maths", LocalDate.of(2030, 5, 6));
        AttachmentRef ref = new AttachmentRef();
        ref.setFileName("synth.pdf");
        ref.setUploadedFile(true);
        a.setAttachments(List.of(ref));

        DiffResult<Assignment> diff = new DiffResult<>(List.of(a), List.of(), Map.of());
        NotificationPayload p = NotificationPayloadBuilder.build(
                diff, empty(), empty(), empty(), empty());

        assertTrue(p.body().contains("[📎 synth.pdf]"), "body: " + p.body());
    }

    @Test
    void modifiedAssignment_doneFlipped_rendersFriendlyLabel() {
        Assignment a = newAssignment("a1", "Maths", LocalDate.of(2030, 5, 6));
        Map<Assignment, List<FieldChange>> modified = new LinkedHashMap<>();
        modified.put(a, List.of(new FieldChange("done", false, true)));
        DiffResult<Assignment> diff = new DiffResult<>(List.of(), List.of(), modified);

        NotificationPayload p = NotificationPayloadBuilder.build(
                diff, empty(), empty(), empty(), empty());

        assertTrue(p.body().contains("marqué fait"), "body: " + p.body());
    }

    @Test
    void timetableRemoval_triggersHighPriority() {
        TimetableEntry removed = newTimetableEntry(
                "Maths", LocalDateTime.of(2030, 5, 6, 8, 0), EntryStatus.NORMAL);
        DiffResult<TimetableEntry> ttDiff = new DiffResult<>(
                List.of(), List.of(removed), Map.of());

        NotificationPayload p = NotificationPayloadBuilder.build(
                empty(), ttDiff, empty(), empty(), empty());
        assertEquals(NotificationPayload.Priority.HIGH, p.priority());
        assertTrue(p.body().contains("- Maths"));
    }

    @Test
    void normalChangesOnly_haveNormalPriority() {
        Assignment a = newAssignment("a1", "Maths", LocalDate.of(2030, 5, 6));
        DiffResult<Assignment> diff = new DiffResult<>(List.of(a), List.of(), Map.of());

        NotificationPayload p = NotificationPayloadBuilder.build(
                diff, empty(), empty(), empty(), empty());
        assertEquals(NotificationPayload.Priority.NORMAL, p.priority());
    }

    @Test
    void addedEvaluation_renderedInBody() {
        CompetenceEvaluation ev = new CompetenceEvaluation();
        ev.setId("EV@2030-05-06");
        ev.setSubject("Maths");
        ev.setEnrichedSubject("Maths");
        ev.setName("Eval chap 3");
        ev.setDate(LocalDate.of(2030, 5, 6));
        ev.setPeriodName("Trimestre 2");

        DiffResult<CompetenceEvaluation> diff = new DiffResult<>(
                List.of(ev), List.of(), Map.of());

        NotificationPayload p = NotificationPayloadBuilder.build(
                empty(), empty(), empty(), diff, empty());
        assertTrue(p.title().contains("📋 Maths éval."));
        assertTrue(p.body().contains("Eval chap 3"));
        assertTrue(p.body().contains("[Trimestre 2]"));
    }
}
