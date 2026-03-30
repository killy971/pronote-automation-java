package com.pronote.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pronote.auth.PronoteSession;
import com.pronote.client.ApiFunction;
import com.pronote.client.PronoteHttpClient;
import com.pronote.config.AppConfig;
import com.pronote.config.SubjectEnricher;
import com.pronote.domain.EntryStatus;
import com.pronote.domain.TimetableEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Fetches the student timetable from Pronote using {@code PageEmploiDuTemps}.
 *
 * <p>Pronote's timetable API is week-based. One request per week is required.
 * The scraper iterates over the configured week range.
 *
 * <p>French field mapping:
 * <pre>
 *   N                           → id
 *   LibelleMatiereEnseignee     → subject
 *   Professeur (or ListeProfesseurs[0]) → teacher
 *   Salle (or ListeSalles[0])   → room
 *   DateDuCours.V               → startTime  ("DD/MM/YYYY HH:MM:SS")
 *   duree                       → duration in 15-min slots (endTime = startTime + duree*15min)
 *   estAnnule                   → CANCELLED
 *   estModifie                  → MODIFIED
 *   estExempte                  → EXEMPTED
 *   estDevoir                   → isTest
 * </pre>
 */
public class TimetableScraper {

    private static final Logger log = LoggerFactory.getLogger(TimetableScraper.class);

    private static final DateTimeFormatter PRONOTE_DATETIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final ObjectMapper jackson = new ObjectMapper();
    private final SubjectEnricher subjectEnricher;

    public TimetableScraper(SubjectEnricher subjectEnricher) {
        this.subjectEnricher = subjectEnricher;
    }

    /**
     * Fetches timetable entries for the configured week range.
     *
     * @param session active, authenticated session
     * @param client  HTTP client
     * @param config  application configuration
     * @return combined list of timetable entries across all fetched weeks
     */
    public List<TimetableEntry> fetch(PronoteSession session, PronoteHttpClient client, AppConfig config) {
        LocalDate today = LocalDate.now();
        LocalDate firstWeekStart = today.minusWeeks(config.getPronote().getWeeksBefore())
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate lastWeekStart = today.plusWeeks(config.getPronote().getWeeksAhead())
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        String group = config.getPronote().getGroup();
        if (group != null && !group.isBlank()) {
            log.info("Timetable group filter active: \"{}\"", group);
        }

        List<TimetableEntry> all = new ArrayList<>();
        LocalDate weekStart = firstWeekStart;

        while (!weekStart.isAfter(lastWeekStart)) {
            int weekNumber = schoolYearWeekNumber(weekStart, session);
            log.info("Fetching timetable for week {} (starting {})", weekNumber, weekStart);

            List<TimetableEntry> weekEntries = fetchWeek(session, client, weekStart, weekNumber, group);
            all.addAll(weekEntries);
            weekStart = weekStart.plusWeeks(1);
        }

        log.info("Fetched {} timetable entries total", all.size());
        return all;
    }

    private List<TimetableEntry> fetchWeek(PronoteSession session, PronoteHttpClient client,
                                            LocalDate weekStart, int weekNumber, String group) {
        ObjectNode params = jackson.createObjectNode();
        // Pronote identifies weeks by their Monday date and/or week number
        LocalDate weekEnd = weekStart.plusDays(6); // Sunday
        params.put("NumeroSemaine", weekNumber);
        params.put("DateDebut", jackson.createObjectNode()
                .put("_T", 7)
                .put("V", weekStart.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + " 0:0:0"));
        params.put("DateFin", jackson.createObjectNode()
                .put("_T", 7)
                .put("V", weekEnd.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + " 0:0:0"));
        // For parent accounts, specify whose timetable to fetch via the "ressource" parameter.
        // Without it, Pronote returns only a partial class-level view (whole-class entries only).
        if (session.getChildId() != null) {
            params.set("ressource", jackson.createObjectNode()
                    .put("N", session.getChildId())
                    .put("G", 4));
        }

        JsonNode response = client.encryptedPost(session, ApiFunction.PAGE_TIMETABLE, params, 16);
        return parseTimetable(response, group);
    }

