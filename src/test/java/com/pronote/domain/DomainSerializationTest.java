package com.pronote.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sanity round-trip tests for the domain types persisted into snapshot files.
 * These ensure Jackson handles the POJO/record mix correctly and that no field
 * silently disappears across a serialise/deserialise cycle.
 */
class DomainSerializationTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void assignment_roundTrip_preservesAllFields() throws Exception {
        Assignment original = new Assignment();
        original.setId("synthetic@2030-05-01@2030-04-25");
        original.setSubject("SYN_SUBJECT");
        original.setEnrichedSubject("Synthetic Subject");
        original.setTeacher("Test Teacher");
        original.setDescription("synthetic description");
        original.setDueDate(LocalDate.of(2030, 5, 1));
        original.setAssignedDate(LocalDate.of(2030, 4, 25));
        original.setDone(true);

        AttachmentRef ref = new AttachmentRef();
        ref.setStableId("synth|file.pdf");
        ref.setFileName("file.pdf");
        ref.setUploadedFile(true);
        ref.setLocalPath("/tmp/synth/file.pdf");
        ref.setMimeType("application/pdf");
        // downloadUrl is @JsonIgnore — should NOT survive a round-trip
        ref.setDownloadUrl("https://example.invalid/session-scoped");
        original.setAttachments(List.of(ref));

        String json = MAPPER.writeValueAsString(original);
        Assignment restored = MAPPER.readValue(json, Assignment.class);

        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getSubject(), restored.getSubject());
        assertEquals(original.getEnrichedSubject(), restored.getEnrichedSubject());
        assertEquals(original.getTeacher(), restored.getTeacher());
        assertEquals(original.getDescription(), restored.getDescription());
        assertEquals(original.getDueDate(), restored.getDueDate());
        assertEquals(original.getAssignedDate(), restored.getAssignedDate());
        assertTrue(restored.isDone());
        assertEquals(1, restored.getAttachments().size());

        AttachmentRef restoredRef = restored.getAttachments().get(0);
        assertEquals("synth|file.pdf", restoredRef.getStableId());
        assertEquals("file.pdf", restoredRef.getFileName());
        assertTrue(restoredRef.isUploadedFile());
        assertEquals("/tmp/synth/file.pdf", restoredRef.getLocalPath());
        assertEquals("application/pdf", restoredRef.getMimeType());
        // Session-scoped URL must not be persisted
        assertNull(restoredRef.getDownloadUrl(),
                "downloadUrl is @JsonIgnore and must not appear in the snapshot");
    }

    @Test
    void timetableEntry_roundTrip_preservesStatusAndEvalFlags() throws Exception {
        TimetableEntry e = new TimetableEntry();
        e.setId("SYN@2030-05-01T08:00:NORMAL");
        e.setSubject("SYN_SUBJECT");
        e.setEnrichedSubject("Synthetic Subject");
        e.setTeacher("Synthetic Teacher");
        e.setRoom("Room 101");
        e.setStartTime(LocalDateTime.of(2030, 5, 1, 8, 0));
        e.setEndTime(LocalDateTime.of(2030, 5, 1, 9, 0));
        e.setStatus(EntryStatus.CANCELLED);
        e.setStatusLabel("Prof. absent");
        e.setEval(true);
        e.setTest(false);
        e.setLessonLabel("Évaluation de compétences");
        e.setMemo("apporter la calculatrice");

        String json = MAPPER.writeValueAsString(e);
        TimetableEntry restored = MAPPER.readValue(json, TimetableEntry.class);

        assertEquals(e.getId(), restored.getId());
        assertEquals(e.getSubject(), restored.getSubject());
        assertEquals(e.getRoom(), restored.getRoom());
        assertEquals(e.getStartTime(), restored.getStartTime());
        assertEquals(e.getEndTime(), restored.getEndTime());
        assertEquals(EntryStatus.CANCELLED, restored.getStatus());
        assertEquals("Prof. absent", restored.getStatusLabel());
        assertTrue(restored.isEval());
        assertFalse(restored.isTest());
        assertEquals("Évaluation de compétences", restored.getLessonLabel());
        assertEquals("apporter la calculatrice", restored.getMemo());
    }

    @Test
    void competenceEvaluation_roundTripWithAcquisitions() throws Exception {
        CompetenceEvaluation ev = new CompetenceEvaluation();
        ev.setId("SYN_SUBJECT@2030-04-10@Synthetic Eval");
        ev.setSubject("SYN_SUBJECT");
        ev.setName("Synthetic Eval");
        ev.setDate(LocalDate.of(2030, 4, 10));
        ev.setPeriodName("Trimestre 1");

        CompetenceAcquisition ca = new CompetenceAcquisition();
        ca.setName("Compétence A");
        ca.setLevel("A Acquis");
        ca.setAbbreviation("A");
        ev.setAcquisitions(List.of(ca));

        String json = MAPPER.writeValueAsString(ev);
        CompetenceEvaluation restored = MAPPER.readValue(json, CompetenceEvaluation.class);

        assertEquals(ev.getId(),         restored.getId());
        assertEquals(ev.getSubject(),    restored.getSubject());
        assertEquals(ev.getName(),       restored.getName());
        assertEquals(ev.getDate(),       restored.getDate());
        assertEquals(ev.getPeriodName(), restored.getPeriodName());
        assertEquals(1, restored.getAcquisitions().size());
        assertEquals("Compétence A", restored.getAcquisitions().get(0).getName());
        assertEquals("A Acquis",     restored.getAcquisitions().get(0).getLevel());
        assertEquals("A",            restored.getAcquisitions().get(0).getAbbreviation());
    }

    @Test
    void deserialise_tolerantOfUnknownFields() throws Exception {
        // Pronote API field names occasionally drift; @JsonIgnoreProperties(ignoreUnknown = true)
        // must keep us safe from breaking on unexpected fields in archived snapshots.
        String json = "{\"id\":\"x\",\"subject\":\"S\",\"someBrandNewField\":42}";
        Assignment a = MAPPER.readValue(json, Assignment.class);
        assertEquals("x", a.getId());
        assertEquals("S", a.getSubject());
    }
}
