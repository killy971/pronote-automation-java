package com.pronote.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pronote.auth.CryptoHelper;
import com.pronote.auth.PronoteSession;
import com.pronote.client.ApiFunction;
import com.pronote.client.PronoteHttpClient;
import com.pronote.config.AppConfig;
import com.pronote.config.SubjectEnricher;
import com.pronote.domain.Assignment;
import com.pronote.domain.AttachmentRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.commons.text.StringEscapeUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
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
 * Fetches homework assignments from Pronote using the {@code ListeTravailAFaire} API function.
 *
 * <p>The date range covers [current week - weeksBefore, current week + weeksAhead].
 * Pronote returns assignments due within the queried period.
 *
 * <p>French field mapping:
 * <pre>
 *   Matiere.V.L            → subject
 *   descriptif.V           → description (HTML stripped)
 *   TAFFait                → done
 *   PourLe.V               → dueDate   (format: "DD/MM/YYYY HH:MM:SS")
 *   DonneLe.V              → assignedDate
 *   ListePieceJointe.V[]     → attachmentUrls (G=0 link: url field; G=1 file: AES-derived URL)
 * </pre>
 * ID is content-derived: {@code subject@dueDate@assignedDate}.
 */
public class AssignmentScraper {

    private static final Logger log = LoggerFactory.getLogger(AssignmentScraper.class);

    // Pronote date format in assignment responses
    private static final DateTimeFormatter PRONOTE_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final DateTimeFormatter PRONOTE_DATE_SHORT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ObjectMapper jackson = new ObjectMapper();
    private final SubjectEnricher subjectEnricher;

    public AssignmentScraper(SubjectEnricher subjectEnricher) {
        this.subjectEnricher = subjectEnricher;
    }

    /**
     * Fetches all assignments for the configured date range.
     *
     * @param session active, authenticated Pronote session
     * @param client  HTTP client
     * @param config  application config (weeksBefore, weeksAhead)
     * @return list of assignments (may be empty if none found)
     */
    public List<Assignment> fetch(PronoteSession session, PronoteHttpClient client, AppConfig config) {
        LocalDate today = LocalDate.now();
        LocalDate rangeStart = today.minusWeeks(config.getPronote().getWeeksBefore())
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate rangeEnd = today.plusWeeks(config.getPronote().getWeeksAhead())
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        int weekFrom = schoolYearWeekNumber(rangeStart, session);
        int weekTo   = schoolYearWeekNumber(rangeEnd, session);
        log.info("Fetching assignments for school-year weeks {} to {} ({} to {})",
                weekFrom, weekTo, rangeStart, rangeEnd);

        // PageCahierDeTexte (onglet 88) expects a "domaine" week-range, not DateDebut/DateFin.
        // Format: {"_T": 8, "V": "[weekFrom..weekTo]"}  (pronotepy convention)
        ObjectNode params = jackson.createObjectNode();
        params.set("domaine", jackson.createObjectNode()
                .put("_T", 8)
                .put("V", "[" + weekFrom + ".." + weekTo + "]"));

        JsonNode response = client.encryptedPost(session, ApiFunction.LIST_HOMEWORK, params, 88);
        log.debug("Assignment response received, parsing...");

        return parseAssignments(response, session);
    }

    private static int schoolYearWeekNumber(LocalDate weekStart, PronoteSession session) {
        if (session.getSchoolYearFirstMonday() != null) {
            return 1 + (int) ChronoUnit.WEEKS.between(session.getSchoolYearFirstMonday(), weekStart);
        }
        return weekStart.get(WeekFields.of(Locale.FRANCE).weekOfWeekBasedYear());
    }

    private List<Assignment> parseAssignments(JsonNode response, PronoteSession session) {
        // Response structure: { "ListeTravauxAFaire": { "V": [ { "V": { ...assignment fields... } }, ... ] } }
        JsonNode wrapper = response.get("ListeTravauxAFaire");
        JsonNode list = null;
        if (wrapper != null) {
            list = wrapper.has("V") ? wrapper.get("V") : wrapper;
        }
        if (list == null || !list.isArray()) {
            log.debug("No 'ListeTravauxAFaire' array in response (may be empty or different structure)");
            // Try direct array
            if (response.isArray()) {
                list = response;
            } else {
                log.warn("Unexpected assignment response structure. Raw: {}", response);
                return Collections.emptyList();
            }
        }

        List<Assignment> assignments = new ArrayList<>();
        for (JsonNode item : list) {
            // Items may be wrapped in {"V": {...}} or be the object directly
            JsonNode data = item.has("V") ? item.get("V") : item;
            try {
                assignments.add(mapAssignment(data, session));
            } catch (Exception e) {
                log.warn("Skipping malformed assignment entry: {} — {}", data, e.getMessage());
            }
        }

        log.info("Parsed {} assignments", assignments.size());
        return assignments;
    }

