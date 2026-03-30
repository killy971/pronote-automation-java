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
import java.time.LocalTime;
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
 *   G=13 → ABSENCE      (absence)
 *   G=14 → DELAY        (retard)
 *   G=21 → INFIRMARY    (passage infirmerie — actesMedicaux + symptomesMedicaux)
 *   G=41 → PUNISHMENT   (punition, retenue, exclusion)
 *   G=46 → OBSERVATION  (observation, travail non fait, oubli de matériel — with teacher + subject)
 *   other → OTHER
 * </pre>
 *
 * <p>Captured data covers what parents see in the "vie scolaire" section:
 * absences, tardiness, punishments, infirmary visits, and teacher observations
 * (e.g., "Travail non fait", "Oubli de matériel").
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

        // Prefer the whole-year period to avoid redundant requests and overlapping data.
        // Fall back to all periods if no year-spanning period is found.
        List<PronoteSession.Period> targetPeriods = periods.stream()
                .filter(p -> p.getName() != null && p.getName().toLowerCase().contains("année"))
                .collect(java.util.stream.Collectors.toList());
        if (targetPeriods.isEmpty()) {
            log.debug("No whole-year period found — using all {} period(s)", periods.size());
            targetPeriods = periods;
        } else {
            log.info("Using whole-year period '{}' for vie scolaire (skipping {} other period(s))",
                    targetPeriods.get(0).getName(), periods.size() - targetPeriods.size());
        }

        List<SchoolLifeEvent> all = new ArrayList<>();
        for (PronoteSession.Period period : targetPeriods) {
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
                all.size(), targetPeriods.size());
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
            case 21 -> EventType.INFIRMARY;
            case 41 -> EventType.PUNISHMENT;
            case 46 -> EventType.OBSERVATION;
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

        } else if (type == EventType.INFIRMARY) {
            // Infirmary visit: dateDebut.V / dateFin.V with time, actesMedicaux, symptomesMedicaux
            JsonNode dateDebut = data.get("dateDebut");
            String fromDs = "";
            if (dateDebut != null) {
                fromDs = dateDebut.has("V") ? dateDebut.get("V").asText("") : dateDebut.asText("");
                event.setDate(parseDate(fromDs));
                event.setTime(parseTime(fromDs));
            }
            JsonNode dateFin = data.get("dateFin");
            String toTime = "";
            if (dateFin != null) {
                String toDs = dateFin.has("V") ? dateFin.get("V").asText("") : dateFin.asText("");
                event.setEndDate(parseDate(toDs));
                toTime = parseTime(toDs) != null ? parseTime(toDs) : "";
            }
            // reasons: actesMedicaux.V[].L (medical acts performed)
            event.setReasons(extractLabelList(data, "actesMedicaux"));
            // circumstances: symptomesMedicaux.V[].L (symptoms)
            event.setCircumstances(extractLabelList(data, "symptomesMedicaux"));

            // Stable ID: INFIRMARY@fromDate@fromTime@toTime
            String datePart = event.getDate() != null ? event.getDate().toString() : "unknown";
            String fromTime = event.getTime() != null ? event.getTime() : "00:00";
            event.setId("INFIRMARY@" + datePart + "@" + fromTime + "@" + toTime);

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

        } else if (type == EventType.OBSERVATION) {
            // Observation: L (label), date.V with time, demandeur.V.L, matiere.V.L, commentaire
            JsonNode dateNode = data.get("date");
            String dateStr = "";
            if (dateNode != null) {
                dateStr = dateNode.has("V") ? dateNode.get("V").asText("") : dateNode.asText("");
                event.setDate(parseDate(dateStr));
                event.setTime(parseTime(dateStr));
            }
            // L field is the observation label (e.g. "Travail non fait", "Oubli de matériel")
            event.setLabel(getString(data, "L", ""));
            // Teacher who issued the observation
            JsonNode demandeur = data.get("demandeur");
            if (demandeur != null) {
                JsonNode demandeurV = demandeur.has("V") ? demandeur.get("V") : demandeur;
                event.setGiver(getString(demandeurV, "L", ""));
            }
            // Subject
            JsonNode matiere = data.get("matiere");
            if (matiere != null) {
                JsonNode matiereV = matiere.has("V") ? matiere.get("V") : matiere;
                event.setSubject(getString(matiereV, "L", ""));
            }
            // Optional free-text comment
            event.setCircumstances(getString(data, "commentaire", ""));

            // Stable ID: OBSERVATION@date@time@label@subject
            String datePart    = event.getDate()    != null ? event.getDate().toString() : "unknown";
            String timePart    = event.getTime()    != null ? event.getTime()            : "00:00";
            String labelPart   = event.getLabel()   != null ? event.getLabel()           : "";
            String subjectPart = event.getSubject() != null ? event.getSubject()         : "";
            event.setId("OBSERVATION@" + datePart + "@" + timePart + "@" + labelPart + "@" + subjectPart);

        } else {
            // OTHER: unknown G value — best-effort date extraction
            JsonNode dateNode = data.has("date") ? data.get("date") : data.get("dateDebut");
            if (dateNode != null) {
                String ds = dateNode.has("V") ? dateNode.get("V").asText("") : dateNode.asText("");
                event.setDate(parseDate(ds));
                event.setTime(parseTime(ds));
            }

            // Stable ID: OTHER@date@G<n>
            String datePart = event.getDate() != null ? event.getDate().toString() : "unknown";
            event.setId("OTHER@" + datePart + "@G" + g);
        }

        return event;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Extracts the time portion (HH:mm) from a Pronote datetime string like "DD/MM/YYYY HH:MM:SS".
     * Returns null if the string has no time component or cannot be parsed.
     */
    private static String parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        // Format: "19/09/2025 13:00:00"
        int spaceIdx = s.indexOf(' ');
        if (spaceIdx < 0) return null;
        String timePart = s.substring(spaceIdx + 1).trim(); // "13:00:00"
        try {
            LocalTime t = LocalTime.parse(timePart);
            return String.format("%02d:%02d", t.getHour(), t.getMinute());
        } catch (DateTimeParseException e) {
            log.debug("Could not parse time from '{}': {}", s, e.getMessage());
            return null;
        }
    }

    /**
     * Extracts labels from a nested list field like {@code actesMedicaux.V[].L},
     * joining them with "; ". Returns empty string if absent or empty.
     */
    private static String extractLabelList(JsonNode data, String fieldName) {
        JsonNode field = data.get(fieldName);
        if (field == null) return "";
        JsonNode items = field.has("V") ? field.get("V") : field;
        if (items == null || !items.isArray() || items.isEmpty()) return "";
        StringJoiner sj = new StringJoiner("; ");
        for (JsonNode item : items) {
            String label = getString(item, "L", null);
            if (label != null && !label.isBlank()) sj.add(label.trim());
        }
        return sj.toString();
    }

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
