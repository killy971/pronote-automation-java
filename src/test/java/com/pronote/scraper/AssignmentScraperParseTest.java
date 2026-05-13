package com.pronote.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronote.auth.PronoteSession;
import com.pronote.config.AppConfig;
import com.pronote.config.SubjectEnricher;
import com.pronote.domain.Assignment;
import com.pronote.domain.AttachmentRef;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link AssignmentScraper#parseAssignments(JsonNode, PronoteSession)} with synthetic
 * Pronote JSON fixtures. No network, no real credentials. The session is constructed locally
 * with the default AES key/IV so that {@code G=1} attachment URL building does not crash.
 */
class AssignmentScraperParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AssignmentScraper scraper() {
        SubjectEnricher enricher = new SubjectEnricher(new AppConfig.SubjectEnrichmentConfig());
        return new AssignmentScraper(enricher);
    }

    private static PronoteSession syntheticSession() {
        PronoteSession s = new PronoteSession("https://example.invalid/test/");
        s.setSessionHandle(42);
        return s;
    }

    @Test
    void parse_emptyResponse_returnsEmptyList() throws Exception {
        JsonNode response = MAPPER.readTree("{}");
        List<Assignment> out = scraper().parseAssignments(response, syntheticSession());
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    @Test
    void parse_singleAssignment_mapsAllStandardFields() throws Exception {
        String json = """
            {
              "ListeTravauxAFaire": {
                "V": [
                  {
                    "V": {
                      "Matiere": {"V": {"L": "MATHS"}},
                      "PourLe":  {"V": "06/05/2030"},
                      "DonneLe": {"V": "29/04/2030"},
                      "descriptif": {"V": "<p>Faire <b>les exercices</b> 1 à 5</p>"},
                      "TAFFait": false,
                      "ListePieceJointe": {"V": []}
                    }
                  }
                ]
              }
            }
            """;
        JsonNode response = MAPPER.readTree(json);
        List<Assignment> out = scraper().parseAssignments(response, syntheticSession());

        assertEquals(1, out.size());
        Assignment a = out.get(0);
        assertEquals("MATHS", a.getSubject());
        assertEquals(LocalDate.of(2030, 5, 6),  a.getDueDate());
        assertEquals(LocalDate.of(2030, 4, 29), a.getAssignedDate());
        assertEquals("Faire les exercices 1 à 5", a.getDescription(),
                "HTML tags should be stripped");
        assertFalse(a.isDone());
        // Stable content-based ID
        assertEquals("MATHS@2030-05-06@2030-04-29", a.getId());
    }

    @Test
    void parse_hyperlinkAttachment_capturedAsG0() throws Exception {
        String json = """
            {
              "ListeTravauxAFaire": {
                "V": [
                  {
                    "V": {
                      "Matiere": {"V": {"L": "ENG"}},
                      "PourLe": {"V": "06/05/2030"},
                      "DonneLe": {"V": "29/04/2030"},
                      "descriptif": {"V": ""},
                      "TAFFait": false,
                      "ListePieceJointe": {"V": [
                        {"G": 0, "url": "https://example.invalid/resource.pdf", "L": "Synth Link.pdf"}
                      ]}
                    }
                  }
                ]
              }
            }
            """;
        JsonNode response = MAPPER.readTree(json);
        List<Assignment> out = scraper().parseAssignments(response, syntheticSession());

        assertEquals(1, out.size());
        List<AttachmentRef> atts = out.get(0).getAttachments();
        assertEquals(1, atts.size());
        AttachmentRef ref = atts.get(0);
        assertFalse(ref.isUploadedFile());
        assertEquals("https://example.invalid/resource.pdf", ref.getUrl());
        assertEquals("https://example.invalid/resource.pdf", ref.getStableId());
        assertEquals("Synth Link.pdf", ref.getFileName());
        // Hyperlink attachments must not produce a downloadUrl
        assertNull(ref.getDownloadUrl());
    }

    @Test
    void parse_uploadedFileAttachment_g1_buildsTransientDownloadUrl() throws Exception {
        String json = """
            {
              "ListeTravauxAFaire": {
                "V": [
                  {
                    "V": {
                      "Matiere": {"V": {"L": "HIST"}},
                      "PourLe": {"V": "06/05/2030"},
                      "DonneLe": {"V": "29/04/2030"},
                      "descriptif": {"V": ""},
                      "TAFFait": false,
                      "ListePieceJointe": {"V": [
                        {"G": 1, "N": "37#synthtoken", "L": "carte.png"}
                      ]}
                    }
                  }
                ]
              }
            }
            """;
        JsonNode response = MAPPER.readTree(json);
        List<Assignment> out = scraper().parseAssignments(response, syntheticSession());

        AttachmentRef ref = out.get(0).getAttachments().get(0);
        assertTrue(ref.isUploadedFile());
        // Stable ID uses assignmentId + "|" + fileName — never the session-scoped N field
        assertTrue(ref.getStableId().endsWith("|carte.png"), "stableId=" + ref.getStableId());
        assertFalse(ref.getStableId().contains("synthtoken"),
                "session-scoped token must not leak into stable ID");
        assertNotNull(ref.getDownloadUrl(), "G=1 attachments must have a downloadUrl");
        assertTrue(ref.getDownloadUrl().contains("FichiersExternes/"));
    }

    @Test
    void parse_donneIsTrue_markedDone() throws Exception {
        String json = """
            {
              "ListeTravauxAFaire": {
                "V": [
                  {
                    "V": {
                      "Matiere": {"V": {"L": "GEO"}},
                      "PourLe": {"V": "06/05/2030"},
                      "DonneLe": {"V": "29/04/2030"},
                      "TAFFait": true
                    }
                  }
                ]
              }
            }
            """;
        JsonNode response = MAPPER.readTree(json);
        List<Assignment> out = scraper().parseAssignments(response, syntheticSession());
        assertTrue(out.get(0).isDone());
    }

    @Test
    void parse_unknownAttachmentType_isSkippedSilently() throws Exception {
        String json = """
            {
              "ListeTravauxAFaire": {
                "V": [
                  {
                    "V": {
                      "Matiere": {"V": {"L": "PHYS"}},
                      "PourLe": {"V": "06/05/2030"},
                      "DonneLe": {"V": "29/04/2030"},
                      "ListePieceJointe": {"V": [
                        {"G": 99, "L": "unknown"}
                      ]}
                    }
                  }
                ]
              }
            }
            """;
        JsonNode response = MAPPER.readTree(json);
        List<Assignment> out = scraper().parseAssignments(response, syntheticSession());
        assertEquals(1, out.size());
        assertTrue(out.get(0).getAttachments().isEmpty());
    }

    @Test
    void parse_unexpectedRootStructure_returnsEmpty() throws Exception {
        // A plain object with no recognised key — not an array, not a ListeTravauxAFaire wrapper
        JsonNode response = MAPPER.readTree("{\"someOtherKey\": 42}");
        List<Assignment> out = scraper().parseAssignments(response, syntheticSession());
        assertTrue(out.isEmpty(), "unknown structure should yield empty list, not throw");
    }

    @Test
    void parse_unwrappedArray_alsoAccepted() throws Exception {
        // Pronote's response is normally wrapped, but the parser also accepts a top-level array
        String json = """
            [
              {"V": {
                "Matiere": {"V": {"L": "RAW_ARRAY_SUBJ"}},
                "PourLe":  {"V": "06/05/2030"},
                "DonneLe": {"V": "29/04/2030"}
              }}
            ]
            """;
        JsonNode response = MAPPER.readTree(json);
        List<Assignment> out = scraper().parseAssignments(response, syntheticSession());
        assertEquals(1, out.size());
        assertEquals("RAW_ARRAY_SUBJ", out.get(0).getSubject());
    }
}