    private List<TimetableEntry> parseTimetable(JsonNode response, String group) {
        // Response structure: { "ListeCours": [ { "V": {...} }, ... ] }
        // Pronote may wrap the array in a {"V": [...]} container (_T=24).
        JsonNode list = response.get("ListeCours");
        if (list != null && !list.isArray() && list.has("V")) {
            list = list.get("V");
        }
        if (list == null || !list.isArray()) {
            log.debug("No 'ListeCours' in response (week may be empty)");
            return Collections.emptyList();
        }

        log.debug("ListeCours: {} raw entries from API", list.size());
        List<TimetableEntry> entries = new ArrayList<>();
        int skippedGroup = 0;
        for (JsonNode item : list) {
            JsonNode data = item.has("V") ? item.get("V") : item;
            if (!matchesGroup(data, group)) {
                skippedGroup++;
                log.debug("Skipping entry — group does not match filter \"{}\"", group);
                continue;
            }
            try {
                entries.add(mapEntry(data));
            } catch (Exception e) {
                log.warn("Skipping malformed timetable entry: {} — {}", data, e.getMessage());
            }
        }
        if (skippedGroup > 0) {
            log.info("Group filter \"{}\" excluded {} of {} entries", group, skippedGroup, list.size());
        }
        return entries;
    }

    /**
     * Returns true if the entry belongs to the configured group (or no group filter is set).
     *
     * <p>Pronote entries with a G=2 item in ListeContenus are group-specific (e.g. "[6C SIA G1]").
     * Entries with no G=2 item apply to the whole class and are always included.
     * When a group is configured, only entries whose G=2 label contains the configured group
     * string (case-insensitive) are kept.
     */
    private static boolean matchesGroup(JsonNode data, String group) {
        if (group == null || group.isBlank()) return true;

        JsonNode listeContenus = data.get("ListeContenus");
        if (listeContenus == null) return true;
        JsonNode items = listeContenus.has("V") ? listeContenus.get("V") : listeContenus;
        if (items == null || !items.isArray()) return true;

        String groupLower = group.toLowerCase();
        for (JsonNode item : items) {
            if (item.has("G") && item.get("G").asInt(-1) == 2) {
                String label = item.has("L") ? item.get("L").asText("") : "";
                return label.toLowerCase().contains(groupLower);
            }
        }
        // No G=2 item → whole-class entry, always include
        return true;
    }

