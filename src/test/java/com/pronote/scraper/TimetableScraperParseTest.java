package com.pronote.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronote.config.AppConfig;
import com.pronote.config.SubjectEnricher;
import com.pronote.domain.EntryStatus;
import com.pronote.domain.TimetableEntry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimetableScraperParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static TimetableScraper scraper() {
        SubjectEnricher enricher = new SubjectEnricher(new AppConfig.SubjectEnrichmentConfig());
        return new TimetableScraper(enricher);
    }

    @Test
    void parse_emptyResponse_returnsEmptyList() throws Exception {
        List<TimetableEntry> out = scraper().parseTimetable(MAPPER.readTree("{}"), null);
        assertTrue(out.isEmpty());
    }

    @Test
    void parse_singleEntry_mapsSubjectTeacherRoomAndTime() throws Exception {
        // duree=4 → 60 minutes (4 × 15-min slots)
        String json = """
            {
              "ListeCours": [
                {"V": {
                  "DateDuCours": {"V": "06/05/2030 08:00:00"},
                  "duree": 4,
                  "ListeContenus": {"V": [
                    {"G": 16, "L": "MATHEMATIQUES"},
                    {"G": 3,  "L": "TEACHER_ALPHA"},
                    {"G": 17, "L": "Room 101"}
                  ]}
                }}
              ]
            }
            """;
        List<TimetableEntry> out = scraper().parseTimetable(MAPPER.readTree(json), null);
        assertEquals(1, out.size());

        TimetableEntry e = out.get(0);
        assertEquals("MATHEMATIQUES", e.getSubject());
        assertEquals("TEACHER_ALPHA", e.getTeacher());
        assertEquals("Room 101",      e.getRoom());
        assertEquals(LocalDateTime.of(2030, 5, 6, 8, 0), e.getStartTime());
        assertEquals(LocalDateTime.of(2030, 5, 6, 9, 0), e.getEndTime());
        assertEquals(EntryStatus.NORMAL, e.getStatus());
        assertFalse(e.isEval());
        assertFalse(e.isTest());
    }

    @Test
    void parse_cancelledEntry_setsStatusAndLabel() throws Exception {
        String json = """
            {
              "ListeCours": [
                {"V": {
                  "DateDuCours": {"V": "06/05/2030 10:00:00"},
                  "duree": 4,
                  "estAnnule": true,
                  "Statut": "Prof. absent",
                  "ListeContenus": {"V": [
                    {"G": 16, "L": "PHYS"},
                    {"G": 3,  "L": "TEACHER_X"},
                    {"G": 17, "L": "Lab A"}
                  ]}
                }}
              ]
            }
            """;
        List<TimetableEntry> out = scraper().parseTimetable(MAPPER.readTree(json), null);
        TimetableEntry e = out.get(0);
        assertEquals(EntryStatus.CANCELLED, e.getStatus());
        assertEquals("Prof. absent", e.getStatusLabel());
    }

    @Test
    void parse_evalEntry_setsEvalFlagAndLessonLabel() throws Exception {
        String json = """
            {
              "ListeCours": [
                {"V": {
                  "DateDuCours": {"V": "07/05/2030 14:00:00"},
                  "duree": 4,
                  "ListeContenus": {"V": [
                    {"G": 16, "L": "HIST"},
                    {"G": 3,  "L": "TEACHER_Y"},
                    {"G": 17, "L": "Room 12"}
                  ]},
                  "cahierDeTextes": {"V": {
                    "estEval": true,
                    "estDevoir": false,
                    "originesCategorie": {"V": [
                      {"G": 7, "L": "Évaluation de compétences"}
                    ]}
                  }}
                }}
              ]
            }
            """;
        List<TimetableEntry> out = scraper().parseTimetable(MAPPER.readTree(json), null);
        TimetableEntry e = out.get(0);
        assertTrue(e.isEval());
        assertEquals("Évaluation de compétences", e.getLessonLabel());
    }

    @Test
    void parse_groupFilter_excludesMismatchedGroupOnlyEntries() throws Exception {
        String json = """
            {
              "ListeCours": [
                {"V": {
                  "DateDuCours": {"V": "06/05/2030 08:00:00"},
                  "duree": 4,
                  "ListeContenus": {"V": [
                    {"G": 16, "L": "MATHS"},
                    {"G": 2,  "L": "6A SIA G2"}
                  ]}
                }},
                {"V": {
                  "DateDuCours": {"V": "06/05/2030 09:00:00"},
                  "duree": 4,
                  "ListeContenus": {"V": [
                    {"G": 16, "L": "MATHS"},
                    {"G": 2,  "L": "6A SIA G1"}
                  ]}
                }}
              ]
            }
            """;
        List<TimetableEntry> out = scraper().parseTimetable(MAPPER.readTree(json), "6A SIA G1");
        assertEquals(1, out.size());
        assertEquals(LocalDateTime.of(2030, 5, 6, 9, 0), out.get(0).getStartTime());
    }

    @Test
    void parse_classLevelEntryWithoutGroupTag_kept_evenWithGroupFilter() throws Exception {
        // An entry with no G=2 element is whole-class and must always survive the filter
        String json = """
            {
              "ListeCours": [
                {"V": {
                  "DateDuCours": {"V": "06/05/2030 11:00:00"},
                  "duree": 4,
                  "ListeContenus": {"V": [
                    {"G": 16, "L": "PHYS"}
                  ]}
                }}
              ]
            }
            """;
        List<TimetableEntry> out = scraper().parseTimetable(MAPPER.readTree(json), "6A SIA G1");
        assertEquals(1, out.size());
    }

    @Test
    void parse_multipleG2Labels_anyMatchKeepsEntry() throws Exception {
        // pronotepy bug fix: entries may have multiple G=2 labels (class + group),
        // any one matching the filter should keep the entry.
        String json = """
            {
              "ListeCours": [
                {"V": {
                  "DateDuCours": {"V": "06/05/2030 13:00:00"},
                  "duree": 4,
                  "ListeContenus": {"V": [
                    {"G": 16, "L": "MATHS"},
                    {"G": 2,  "L": "6A"},
                    {"G": 2,  "L": "6A SIA G1"}
                  ]}
                }}
              ]
            }
            """;
        List<TimetableEntry> out = scraper().parseTimetable(MAPPER.readTree(json), "6A SIA G1");
        assertEquals(1, out.size());
    }
}
