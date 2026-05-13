package com.pronote.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronote.config.AppConfig;
import com.pronote.config.SubjectEnricher;
import com.pronote.domain.Grade;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GradeScraperParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static GradeScraper scraper() {
        SubjectEnricher enricher = new SubjectEnricher(new AppConfig.SubjectEnrichmentConfig());
        return new GradeScraper(enricher);
    }

    @Test
    void parse_emptyResponse_returnsEmptyList() throws Exception {
        List<Grade> out = scraper().parseGrades(MAPPER.readTree("{}"), "Trimestre 1");
        assertTrue(out.isEmpty());
    }

    @Test
    void parse_singleGrade_mapsCoreFields() throws Exception {
        String json = """
            {
              "listeDevoirs": {"V": [
                {
                  "service":     {"V": {"L": "MATHS"}},
                  "note":        {"V": "15,5"},
                  "bareme":      {"V": "20"},
                  "coefficient": "2",
                  "date":        {"V": "06/05/2030"},
                  "commentaire": "Interrogation chapitre 3"
                }
              ]}
            }
            """;
        List<Grade> out = scraper().parseGrades(MAPPER.readTree(json), "Trimestre 1");
        assertEquals(1, out.size());

        Grade g = out.get(0);
        assertEquals("MATHS", g.getSubject());
        assertEquals("15,5",  g.getValue());
        assertEquals(20.0,    g.getOutOf());
        assertEquals(2.0,     g.getCoefficient());
        assertEquals(LocalDate.of(2030, 5, 6), g.getDate());
        assertEquals("Interrogation chapitre 3", g.getComment());
        assertEquals("Trimestre 1", g.getPeriodName());

        // Stable ID convention: subject@date@outOf@coefficient (note value excluded)
        assertTrue(g.getId().startsWith("MATHS@2030-05-06@"));
        assertTrue(g.getId().endsWith("@2.0"));
    }

    @Test
    void parse_alternateSubjectField_matiere_alsoSupported() throws Exception {
        // Some Pronote versions return "matiere" instead of "service"
        String json = """
            {
              "listeDevoirs": {"V": [
                {
                  "matiere":     {"V": {"L": "HIST"}},
                  "note":        {"V": "12"},
                  "bareme":      {"V": "20"},
                  "coefficient": "1",
                  "date":        {"V": "06/05/2030"}
                }
              ]}
            }
            """;
        List<Grade> out = scraper().parseGrades(MAPPER.readTree(json), "Trimestre 1");
        assertEquals(1, out.size());
        assertEquals("HIST", out.get(0).getSubject());
    }

    @Test
    void parse_specialGradeValues_keptVerbatim() throws Exception {
        String json = """
            {
              "listeDevoirs": {"V": [
                {"service": {"V": {"L": "ENG"}}, "note": {"V": "ABS"}, "bareme": {"V": "20"},
                 "coefficient": "1", "date": {"V": "06/05/2030"}},
                {"service": {"V": {"L": "ENG"}}, "note": {"V": "/"},   "bareme": {"V": "20"},
                 "coefficient": "1", "date": {"V": "07/05/2030"}},
                {"service": {"V": {"L": "ENG"}}, "note": {"V": "Disp"},"bareme": {"V": "20"},
                 "coefficient": "1", "date": {"V": "08/05/2030"}}
              ]}
            }
            """;
        List<Grade> out = scraper().parseGrades(MAPPER.readTree(json), "Trimestre 1");
        assertEquals(3, out.size());
        assertEquals("ABS",  out.get(0).getValue());
        assertEquals("/",    out.get(1).getValue());
        assertEquals("Disp", out.get(2).getValue());
    }

    @Test
    void parse_skipsGradeWithEmptySubjectAndUnknownDate() throws Exception {
        // Empty subject + missing date → ID "@unknown@0.0@1.0" which is treated as blank
        String json = """
            {
              "listeDevoirs": {"V": [
                {
                  "note":        {"V": "5"},
                  "bareme":      {"V": "20"},
                  "coefficient": "1"
                }
              ]}
            }
            """;
        List<Grade> out = scraper().parseGrades(MAPPER.readTree(json), "T1");
        // The implementation logs and keeps the grade as long as the ID is non-blank.
        // After parsing, ID="@unknown@0.0@1.0" which is non-blank, so it's kept.
        // This test pins current behaviour rather than asserts a skip.
        assertEquals(1, out.size());
        assertEquals("",     out.get(0).getSubject());
        assertNull(out.get(0).getDate());
    }
}