    private Assignment mapAssignment(JsonNode data, PronoteSession session) {
        log.debug("Mapping assignment entry: {}", data);
        Assignment a = new Assignment();

        // subject: Matiere.V.L (always wrapped in a V object)
        JsonNode matiere = data.get("Matiere");
        if (matiere != null) {
            JsonNode matiereV = matiere.has("V") ? matiere.get("V") : matiere;
            a.setSubject(getString(matiereV, "L", ""));
        }

        // dueDate: PourLe.V
        JsonNode pourLe = data.get("PourLe");
        if (pourLe != null) {
            String dateStr = pourLe.has("V") ? pourLe.get("V").asText("") : pourLe.asText("");
            a.setDueDate(parsePronotDate(dateStr));
        }

        // assignedDate: DonneLe.V
        JsonNode donneLe = data.get("DonneLe");
        if (donneLe != null) {
            String dateStr = donneLe.has("V") ? donneLe.get("V").asText("") : donneLe.asText("");
            a.setAssignedDate(parsePronotDate(dateStr));
        }

        // Stable content-based ID: subject@dueDate@assignedDate.
        // Pronote's N field is session-scoped for homework entries, so we derive identity
        // from content. subject+dueDate+assignedDate is unique in practice: the same subject
        // will rarely have two assignments for the same due date assigned on the exact same day.
        String duePart      = a.getDueDate()      != null ? a.getDueDate().toString()      : "unknown";
        String assignedPart = a.getAssignedDate() != null ? a.getAssignedDate().toString() : "unknown";
        a.setId((a.getSubject() != null ? a.getSubject() : "") + "@" + duePart + "@" + assignedPart);

        // Assignments carry no teacher in the Pronote API response.
        // Apply subject-only rules here; Main.java re-enriches with teacher resolved from timetable.
        a.setEnrichedSubject(subjectEnricher.enrich(a.getSubject(), null));

        // description: descriptif.V (rich-text object; fall back to plain string)
        JsonNode descriptif = data.get("descriptif");
        if (descriptif != null) {
            String raw = descriptif.has("V") ? descriptif.get("V").asText("") : descriptif.asText("");
            a.setDescription(stripHtml(raw));
        }

        // done: TAFFait
        a.setDone(getBoolean(data, "TAFFait", false));

        // attachments: ListePieceJointe.V
        //   G=0 → hyperlink: url field is the destination URL; not downloaded locally
        //   G=1 → uploaded file: URL constructed via AES encryption; downloaded locally
        //   Both types carry N (stable Pronote ID) and L (filename/label).
        List<AttachmentRef> attachments = new ArrayList<>();
        JsonNode pieceJointe = data.get("ListePieceJointe");
        if (pieceJointe != null) {
            JsonNode items = pieceJointe.has("V") ? pieceJointe.get("V") : pieceJointe;
            if (items != null && items.isArray()) {
                for (JsonNode p : items) {
                    int g = p.has("G") ? p.get("G").asInt(-1) : -1;
                    if (g == 0) {
                        // Hyperlink: url field is ready to use; not a downloadable file.
                        // stableId = the URL itself (externally hosted, stable across sessions).
                        String url   = getString(p, "url", null);
                        String label = getString(p, "L", null);
                        if (url != null && !url.isBlank()) {
                            AttachmentRef ref = new AttachmentRef();
                            ref.setStableId(url);
                            ref.setFileName(label != null ? label : "link");
                            ref.setUrl(url);
                            ref.setUploadedFile(false);
                            attachments.add(ref);
                        }
                    } else if (g == 1) {
                        // Uploaded file: build authenticated download URL.
                        // NOTE: The Pronote N field is intentionally NOT used as stableId —
                        // it contains a session-scoped token after '#' that changes on every login.
                        // stableId = assignmentId + "|" + fileName, both of which are stable.
                        // The download URL is session-scoped: stored in the transient downloadUrl
                        // field (never persisted to snapshot, not compared by DiffEngine).
                        String fileId   = getString(p, "N", null);
                        String fileName = getString(p, "L", null);
                        if (fileId != null && fileName != null) {
                            String fileUrl = buildFileUrl(session, fileId, fileName);
                            if (fileUrl != null) {
                                AttachmentRef ref = new AttachmentRef();
                                ref.setStableId(a.getId() + "|" + fileName);
                                ref.setFileName(fileName);
                                ref.setDownloadUrl(fileUrl); // transient, never persisted
                                ref.setUploadedFile(true);
                                attachments.add(ref);
                            }
                        }
                    } else {
                        log.debug("Unknown attachment type G={} on entry, skipping", g);
                    }
                }
            }
        }
        a.setAttachments(attachments);

        return a;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Constructs the authenticated download URL for a G=1 (uploaded file) attachment.
     *
     * <p>Mirrors pronotepy's Attachment URL derivation:
     * <ol>
     *   <li>Serialize {@code {"N":"<id>","Actif":true}} (no spaces, lowercase boolean)</li>
     *   <li>AES-CBC-PKCS7 encrypt with the session key/IV → lowercase hex</li>
     *   <li>Append to base URL: {@code FichiersExternes/<hex>/<encoded-name>?Session=<h>}</li>
     * </ol>
     */
    private static String buildFileUrl(PronoteSession session, String fileId, String fileName) {
        try {
            String json = "{\"N\":\"" + fileId + "\",\"Actif\":true}";
            byte[] encrypted = CryptoHelper.aesEncrypt(
                    json.getBytes(StandardCharsets.UTF_8),
                    session.getAesKey(), session.getAesIv());
            String hex = CryptoHelper.toHex(encrypted);
            // Use %20 for spaces (matches Python quote()); keep . and * unencoded as URLEncoder does
            String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                    .replace("+", "%20");
            return session.getBaseUrl() + "FichiersExternes/" + hex
                    + "/" + encodedName + "?Session=" + session.getSessionHandle();
        } catch (Exception e) {
            log.warn("Could not build file URL for attachment '{}' (N={}): {}", fileName, fileId, e.getMessage());
            return null;
        }
    }

    private static String getString(JsonNode node, String field, String defaultValue) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? defaultValue : v.asText(defaultValue);
    }

    private static boolean getBoolean(JsonNode node, String field, boolean defaultValue) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? defaultValue : v.asBoolean(defaultValue);
    }

    private static LocalDate parsePronotDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            // Try full datetime format first
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

    /** Strips HTML tags from description text and decodes HTML entities. */
    private static String stripHtml(String html) {
        if (html == null) return "";
        return StringEscapeUtils.unescapeHtml4(html.replaceAll("<[^>]+>", "").trim());
    }
}
