package com.pronote.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pronote.auth.PronoteSession;
import com.pronote.client.ApiFunction;
import com.pronote.client.PronoteHttpClient;
import com.pronote.domain.SchoolLifeEvent;
import com.pronote.domain.SchoolLifeEvent.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

/**
 * Fetches vie scolaire (school life) events from Pronote using {@code PagePresence} (onglet 19).
 *
 * <p>One request is made per academic period. All event types are returned by the same
 * endpoint and distinguished by the {@code G} discriminator in the response:
 * <pre>
 *   G=13 → ABSENCE
 *   G=14 → DELAY  (retard)
 *   G=41 → PUNISHMENT (punition, retenue, exclusion)
 *   other → OTHER
 * </pre>
 *
 * <p>Captured data covers exactly what parents look for in the "cahier de correspondance"
 * section: tardiness, absences, punishments, and associated reasons such as
 * "Oubli de matériel" or "Travail non rendu" from the {@code listeMotifs} field.
 *
 * <p>Stable IDs:
 * <ul>
 *   <li>ABSENCE:    {@code ABSENCE@fromDate@toDate}</li>
 *   <li>DELAY:      {@code DELAY@date@minutes}</li>
 *   <li>PUNISHMENT: {@code PUNISHMENT@date@nature@giver}</li>
 *   <li>OTHER:      {@code OTHER@date@reasonsPrefix}</li>
 * </ul>
 */
public class SchoolLifeScraper {

    private static final Logger log = LoggerFactory.getLogger(SchoolLifeScraper.class);

    private static final DateTimeFormatter PRONOTE_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final DateTimeFormatter PRONOTE_DATE_SHORT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter PRONOTE_DATE_PARAM =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final ObjectMapper jackson = new ObjectMapper();

    /**
     * Fetches all vie scolaire events for every available academic period.
     *
     * @param session active, authenticated Pronote session
     * @param client  HTTP client
     * @param periods academic periods from the session
     * @return combined list of events across all periods (may be empty)
     */
    public List<SchoolLifeEvent> fetch(PronoteSession session, PronoteHttpClient client,
                                       List<PronoteSession.Period> periods) {
        if (periods == null || periods.isEmpty()) {
            log.warn("No academic periods in session — cannot fetch vie scolaire events. "
                    + "Delete session.json to trigger a fresh login and populate periods.");
            return Collections.emptyList();
        }

        List<SchoolLifeEvent> all = new ArrayList<>();
        for (PronoteSession.Period period : periods) {
            log.info("Fetching vie scolaire events for period: {}", period.getName());

            ObjectNode params = jackson.createObjectNode();
            params.set("periode", jackson.createObjectNode()
                    .put("N", period.getId())
                    .put("L", period.getName())
                    .put("G", period.getType()));

            // DateDebut / DateFin are required by PagePresence.
            // Use the stored period dates; fall back to school-year bounds if absent.
            LocalDate start = period.getStartDate();
            LocalDate end   = period.getEndDate();
            if (start == null) start = fallbackStart(session);
            if (end   == null) end   = LocalDate.now().plusDays(1);

            params.set("DateDebut", jackson.createObjectNode()
                    .put("_T", 7)
                    .put("V", start.atStartOfDay().format(PRONOTE_DATE_PARAM)));
            params.set("DateFin", jackson.createObjectNode()
                    .put("_T", 7)
                    .put("V", end.atStartOfDay().format(PRONOTE_DATE_PARAM)));

            JsonNode response = client.encryptedPost(session, ApiFunction.SCHOOL_LIFE, params, 19);
            log.debug("Vie scolaire response for period '{}': {}", period.getName(), response);

            List<SchoolLifeEvent> periodEvents = parseEvents(response, period.getName());
            log.info("  → {} vie scolaire event(s) in period '{}'", periodEvents.size(), period.getName());
            all.addAll(periodEvents);
        }

        log.info("Fetched {} vie scolaire event(s) total across {} period(s)",
                all.size(), periods.size());
        return all;
    }

    private static LocalDate fallbackStart(PronoteSession session) {
        // If period dates are missing, fall back to the school-year first Monday or Sep 1
        if (session.getSchoolYearFirstMonday() != null) return session.getSchoolYearFirstMonday();
        LocalDate now = LocalDate.now();
        int year = now.getMonthValue() >= 9 ? now.getYear() : now.getYear() - 1;
        return LocalDate.of(year, 9, 1);
    }

    private List<SchoolLifeEvent> parseEvents(JsonNode response, String periodName) {
        // Response: { "listeAbsences": { "V": [ { event object }, ... ] } }
        JsonNode wrapper = response.get("listeAbsences");
        JsonNode list = null;
        if (wrapper != null) {
            list = wrapper.has("V") ? wrapper.get("V") : wrapper;
        }
        if (list == null || !list.isArray()) {
            log.debug("No 'listeAbsences' array in vie scolaire response (period may have no events)");
            if (response.isArray()) {
                list = response;
            } else {
                log.warn("Unexpected vie scolaire response structure for period '{}'. Raw: {}",
                        periodName, response);
                return Collections.emptyList();
            }
        }

        List<SchoolLifeEvent> events = new ArrayList<>();
        for (JsonNode item : list) {
            JsonNode data = item.has("V") ? item.get("V") : item;
            try {
                SchoolLifeEvent event = mapEvent(data);
                if (event.getId() != null && !event.getId().isBlank()) {
                    events.add(event);
                } else {
                    log.warn("Skipping vie scolaire event with empty stable ID: {}", data);
                }
            } catch (Exception e) {
                log.warn("Skipping malformed vie scolaire entry: {} — {}", data, e.getMessage());
            }
        }
        return events;
    }

