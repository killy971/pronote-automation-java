package com.pronote.views;

import com.pronote.domain.Assignment;
import com.pronote.domain.AttachmentRef;
import com.pronote.domain.TimetableEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for {@link AssignmentHtmlGenerator}. We don't pin the exact markup (it would
 * be too brittle), but we assert key invariants: subject is rendered, descriptions appear,
 * attachments produce links, scripts are escaped, and the output is a self-contained
 * HTML5 document.
 */
class AssignmentHtmlGeneratorTest {

    private static Assignment assignment(String id, String subject, LocalDate dueDate,
                                          String description) {
        Assignment a = new Assignment();
        a.setId(id);
        a.setSubject(subject);
        a.setEnrichedSubject(subject);
        a.setDueDate(dueDate);
        a.setAssignedDate(dueDate.minusDays(7));
        a.setDescription(description);
        return a;
    }

    @Test
    void generate_emptyInputs_producesValidEmptyDocument(@TempDir Path outDir) {
        String html = new AssignmentHtmlGenerator().generate(List.of(), List.of(), outDir);
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.trim().endsWith("</html>"));
        // Self-contained: no external CSS or JS
        assertFalse(html.contains("<link "));
        assertTrue(html.contains("<style>"));
    }

    @Test
    void generate_singleUpcomingAssignment_includesSubjectAndDescription(@TempDir Path outDir) {
        Assignment a = assignment("a-1", "SYN_MATHS",
                LocalDate.now().plusDays(3), "synthetic description body");
        String html = new AssignmentHtmlGenerator().generate(
                List.of(a), List.of(), outDir);

        assertTrue(html.contains("SYN_MATHS"));
        assertTrue(html.contains("synthetic description body"));
    }

    @Test
    void generate_pastAssignment_isExcluded(@TempDir Path outDir) {
        Assignment past = assignment("p-1", "PAST_SUBJECT",
                LocalDate.now().minusDays(2), "old description");
        String html = new AssignmentHtmlGenerator().generate(
                List.of(past), List.of(), outDir);
        // Subject of the past assignment must not appear in the rendered upcoming list
        assertFalse(html.contains("PAST_SUBJECT"),
                "assignments with dueDate < today should be filtered out");
    }

    @Test
    void generate_hyperlinkAttachment_emitsAnchor(@TempDir Path outDir) {
        Assignment a = assignment("a-link", "SYN_ENG",
                LocalDate.now().plusDays(5), "see attached link");
        AttachmentRef ref = new AttachmentRef();
        ref.setUploadedFile(false);
        ref.setFileName("Synth Resource.pdf");
        ref.setUrl("https://example.invalid/synth-resource.pdf");
        ref.setStableId("https://example.invalid/synth-resource.pdf");
        a.setAttachments(List.of(ref));

        String html = new AssignmentHtmlGenerator().generate(
                List.of(a), List.of(), outDir);

        assertTrue(html.contains("https://example.invalid/synth-resource.pdf"));
        assertTrue(html.contains("Synth Resource.pdf"));
    }

    @Test
    void generate_descriptionWithScriptTag_isEscaped(@TempDir Path outDir) {
        Assignment a = assignment("a-xss", "SYN_HIST",
                LocalDate.now().plusDays(2),
                "<script>alert('xss')</script>"
                        + " & ampersand & < less-than >");

        String html = new AssignmentHtmlGenerator().generate(
                List.of(a), List.of(), outDir);

        // The raw script tag must not appear unescaped in the output
        assertFalse(html.contains("<script>alert('xss')</script>"),
                "<script> in description must be HTML-escaped");
    }

    @Test
    void generate_upcomingEvalFromTimetable_isSurfaced(@TempDir Path outDir) {
        // Empty assignments — only an upcoming eval in the timetable
        TimetableEntry eval = new TimetableEntry();
        eval.setId("eval-1");
        eval.setSubject("SYN_PHYS");
        eval.setEnrichedSubject("SYN_PHYS");
        eval.setEval(true);
        eval.setStartTime(LocalDateTime.of(LocalDate.now().plusDays(4), java.time.LocalTime.of(10, 0)));
        eval.setEndTime(eval.getStartTime().plusHours(1));
        eval.setLessonLabel("DS révision chap. 7");

        String html = new AssignmentHtmlGenerator().generate(
                List.of(), List.of(eval), outDir);

        // Subject of the eval should appear; we don't pin the exact banner copy
        assertTrue(html.contains("SYN_PHYS"));
        assertTrue(html.contains("DS révision chap. 7"));
    }
}