    private TimetableEntry mapEntry(JsonNode data) {
        TimetableEntry e = new TimetableEntry();

        // Start time (needed for the composite ID)
        JsonNode dateDuCours = data.get("DateDuCours");
        if (dateDuCours != null) {
            String dateStr = dateDuCours.has("V") ? dateDuCours.get("V").asText("") : dateDuCours.asText("");
            e.setStartTime(parseDatetime(dateStr));
        }

        // ID: N is session-specific and changes on every API call, so use content-based identity.
        // subject + startTime uniquely identifies a scheduled lesson across runs.
        // (populated after ListeContenus is parsed below — set at end of method)

        // All display fields (subject, teacher, room, group) live in ListeContenus.V
        // Each element has G (type discriminator) and L (display label):
        //   G=16 → subject, G=3 → teacher, G=17 → room, G=2 → class group
        JsonNode listeContenus = data.get("ListeContenus");
        if (listeContenus != null) {
            JsonNode items = listeContenus.has("V") ? listeContenus.get("V") : listeContenus;
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    int g = item.has("G") ? item.get("G").asInt(-1) : -1;
                    String label = getString(item, "L", "");
                    switch (g) {
                        case 16 -> e.setSubject(label);
                        case 3  -> e.setTeacher(label);
                        case 17 -> e.setRoom(label);
                        // G=2 is class group — not mapped to a dedicated field
                    }
                }
            }
        }

        if (e.getSubject() == null) e.setSubject("");
        if (e.getTeacher() == null) e.setTeacher("");
        if (e.getRoom()    == null) e.setRoom("");

        // End time: duree is in 15-minute slots (quarter-hours) for this Pronote instance.
        // A standard 1-hour period has duree=4 (4 × 15 min = 60 min).
        if (e.getStartTime() != null) {
            int duree = data.has("duree") ? data.get("duree").asInt(4) : 4;
            e.setEndTime(e.getStartTime().plusMinutes(duree * 15L));
        }

        // Status flags (absent = false/normal)
        boolean annule = getBoolean(data, "estAnnule", false);
        boolean modifie = getBoolean(data, "estModifie", false);
        boolean exempte = getBoolean(data, "estExempte", false);
        if (annule) e.setStatus(EntryStatus.CANCELLED);
        else if (exempte) e.setStatus(EntryStatus.EXEMPTED);
        else if (modifie) e.setStatus(EntryStatus.MODIFIED);
        else e.setStatus(EntryStatus.NORMAL);

        // Human-readable status label from Pronote (e.g. "Prof. absent", "Exceptionnel",
        // "Cours maintenu", "Cours modifié"). Present only when Pronote attaches extra context.
        String statut = getString(data, "Statut", null);
        if (statut != null && !statut.isBlank()) {
            e.setStatusLabel(statut);
        }

        // estDevoir is nested inside cahierDeTextes.V, not at the top level.
        // (pronotepy: self._resolver(bool, "cahierDeTextes", "V", "estDevoir", default=False))
        JsonNode cahierDeTextes = data.get("cahierDeTextes");
        if (cahierDeTextes != null && cahierDeTextes.has("V")) {
            e.setTest(getBoolean(cahierDeTextes.get("V"), "estDevoir", false));
        }

        // memo: free-text teacher annotation (e.g. "Évaluation de compétences")
        String memo = getString(data, "memo", null);
        if (memo != null && !memo.isBlank()) {
            e.setMemo(memo);
        }

        // Stable content-based ID: subject@startTime:status
        // Status is included so that a cancellation + replacement pair for the same slot
        // (which Pronote emits as two entries) produce distinct IDs rather than colliding.
        String startKey = e.getStartTime() != null ? e.getStartTime().toString() : "unknown";
        e.setId(e.getSubject() + "@" + startKey + ":" + e.getStatus().name());

        e.setEnrichedSubject(subjectEnricher.enrich(e.getSubject(), e.getTeacher()));

        return e;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String getString(JsonNode node, String field, String defaultValue) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? defaultValue : v.asText(defaultValue);
    }

    /**
     * Computes the Pronote school-year week number for a given Monday.
     *
     * <p>Pronote expects {@code NumeroSemaine} to be relative to the first Monday of the
     * school year (week 1 = the week that contains the first day of school in September),
     * not the ISO calendar week number. Using the ISO week number causes Pronote to return
     * only a subset of the weekly schedule.
     *
     * <p>Falls back to the ISO week number if the school-year first Monday is not available
     * in the session (e.g., when using a persisted session that predates this field).
     */
    private static int schoolYearWeekNumber(LocalDate weekStart, PronoteSession session) {
        if (session.getSchoolYearFirstMonday() != null) {
            return 1 + (int) ChronoUnit.WEEKS.between(session.getSchoolYearFirstMonday(), weekStart);
        }
        return weekStart.get(WeekFields.of(Locale.FRANCE).weekOfWeekBasedYear());
    }

    private static boolean getBoolean(JsonNode node, String field, boolean defaultValue) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? defaultValue : v.asBoolean(defaultValue);
    }

    private static LocalDateTime parseDatetime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s.trim(), PRONOTE_DATETIME);
        } catch (DateTimeParseException e) {
            log.debug("Could not parse Pronote datetime '{}': {}", s, e.getMessage());
            return null;
        }
    }
}
