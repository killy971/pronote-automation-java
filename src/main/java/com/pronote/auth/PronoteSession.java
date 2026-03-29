package com.pronote.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable state for an active Pronote session.
 *
 * <p>Contains the AES key/IV, the session handle (h), app id (a),
 * the request order counter, and cookies from the initial GET request.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PronoteSession {

    /** Session handle (Pronote "h" parameter) */
    private int sessionHandle;

    /** App identifier (Pronote "a" parameter, used in URL path) */
    private int appId;

    /** Current AES encryption key (16 bytes) */
    private byte[] aesKey;

    /** Current AES IV (16 bytes) */
    private byte[] aesIv;

    /** The temporary random IV generated during init (needed for iv derivation) */
    private byte[] aesIvTemp;

    /** Request order counter — starts at 1, incremented by 2 per request */
    private int orderCounter = 1;

    /** Cookies captured from the initial GET response */
    private Map<String, String> cookies = new HashMap<>();

    /** When this session was created */
    private Instant createdAt = Instant.now();

    /** The root URL of the Pronote instance this session belongs to */
    private String baseUrl;

    /** For parent accounts: the selected child's N identifier (null for student accounts) */
    private String childId;

    /**
     * The Monday of the first school-year week, parsed from FonctionParametres → General.PremierLundi.
     * Used to compute the correct Pronote school-year week number (NumeroSemaine) for timetable requests.
     * Null if not yet populated (older persisted sessions will fall back to ISO week number).
     */
    private LocalDate schoolYearFirstMonday;

    /**
     * Academic periods (trimesters/semesters) parsed from FonctionParametres → General.ListePeriodes.
     * Required by the grades (DernieresNotes) and evaluations (DernieresEvaluations) API calls.
     * Empty when using an older persisted session that predates this field.
     */
    private List<Period> periods = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Period inner class
    // -------------------------------------------------------------------------

    /**
     * An academic period (trimester or semester) as returned by Pronote's
     * FonctionParametres → General.ListePeriodes.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Period {
        private String id;        // N field
        private String name;      // L field (e.g. "Trimestre 1", "Semestre 2")
        private int type;         // G field (1=trimester, 2=semester, etc.)
        private LocalDate startDate; // dateDebut.V — used by PagePresence (vie scolaire)
        private LocalDate endDate;   // dateFin.V   — used by PagePresence (vie scolaire)

        public Period() {}

        public Period(String id, String name, int type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }

        public String getId()   { return id; }
        public void setId(String id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public int getType()    { return type; }
        public void setType(int type) { this.type = type; }

        public LocalDate getStartDate() { return startDate; }
        public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

        public LocalDate getEndDate() { return endDate; }
        public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

        @Override
        public String toString() { return "Period{id='" + id + "', name='" + name + "', type=" + type
                + ", start=" + startDate + ", end=" + endDate + '}'; }
    }

    public PronoteSession() {}

    public PronoteSession(String baseUrl) {
        this.baseUrl = baseUrl;
        this.aesKey = Arrays.copyOf(CryptoHelper.INITIAL_AES_KEY, 16);
        this.aesIv = Arrays.copyOf(CryptoHelper.INITIAL_AES_IV, 16);
        this.aesIvTemp = CryptoHelper.randomBytes(16);
    }

    /** Consumes the current order counter and advances it by 2. */
    public int nextOrder() {
        int current = orderCounter;
        orderCounter += 2;
        return current;
    }

    // -------------------------------------------------------------------------
    // Getters / setters (required for Jackson serialization)
    // -------------------------------------------------------------------------

    public int getSessionHandle() { return sessionHandle; }
    public void setSessionHandle(int sessionHandle) { this.sessionHandle = sessionHandle; }

    public int getAppId() { return appId; }
    public void setAppId(int appId) { this.appId = appId; }

    public byte[] getAesKey() { return aesKey; }
    public void setAesKey(byte[] aesKey) { this.aesKey = aesKey; }

    public byte[] getAesIv() { return aesIv; }
    public void setAesIv(byte[] aesIv) { this.aesIv = aesIv; }

    public byte[] getAesIvTemp() { return aesIvTemp; }
    public void setAesIvTemp(byte[] aesIvTemp) { this.aesIvTemp = aesIvTemp; }

    public int getOrderCounter() { return orderCounter; }
    public void setOrderCounter(int orderCounter) { this.orderCounter = orderCounter; }

    public Map<String, String> getCookies() { return cookies; }
    public void setCookies(Map<String, String> cookies) { this.cookies = cookies; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getChildId() { return childId; }
    public void setChildId(String childId) { this.childId = childId; }

    public LocalDate getSchoolYearFirstMonday() { return schoolYearFirstMonday; }
    public void setSchoolYearFirstMonday(LocalDate schoolYearFirstMonday) { this.schoolYearFirstMonday = schoolYearFirstMonday; }

    public List<Period> getPeriods() { return periods; }
    public void setPeriods(List<Period> periods) { this.periods = periods != null ? periods : new ArrayList<>(); }
}
