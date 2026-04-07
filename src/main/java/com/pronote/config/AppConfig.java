package com.pronote.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Root configuration POJO. Populated by SnakeYAML from config.yaml.
 */
public class AppConfig {

    private PronoteConfig pronote = new PronoteConfig();
    private DataConfig data = new DataConfig();
    private SafetyConfig safety = new SafetyConfig();
    private NotificationsConfig notifications = new NotificationsConfig();
    private FeaturesConfig features = new FeaturesConfig();
    private SubjectEnrichmentConfig subjectEnrichment = new SubjectEnrichmentConfig();
    private TimetableViewConfig timetableView = new TimetableViewConfig();
    private AssignmentViewConfig assignmentView = new AssignmentViewConfig();

    // -------------------------------------------------------------------------
    // Getters / setters (plain, for SnakeYAML)
    // -------------------------------------------------------------------------

    public PronoteConfig getPronote() { return pronote; }
    public void setPronote(PronoteConfig pronote) { this.pronote = pronote; }

    public DataConfig getData() { return data; }
    public void setData(DataConfig data) { this.data = data; }

    public SafetyConfig getSafety() { return safety; }
    public void setSafety(SafetyConfig safety) { this.safety = safety; }

    public NotificationsConfig getNotifications() { return notifications; }
    public void setNotifications(NotificationsConfig notifications) { this.notifications = notifications; }

    public FeaturesConfig getFeatures() { return features; }
    public void setFeatures(FeaturesConfig features) { this.features = features; }

    public SubjectEnrichmentConfig getSubjectEnrichment() { return subjectEnrichment; }
    public void setSubjectEnrichment(SubjectEnrichmentConfig subjectEnrichment) { this.subjectEnrichment = subjectEnrichment; }

    public TimetableViewConfig getTimetableView() { return timetableView; }
    public void setTimetableView(TimetableViewConfig timetableView) { this.timetableView = timetableView; }

    public AssignmentViewConfig getAssignmentView() { return assignmentView; }
    public void setAssignmentView(AssignmentViewConfig assignmentView) { this.assignmentView = assignmentView; }

    // =========================================================================
    // Nested config classes
    // =========================================================================

    public static class PronoteConfig {
        private String baseUrl;
        private LoginMode loginMode = LoginMode.PARENT;
        private String username;
        private String password;
        private int weeksBefore = 0;
        private int weeksAhead = 2;
        /** Optional class group filter (e.g. "6C SIA G1"). Timetable entries belonging to a
         *  different group are excluded. Entries with no group are always included. */
        private String group;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public LoginMode getLoginMode() { return loginMode; }
        public void setLoginMode(LoginMode loginMode) { this.loginMode = loginMode; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public int getWeeksBefore() { return weeksBefore; }
        public void setWeeksBefore(int weeksBefore) { this.weeksBefore = weeksBefore; }

        public int getWeeksAhead() { return weeksAhead; }
        public void setWeeksAhead(int weeksAhead) { this.weeksAhead = weeksAhead; }

        public String getGroup() { return group; }
        public void setGroup(String group) { this.group = group; }
    }

    public enum LoginMode {
        PARENT, STUDENT
    }

    public static class DataConfig {
        private String directory = "./data";
        private int archiveRetainDays = 30;

        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }

        public int getArchiveRetainDays() { return archiveRetainDays; }
        public void setArchiveRetainDays(int archiveRetainDays) { this.archiveRetainDays = archiveRetainDays; }
    }

    public static class SafetyConfig {
        private long minDelayMs = 2000;
        private long jitterMs = 500;
        private int maxLoginFailures = 3;

        public long getMinDelayMs() { return minDelayMs; }
        public void setMinDelayMs(long minDelayMs) { this.minDelayMs = minDelayMs; }

        public long getJitterMs() { return jitterMs; }
        public void setJitterMs(long jitterMs) { this.jitterMs = jitterMs; }

        public int getMaxLoginFailures() { return maxLoginFailures; }
        public void setMaxLoginFailures(int maxLoginFailures) { this.maxLoginFailures = maxLoginFailures; }
    }

    public static class NotificationsConfig {
        private NtfyConfig ntfy = new NtfyConfig();
        private EmailConfig email = new EmailConfig();
        private ErrorAlertsConfig errorAlerts = new ErrorAlertsConfig();

        public NtfyConfig getNtfy() { return ntfy; }
        public void setNtfy(NtfyConfig ntfy) { this.ntfy = ntfy; }

        public EmailConfig getEmail() { return email; }
        public void setEmail(EmailConfig email) { this.email = email; }

        public ErrorAlertsConfig getErrorAlerts() { return errorAlerts; }
        public void setErrorAlerts(ErrorAlertsConfig errorAlerts) { this.errorAlerts = errorAlerts; }
    }

