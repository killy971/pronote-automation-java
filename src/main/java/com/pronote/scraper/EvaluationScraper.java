package com.pronote.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pronote.auth.PronoteSession;
import com.pronote.client.ApiFunction;
import com.pronote.client.PronoteHttpClient;
import com.pronote.domain.CompetenceAcquisition;
import com.pronote.domain.CompetenceEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fetches competence-based evaluations from Pronote using {@code DernieresEvaluations} (onglet 201).
 *
 * <p>One request is made per academic period. Periods come from {@link PronoteSession#getPeriods()},
 * populated during login. If the session has no periods, this scraper returns an empty list
 * and logs a warning.
 *
 * <p>French field mapping:
 * <pre>
 *   L                            → name         (evaluation title)
 *   matiere.V.L                  → subject
 *   date.V                       → date         ("DD/MM/YYYY HH:MM:SS")
 *   individu.V.L                 → teacher
 *   descriptif                   → description
 *   periode.V.L                  → periodName
 *   listeNiveauxDAcquisitions.V  → acquisitions (competence levels)
 * </pre>
 *
 * <p>Stable ID: {@code subject@date@name}.
 * Pronote's {@code N} field is session-scoped and must not be used.
 */
public class EvaluationScraper {

    private static final Logger log = LoggerFactory.getLogger(EvaluationScraper.class);

    private static final DateTimeFormatter PRONOTE_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final DateTimeFormatter PRONOTE_DATE_SHORT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ObjectMapper jackson = new ObjectMapper();

    /**
     * Fetches all competence evaluations for every available academic period.
     *
     * @param session active, authenticated Pronote session
     * @param client  HTTP client
     * @param periods academic periods from the session
     * @return combined list of evaluations across all periods (may be empty)
     */
    public List<CompetenceEvaluation> fetch(PronoteSession session, PronoteHttpClient client,
                                            List<PronoteSession.Period> periods) {
        if (periods == null || periods.isEmpty()) {
            log.warn("No academic periods in session — cannot fetch competence evaluations. "
                    + "Delete session.json to trigger a fresh login and populate periods.");
            return Collections.emptyList();
        }

        // Evaluations are only available in trimester periods (G type == 1).
        // Other period types (semesters, whole year) return empty results — skip them.
        List<PronoteSession.Period> targetPeriods = periods.stream()
                .filter(p -> p.getType() == 1)
                .collect(java.util.stream.Collectors.toList());
        if (targetPeriods.isEmpty()) {
            log.debug("No trimester periods found (G=1) — using all {} period(s)", periods.size());
            targetPeriods = periods;
        } else {
            log.info("Using {} trimester period(s) for evaluations (skipping {} other period(s))",
                    targetPeriods.size(), periods.size() - targetPeriods.size());
        }

        List<CompetenceEvaluation> all = new ArrayList<>();
        for (PronoteSession.Period period : targetPeriods) {
            log.info("Fetching competence evaluations for period: {}", period.getName());

            ObjectNode params = jackson.createObjectNode();
            // Note: pronotepy passes lowercase "periode" and includes G for DernieresEvaluations.
            params.set("periode", jackson.createObjectNode()
                    .put("N", period.getId())
                    .put("L", period.getName())
                    .put("G", period.getType()));

            JsonNode response = client.encryptedPost(session, ApiFunction.EVALUATIONS, params, 201);
            log.debug("Evaluations response for period '{}': {}", period.getName(), response);

            List<CompetenceEvaluation> periodEvals = parseEvaluations(response, period.getName());
            log.info("  → {} evaluation(s) in period '{}'", periodEvals.size(), period.getName());
            all.addAll(periodEvals);
        }

        log.info("Fetched {} competence evaluation(s) total across {} period(s)",
                all.size(), targetPeriods.size());
        return all;
    }

    private List<CompetenceEvaluation> parseEvaluations(JsonNode response, String periodName) {
        // Response: { "listeEvaluations": { "V": [ { evaluation object }, ... ] } }
        JsonNode wrapper = response.get("listeEvaluations");
        JsonNode list = null;
        if (wrapper != null) {
            list = wrapper.has("V") ? wrapper.get("V") : wrapper;
        }
        if (list == null || !list.isArray()) {
            log.debug("No 'listeEvaluations' array in response (period may have no evaluations yet)");
            if (response.isArray()) {
                list = response;
            } else {
                log.warn("Unexpected evaluations response structure for period '{}'. Raw: {}",
                        periodName, response);
                return Collections.emptyList();
            }
        }

        List<CompetenceEvaluation> evals = new ArrayList<>();
        for (JsonNode item : list) {
            JsonNode data = item.has("V") ? item.get("V") : item;
            try {
                CompetenceEvaluation e = mapEvaluation(data, periodName);
                if (e.getId() != null && !e.getId().isBlank()) {
                    evals.add(e);
                } else {
                    log.warn("Skipping evaluation with empty stable ID: {}", data);
                }
            } catch (Exception ex) {
                log.warn("Skipping malformed evaluation entry: {} — {}", data, ex.getMessage());
            }
        }
        return evals;
    }

    private CompetenceEvaluation mapEvaluation(JsonNode data, String periodName) {
        log.debug("Mapping evaluation entry: {}", data);
        CompetenceEvaluation e = new CompetenceEvaluation();

        // name: L (evaluation title set by teacher)
        e.setName(getString(data, "L", ""));

        // subject: matiere.V.L
        JsonNode matiere = data.get("matiere");
        if (matiere != null) {
            JsonNode matiereV = matiere.has("V") ? matiere.get("V") : matiere;
            e.setSubject(getString(matiereV, "L", ""));
        }
        if (e.getSubject() == null || e.getSubject().isBlank()) e.setSubject("");

        // date: date.V
        JsonNode dateNode = data.get("date");
        if (dateNode != null) {
            String dateStr = dateNode.has("V") ? dateNode.get("V").asText("") : dateNode.asText("");
            e.setDate(parseDate(dateStr));
        }

        // teacher: individu.V.L
        JsonNode individu = data.get("individu");
        if (individu != null) {
            JsonNode individuV = individu.has("V") ? individu.get("V") : individu;
            e.setTeacher(getString(individuV, "L", ""));
        }

        // description: descriptif
        e.setDescription(getString(data, "descriptif", ""));

        e.setPeriodName(periodName);

        // Stable ID: subject@date@name
        // The evaluation name (set by the teacher), date, and subject uniquely identify
        // a competence evaluation across sessions.
        String datePart = e.getDate() != null ? e.getDate().toString() : "unknown";
        e.setId(e.getSubject() + "@" + datePart + "@" + e.getName());

        // acquisitions: listeNiveauxDAcquisitions.V
        List<CompetenceAcquisition> acquisitions = new ArrayList<>();
        JsonNode niveaux = data.get("listeNiveauxDAcquisitions");
        if (niveaux != null) {
            JsonNode niveauxV = niveaux.has("V") ? niveaux.get("V") : niveaux;
            if (niveauxV != null && niveauxV.isArray()) {
                for (JsonNode acq : niveauxV) {
                    JsonNode acqData = acq.has("V") ? acq.get("V") : acq;
                    acquisitions.add(mapAcquisition(acqData));
                }
            }
        }
        e.setAcquisitions(acquisitions);

        return e;
    }

    private CompetenceAcquisition mapAcquisition(JsonNode data) {
        CompetenceAcquisition a = new CompetenceAcquisition();

        // level: L (achieved level label, e.g. "A Acquis", "En cours d'acquisition")
        a.setLevel(getString(data, "L", ""));

        // abbreviation: abbreviation (short form, e.g. "A", "CA")
        a.setAbbreviation(getString(data, "abbreviation", ""));

        // domain: domaine.V.L
        JsonNode domaine = data.get("domaine");
        if (domaine != null) {
            JsonNode domaineV = domaine.has("V") ? domaine.get("V") : domaine;
            a.setDomain(getString(domaineV, "L", ""));
        }

        // name: item.V.L (specific competence name)
        JsonNode item = data.get("item");
        if (item != null) {
            JsonNode itemV = item.has("V") ? item.get("V") : item;
            a.setName(getString(itemV, "L", ""));
        }

        // order: ordre
        a.setOrder(data.has("ordre") ? data.get("ordre").asInt(0) : 0);

        return a;
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
}