    private SchoolLifeEvent mapEvent(JsonNode data) {
        log.debug("Mapping vie scolaire entry: {}", data);
        SchoolLifeEvent event = new SchoolLifeEvent();

        // Determine event type from G discriminator
        int g = data.has("G") ? data.get("G").asInt(-1) : -1;
        EventType type = switch (g) {
            case 13 -> EventType.ABSENCE;
            case 14 -> EventType.DELAY;
            case 41 -> EventType.PUNISHMENT;
            default -> EventType.OTHER;
        };
        event.setType(type);

        // justified
        event.setJustified(getBoolean(data, "justifie", false));

        // reasons: listeMotifs.V[].L — joined with "; "
        String reasons = extractReasons(data);
        event.setReasons(reasons);

        // Date fields differ by event type
        if (type == EventType.ABSENCE) {
            // Absence: dateDebut.V / dateFin.V
            JsonNode dateDebut = data.get("dateDebut");
            if (dateDebut != null) {
                String ds = dateDebut.has("V") ? dateDebut.get("V").asText("") : dateDebut.asText("");
                event.setDate(parseDate(ds));
            }
            JsonNode dateFin = data.get("dateFin");
            if (dateFin != null) {
                String ds = dateFin.has("V") ? dateFin.get("V").asText("") : dateFin.asText("");
                event.setEndDate(parseDate(ds));
            }
            // minutes: NbrHeures (if present as decimal hours * 60, or NbrJours * school-day-hours)
            // Not critical — leave at 0 if absent

            // Stable ID: ABSENCE@fromDate@toDate
            String fromPart = event.getDate()    != null ? event.getDate().toString()    : "unknown";
            String toPart   = event.getEndDate() != null ? event.getEndDate().toString() : "unknown";
            event.setId("ABSENCE@" + fromPart + "@" + toPart);

        } else if (type == EventType.DELAY) {
            // Delay: date.V, duree (minutes)
            JsonNode dateNode = data.get("date");
            if (dateNode != null) {
                String ds = dateNode.has("V") ? dateNode.get("V").asText("") : dateNode.asText("");
                event.setDate(parseDate(ds));
            }
            event.setMinutes(getInt(data, "duree", 0));

            // Stable ID: DELAY@date@minutes
            String datePart = event.getDate() != null ? event.getDate().toString() : "unknown";
            event.setId("DELAY@" + datePart + "@" + event.getMinutes());

        } else if (type == EventType.PUNISHMENT) {
            // Punishment: dateDemande.V, nature.V.L, demandeur.V.L, circonstances, duree
            JsonNode dateDemande = data.get("dateDemande");
            if (dateDemande != null) {
                String ds = dateDemande.has("V") ? dateDemande.get("V").asText("") : dateDemande.asText("");
                event.setDate(parseDate(ds));
            }
            JsonNode nature = data.get("nature");
            if (nature != null) {
                JsonNode natureV = nature.has("V") ? nature.get("V") : nature;
                event.setNature(getString(natureV, "L", ""));
            }
            JsonNode demandeur = data.get("demandeur");
            if (demandeur != null) {
                JsonNode demandeurV = demandeur.has("V") ? demandeur.get("V") : demandeur;
                event.setGiver(getString(demandeurV, "L", ""));
            }
            event.setCircumstances(getString(data, "circonstances", ""));
            event.setMinutes(getInt(data, "duree", 0));

            // Stable ID: PUNISHMENT@date@nature@giver
            String datePart   = event.getDate()   != null ? event.getDate().toString()   : "unknown";
            String naturePart = event.getNature()  != null ? event.getNature()            : "";
            String giverPart  = event.getGiver()   != null ? event.getGiver()             : "";
            event.setId("PUNISHMENT@" + datePart + "@" + naturePart + "@" + giverPart);

        } else {
            // OTHER: try date field, fall back to dateDebut
            JsonNode dateNode = data.has("date") ? data.get("date") : data.get("dateDebut");
            if (dateNode != null) {
                String ds = dateNode.has("V") ? dateNode.get("V").asText("") : dateNode.asText("");
                event.setDate(parseDate(ds));
            }

            // Stable ID: OTHER@date@reasonsPrefix
            String datePart    = event.getDate() != null ? event.getDate().toString() : "unknown";
            String reasonsKey  = reasons != null && !reasons.isBlank()
                    ? reasons.substring(0, Math.min(30, reasons.length()))
                    : "G" + g;
            event.setId("OTHER@" + datePart + "@" + reasonsKey);
        }

        return event;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String extractReasons(JsonNode data) {
        JsonNode listeMotifs = data.get("listeMotifs");
        if (listeMotifs == null) return "";
        JsonNode items = listeMotifs.has("V") ? listeMotifs.get("V") : listeMotifs;
        if (items == null || !items.isArray() || items.isEmpty()) return "";
        StringJoiner sj = new StringJoiner("; ");
        for (JsonNode m : items) {
            JsonNode mData = m.has("V") ? m.get("V") : m;
            String label = getString(mData, "L", null);
            if (label != null && !label.isBlank()) sj.add(label);
        }
        return sj.toString();
    }

    private static String getString(JsonNode node, String field, String defaultValue) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? defaultValue : v.asText(defaultValue);
    }

    private static boolean getBoolean(JsonNode node, String field, boolean defaultValue) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? defaultValue : v.asBoolean(defaultValue);
    }

    private static int getInt(JsonNode node, String field, int defaultValue) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? defaultValue : v.asInt(defaultValue);
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
