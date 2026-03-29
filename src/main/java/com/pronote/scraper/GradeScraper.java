package com.pronote.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pronote.auth.PronoteSession;
import com.pronote.client.ApiFunction;
import com.pronote.client.PronoteHttpClient;
import com.pronote.domain.Grade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fetches grades (marks) from Pronote using {@code DernieresNotes} (onglet 198).
 *
 * <p>One request is made per academic period. Periods are populated in
 * {@link PronoteSession} during login from {@code FonctionParametres → General.ListePeriodes}.
 * If the session has no periods (older persisted session), this scraper returns an empty list
 * and logs a warning; grades will be fetched on the next run after a fresh login.
 *
 * <p>French field mapping:
 * <pre>
 *   service.V.L  → subject
 *   note.V       → value     (grade as string, e.g. "15,5", "ABS", "/")
 *   bareme.V     → outOf     (denominator)
 *   coefficient  → coefficient
 *   date.V       → date      ("DD/MM/YYYY HH:MM:SS")
 *   commentaire  → comment
 *   periode.V.L  → periodName
 * </pre>
 *
 * <p>Stable ID: {@code subject@date@outOf@coefficient}.
 * Pronote's {@code N} field is session-scoped and must not be used.
 */
public class GradeScraper {

    private static final Logger log = LoggerFactory.getLogger(GradeScraper.class);

    private static final DateTimeFormatter PRONOTE_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final DateTimeFormatter PRONOTE_DATE_SHORT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ObjectMapper jackson = new ObjectMapper();

    /**
     * Fetches all grades for every available academic period.
     *
     * @param session active, authenticated Pronote session
     * @param client  HTTP client
     * @param periods academic periods from the session (from {@code PronoteSession.getPeriods()})
     * @return combined list of grades across all periods (may be empty)
     */
    public List<Grade> fetch(PronoteSession session, PronoteHttpClient client,
                             List<PronoteSession.Period> periods) {
        if (periods == null || periods.isEmpty()) {
            log.warn("No academic periods in session — cannot fetch grades. "
                    + "Periods are populated during full login; delete session.json to trigger a fresh login.");
            return Collections.emptyList();
        }

        List<Grade> all = new ArrayList<>();
        for (PronoteSession.Period period : periods) {
            log.info("Fetching grades for period: {}", period.getName());

            ObjectNode params = jackson.createObjectNode();
            params.set("Periode", jackson.createObjectNode()
                    .put("N", period.getId())
                    .put("L", period.getName()));
            // Parent accounts are scoped via Signature.membre (handled by PronoteHttpClient).
            // DernieresNotes does not use a separate "ressource" param (per pronotepy).

            JsonNode response = client.encryptedPost(session, ApiFunction.GRADES, params, 198);
            log.debug("Grades response for period '{}': {}", period.getName(), response);

            List<Grade> periodGrades = parseGrades(response, period.getName());
            log.info("  → {} grade(s) in period '{}'", periodGrades.size(), period.getName());
            all.addAll(periodGrades);
        }

        log.info("Fetched {} grade(s) total across {} period(s)", all.size(), periods.size());
        return all;
    }

    private List<Grade> parseGrades(JsonNode response, String periodName) {
        // Response: { "listeDevoirs": { "V": [ { grade object }, ... ] } }
        JsonNode wrapper = response.get("listeDevoirs");
        JsonNode list = null;
        if (wrapper != null) {
            list = wrapper.has("V") ? wrapper.get("V") : wrapper;
        }
        if (list == null || !list.isArray()) {
            log.debug("No 'listeDevoirs' array in grades response (period may have no grades yet)");
            if (response.isArray()) {
                list = response;
            } else {
                log.warn("Unexpected grades response structure for period '{}'. Raw: {}", periodName, response);
                return Collections.emptyList();
            }
        }

        List<Grade> grades = new ArrayList<>();
        for (JsonNode item : list) {
            JsonNode data = item.has("V") ? item.get("V") : item;
            try {
                Grade g = mapGrade(data, periodName);
                if (g.getId() != null && !g.getId().isBlank()) {
                    grades.add(g);
                } else {
                    log.warn("Skipping grade with empty stable ID: {}", data);
                }
            } catch (Exception e) {
                log.warn("Skipping malformed grade entry: {} — {}", data, e.getMessage());
            }
        }
        return grades;
    }

    private Grade mapGrade(JsonNode data, String periodName) {
        log.debug("Mapping grade entry: {}", data);
        Grade g = new Grade();

        // subject: service.V.L  (some Pronote versions use "matiere.V.L" instead)
        JsonNode service = data.get("service");
        if (service == null) service = data.get("matiere");
        if (service != null) {
            JsonNode serviceV = service.has("V") ? service.get("V") : service;
            g.setSubject(getString(serviceV, "L", ""));
        }
        if (g.getSubject() == null || g.getSubject().isBlank()) {
            g.setSubject("");
        }

        // date: date.V
        JsonNode dateNode = data.get("date");
        if (dateNode != null) {
            String dateStr = dateNode.has("V") ? dateNode.get("V").asText("") : dateNode.asText("");
            g.setDate(parseDate(dateStr));
        }

        // value: note.V (grade as string — keep as-is, may be "15,5", "ABS", "/" etc.)
        JsonNode noteNode = data.get("note");
        if (noteNode != null) {
            g.setValue(noteNode.has("V") ? noteNode.get("V").asText("") : noteNode.asText(""));
        }

        // outOf: bareme.V (denominator)
        JsonNode baremeNode = data.get("bareme");
        if (baremeNode != null) {
            String baremeStr = baremeNode.has("V") ? baremeNode.get("V").asText("0") : baremeNode.asText("0");
            g.setOutOf(parseDouble(baremeStr, 0.0));
        }

        // coefficient
        g.setCoefficient(parseDouble(getString(data, "coefficient", "1"), 1.0));

        // comment: commentaire (may be a test title like "Interrogation ch.3")
        g.setComment(getString(data, "commentaire", ""));

        g.setPeriodName(periodName);

        // Stable ID: subject@date@outOf@coefficient
        // NOTE: Both outOf and coefficient describe the test (not the result), so they are
        // stable identifiers. The grade value itself is intentionally excluded so that
        // grade corrections are detected as modifications, not as new entries.
        String datePart = g.getDate() != null ? g.getDate().toString() : "unknown";
        g.setId(g.getSubject() + "@" + datePart + "@" + g.getOutOf() + "@" + g.getCoefficient());

        return g;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String getString(JsonNode node, String field, String defaultValue) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? defaultValue : v.asText(defaultValue);
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim(), PRONOTE_DATE);
        } catch (DateTimeParseException e1) {
            try {
                return LocalDate.parse(s.trim(), PRONOTE_DATE_SHORT);
            } catch (DateTimeParseException e2) {
                log.debug("Could not parse Pronote date '{}': {}", s, e2.getMessage());
                return null;
            }
        }
    }

    private static double parseDouble(String s, double defaultValue) {
        if (s == null || s.isBlank()) return defaultValue;
        try {
            // Pronote may use comma as decimal separator
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
