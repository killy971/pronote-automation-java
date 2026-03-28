package com.pronote.config;

/**
 * Root configuration POJO. Populated by SnakeYAML from config.yaml.
 */
public class AppConfig {

    private PronoteConfig pronote = new PronoteConfig();
    private DataConfig data = new DataConfig();
    private SafetyConfig safety = new SafetyConfig();
    private NotificationsConfig notifications = new NotificationsConfig();

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

        public NtfyConfig getNtfy() { return ntfy; }
        public void setNtfy(NtfyConfig ntfy) { this.ntfy = ntfy; }

        public EmailConfig getEmail() { return email; }
        public void setEmail(EmailConfig email) { this.email = email; }
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
}