    /**
     * Controls whether pipeline errors (authentication failures, scraper errors, etc.)
     * are sent as ntfy push notifications. Uses the {@code notifications.ntfy} channel;
     * ntfy must also be enabled for alerts to be delivered.
     */
    public static class ErrorAlertsConfig {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /**
     * Feature flags — controls which data types are fetched, diffed, and notified.
     * Disabled types are completely skipped (no HTTP request, no snapshot update).
     * Re-enabling a type after it was disabled behaves like a first run for that type:
     * a baseline snapshot is saved silently with no notification.
     */
    public static class FeaturesConfig {
        private boolean assignments = true;
        private boolean timetable   = true;
        /** Grades (DernieresNotes). Disabled by default — requires academic periods in session. */
        private boolean grades      = false;
        private boolean evaluations = true;
        private boolean schoolLife  = true;

        public boolean isAssignments() { return assignments; }
        public void setAssignments(boolean assignments) { this.assignments = assignments; }

        public boolean isTimetable() { return timetable; }
        public void setTimetable(boolean timetable) { this.timetable = timetable; }

        public boolean isGrades() { return grades; }
        public void setGrades(boolean grades) { this.grades = grades; }

        public boolean isEvaluations() { return evaluations; }
        public void setEvaluations(boolean evaluations) { this.evaluations = evaluations; }

        public boolean isSchoolLife() { return schoolLife; }
        public void setSchoolLife(boolean schoolLife) { this.schoolLife = schoolLife; }
    }

    public static class NtfyConfig {
        private boolean enabled = false;
        private String serverUrl = "https://ntfy.sh";
        private String topic;
        private String token;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getServerUrl() { return serverUrl; }
        public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    public static class EmailConfig {
        private boolean enabled = false;
        private String smtpHost;
        private int smtpPort = 587;
        private String username;
        private String password;
        private String from;
        private String to;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getSmtpHost() { return smtpHost; }
        public void setSmtpHost(String smtpHost) { this.smtpHost = smtpHost; }

        public int getSmtpPort() { return smtpPort; }
        public void setSmtpPort(int smtpPort) { this.smtpPort = smtpPort; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }

        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }
    }

    /**
     * Top-level container for subject enrichment rules.
     *
     * <p>YAML example:
     * <pre>
     * subjectEnrichment:
     *   rules:
     *     - subject: "HISTOIRE-GEOGRAPHIE"
     *       teacher: "BUKOWIECKI J."
     *       enrichedSubject: "Histoire"
     *     - subject: "HISTOIRE-GEOGRAPHIE"
     *       teacher: "LATZKE W."
     *       enrichedSubject: "Géographie"
     *     - subject: "MATHEMATIQUES"
     *       enrichedSubject: "Maths"
     * </pre>
     *
     * Rules with both {@code subject} and {@code teacher} are matched first (most specific).
     * Rules with only {@code subject} act as a fallback for any teacher.
     * When no rule matches, {@code enrichedSubject} equals the original subject.
     */
    public static class SubjectEnrichmentConfig {
        private List<SubjectEnrichmentRule> rules = new ArrayList<>();

        public List<SubjectEnrichmentRule> getRules() { return rules; }
        public void setRules(List<SubjectEnrichmentRule> rules) {
            this.rules = rules != null ? rules : new ArrayList<>();
        }
    }

    /**
     * Configuration for generating static HTML timetable views.
     * When enabled, one self-contained HTML file is written per upcoming weekday
     * after each successful timetable fetch, plus an {@code index.html} overview.
     */
    public static class TimetableViewConfig {
        private boolean enabled = true;
        /** Output directory for generated HTML files. Relative paths are resolved from the JVM
         *  working directory. Point this to {@code docs/timetable} to use with GitHub Pages. */
        private String outputDirectory = "./data/views/timetable";
        /** Number of upcoming weekdays (Mon–Fri) for which to generate a page, starting today. */
        private int daysAhead = 5;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getOutputDirectory() { return outputDirectory; }
        public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; }

        public int getDaysAhead() { return daysAhead; }
        public void setDaysAhead(int daysAhead) { this.daysAhead = daysAhead; }
    }

    /**
     * Configuration for generating the static HTML assignment view.
     * When enabled, a single {@code index.html} is written listing all upcoming assignments
     * (dueDate &ge; today) grouped by date and subject, after each successful assignments fetch.
     */
    public static class AssignmentViewConfig {
        private boolean enabled = true;
        /** Output directory for the generated HTML file. Relative paths are resolved from the JVM
         *  working directory. */
        private String outputDirectory = "./data/views/assignments";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getOutputDirectory() { return outputDirectory; }
        public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; }
    }

    /** A single subject-enrichment mapping rule. {@code teacher} is optional. */
    public static class SubjectEnrichmentRule {
        /** Pronote subject string to match (exact, case-sensitive). */
        private String subject;
        /** Pronote teacher string to match (exact, case-sensitive). Null means "any teacher". */
        private String teacher;
        /** The enriched display name to use when this rule matches. */
        private String enrichedSubject;

        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }

        public String getTeacher() { return teacher; }
        public void setTeacher(String teacher) { this.teacher = teacher; }

        public String getEnrichedSubject() { return enrichedSubject; }
        public void setEnrichedSubject(String enrichedSubject) { this.enrichedSubject = enrichedSubject; }
    }
}
