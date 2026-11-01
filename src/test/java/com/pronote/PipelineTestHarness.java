package com.pronote;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pronote.domain.Assignment;
import com.pronote.domain.CompetenceEvaluation;
import com.pronote.domain.EntryStatus;
import com.pronote.domain.Grade;
import com.pronote.domain.SchoolLifeEvent;
import com.pronote.domain.TimetableEntry;
import com.pronote.notification.NotificationPayload;
import com.pronote.notification.NotificationService;
import com.pronote.pipeline.FetchSource;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Shared environment builder for the end-to-end pipeline regression tests.
 *
 * <p>Each instance owns a fully isolated installation rooted in a JUnit {@code @TempDir}:
 * a generated {@code config.yaml} (synthetic credentials, notifications disabled, all paths
 * inside the temp dir), seedable snapshot files in the exact on-disk layout the application
 * uses, and the optional {@code manual-entries.yaml}. Tests drive the real CLI entry point
 * via {@link #runMain} — or, for {@code --mode fetch}, {@link #runFetch} with a
 * {@link FakeSource} standing in for Pronote — and assert on the files the pipeline produces.
 * They never call internal components directly, so they survive refactoring of the
 * implementation.
 *
 * <p>Conventions follow the project test rules: synthetic data only ({@code SYN_*} subjects,
 * {@code https://example.invalid/} URLs), no mocking framework, no network.
 *
 * <h2>Golden dump</h2>
 * When the {@code pipeline.golden.dir} system property is set (Gradle:
 * {@code -PgoldenDir=/some/dir}), {@link #dumpOutputs} copies every file the run produced into
 * {@code <dir>/<test name>/}, with the temp-dir prefix replaced by {@code <ROOT>}. Dumping on
 * two code versions and diffing the trees (after masking timestamps) proves a refactor changed
 * no output byte.
 */
final class PipelineTestHarness {

    final Path root;
    final Path dataDir;
    final Path configFile;
    final Path manualEntriesFile;
    final Path manualAttachmentDir;
    final Path portalViewDir;
    final Path timetableViewDir;
    final Path assignmentViewDir;
    final Path evaluationViewDir;
    final Path schoolLifeViewDir;

    // Config knobs — mutate before calling writeConfig().
    boolean includeCredentials = true;
    boolean featAssignments = true;
    boolean featTimetable = true;
    boolean featGrades = false;
    boolean featEvaluations = true;
    boolean featSchoolLife = true;
    boolean timetableViewEnabled = true;
    boolean assignmentViewEnabled = true;
    boolean evaluationViewEnabled = true;
    boolean schoolLifeViewEnabled = true;
    int weeksAhead = 2;
    int daysAhead = 5;
    int newBadgeDays = 2;
    /** Enrichment rules as {subject, teacherOrNull, enrichedSubject} rows. */
    final List<String[]> enrichmentRules = new ArrayList<>();
    /** subjectColors.overrides — raw subject to #rrggbb. */
    final Map<String, String> colorOverrides = new LinkedHashMap<>();
    /** subjectIcons.icons — when non-empty, subjectIcons.enabled is written as true. */
    final Map<String, String> iconOverrides = new LinkedHashMap<>();

    /** Same mapper construction as SnapshotStore, so seeded files match the real format. */
    private final ObjectMapper jackson = new ObjectMapper().registerModule(new JavaTimeModule());

    PipelineTestHarness(Path tempDir) {
        this.root = tempDir;
        this.dataDir = tempDir.resolve("data");
        this.configFile = tempDir.resolve("config.yaml");
        this.manualEntriesFile = tempDir.resolve("manual-entries.yaml");
        this.manualAttachmentDir = tempDir.resolve("manual-attachments");
        Path views = tempDir.resolve("views");
        this.portalViewDir = views;
        this.timetableViewDir = views.resolve("timetable");
        this.assignmentViewDir = views.resolve("assignments");
        this.evaluationViewDir = views.resolve("evaluations");
        this.schoolLifeViewDir = views.resolve("school-life");
    }

    /** The portal index the pipeline writes, under {@code portalView.outputDirectory}. */
    Path portalIndexFile() {
        return portalViewDir.resolve("index.html");
    }

    String readPortal() {
        return readFile(portalIndexFile());
    }

    // -------------------------------------------------------------------------
    // Config
    // -------------------------------------------------------------------------

    void writeConfig() {
        StringBuilder yaml = new StringBuilder();
        yaml.append("pronote:\n");
        if (includeCredentials) {
            yaml.append("  baseUrl: \"https://example.invalid/pronote/\"\n")
                .append("  username: \"syn-user\"\n")
                .append("  password: \"syn-pass\"\n");
        }
        yaml.append("  weeksAhead: ").append(weeksAhead).append("\n")
            .append("data:\n")
            .append("  directory: \"").append(dataDir).append("\"\n")
            .append("  archiveRetainDays: 30\n")
            .append("safety:\n")
            .append("  minDelayMs: 0\n")
            .append("  jitterMs: 0\n")
            .append("  maxLoginFailures: 3\n")
            .append("notifications:\n")
            .append("  ntfy:\n")
            .append("    enabled: false\n")
            .append("  email:\n")
            .append("    enabled: false\n")
            .append("  errorAlerts:\n")
            .append("    enabled: false\n")
            .append("features:\n")
            .append("  assignments: ").append(featAssignments).append("\n")
            .append("  timetable: ").append(featTimetable).append("\n")
            .append("  grades: ").append(featGrades).append("\n")
            .append("  evaluations: ").append(featEvaluations).append("\n")
            .append("  schoolLife: ").append(featSchoolLife).append("\n");

        yaml.append("subjectEnrichment:\n")
            .append("  teacherPrefixes:\n")
            .append("    - \"M.\"\n")
            .append("    - \"Mme\"\n");
        if (enrichmentRules.isEmpty()) {
            yaml.append("  rules: []\n");
        } else {
            yaml.append("  rules:\n");
            for (String[] rule : enrichmentRules) {
                yaml.append("    - subject: \"").append(rule[0]).append("\"\n");
                if (rule[1] != null) {
                    yaml.append("      teacher: \"").append(rule[1]).append("\"\n");
                }
                yaml.append("      enrichedSubject: \"").append(rule[2]).append("\"\n");
            }
        }

        yaml.append("subjectColors:\n")
            .append("  source: palette\n");
        if (!colorOverrides.isEmpty()) {
            yaml.append("  overrides:\n");
            colorOverrides.forEach((k, v) ->
                    yaml.append("    \"").append(k).append("\": \"").append(v).append("\"\n"));
        }
        yaml.append("subjectIcons:\n")
            .append("  enabled: ").append(!iconOverrides.isEmpty()).append("\n");
        if (!iconOverrides.isEmpty()) {
            yaml.append("  icons:\n");
            iconOverrides.forEach((k, v) ->
                    yaml.append("    \"").append(k).append("\": \"").append(v).append("\"\n"));
        }

        yaml.append("timetableView:\n")
            .append("  enabled: ").append(timetableViewEnabled).append("\n")
            .append("  outputDirectory: \"").append(timetableViewDir).append("\"\n")
            .append("  daysAhead: ").append(daysAhead).append("\n")
            .append("assignmentView:\n")
            .append("  enabled: ").append(assignmentViewEnabled).append("\n")
            .append("  outputDirectory: \"").append(assignmentViewDir).append("\"\n")
            .append("  newBadgeDays: ").append(newBadgeDays).append("\n")
            .append("evaluationView:\n")
            .append("  enabled: ").append(evaluationViewEnabled).append("\n")
            .append("  outputDirectory: \"").append(evaluationViewDir).append("\"\n")
            .append("schoolLifeView:\n")
            .append("  enabled: ").append(schoolLifeViewEnabled).append("\n")
            .append("  outputDirectory: \"").append(schoolLifeViewDir).append("\"\n")
            .append("portalView:\n")
            .append("  outputDirectory: \"").append(portalViewDir).append("\"\n")
            .append("viewPublish:\n")
            .append("  enabled: false\n")
            .append("manualEntries:\n")
            .append("  file: \"").append(manualEntriesFile).append("\"\n")
            .append("  attachmentDir: \"").append(manualAttachmentDir).append("\"\n");

        write(configFile, yaml.toString());
    }

    void writeManualEntries(String yamlBody) {
        write(manualEntriesFile, yamlBody);
    }

    /** Creates a file under {@code manualEntries.attachmentDir}, for manual attachment tests. */
    Path writeManualAttachment(String fileName, String content) {
        Path file = manualAttachmentDir.resolve(fileName);
        write(file, content);
        return file;
    }

    // -------------------------------------------------------------------------
    // Pipeline invocation
    // -------------------------------------------------------------------------

    /** Runs the real CLI pipeline with this harness's config plus the given extra args. */
    void runMain(String... extraArgs) {
        Main.run(args(extraArgs));
    }

    /**
     * Runs {@code --mode fetch} with {@code source} in place of Pronote. Everything after the
     * login — the manual-entry merge, the empty-fetch guard, diff and filters, the diff report,
     * notification, snapshot write, views and portal — is the production code path.
     */
    void runFetch(FakeSource source, NotificationService errorNotifier, String... extraArgs) {
        Main.runFetchWith(args(extraArgs), source, errorNotifier);
    }

    void runFetch(FakeSource source, String... extraArgs) {
        runFetch(source, null, extraArgs);
    }

    private String[] args(String... extraArgs) {
        List<String> args = new ArrayList<>(List.of("--config", configFile.toString()));
        args.addAll(List.of(extraArgs));
        return args.toArray(new String[0]);
    }

    // -------------------------------------------------------------------------
    // Snapshot seeding (mirrors SnapshotStore's on-disk layout)
    // -------------------------------------------------------------------------

    <T> void seedLatest(String type, List<T> items) {
        writeJson(dataDir.resolve("snapshots").resolve(type).resolve("latest.json"), items);
    }

    /** Seeds the archive entry that {@code --mode diff} treats as the "previous" snapshot. */
    <T> void seedArchive(String type, List<T> items) {
        writeJson(dataDir.resolve("snapshots").resolve(type)
                .resolve("archive").resolve("2020-01-01T00-00-00Z.json"), items);
    }

    Path latestSnapshot(String type) {
        return dataDir.resolve("snapshots").resolve(type).resolve("latest.json");
    }

    Path attachmentsDir() {
        return dataDir.resolve("snapshots").resolve("assignments").resolve("attachments");
    }

    long archiveCount(String type) {
        Path dir = dataDir.resolve("snapshots").resolve(type).resolve("archive");
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.list(dir)) {
            return s.count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeJson(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            jackson.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to seed " + file, e);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + file, e);
        }
    }

    // -------------------------------------------------------------------------
    // File readers for assertions
    // -------------------------------------------------------------------------

    String read(Path dir, String fileName) {
        return readFile(dir.resolve(fileName));
    }

    static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    String readDiffLatest() {
        return read(dataDir, "diff-latest.json");
    }

    String readDiffHistory() {
        return read(dataDir, "diff-history.log");
    }

    // -------------------------------------------------------------------------
    // Golden dump (see class javadoc)
    // -------------------------------------------------------------------------

    /** Copies the run's outputs to {@code ${pipeline.golden.dir}/<name>}; no-op when unset. */
    void dumpOutputs(String name, String extraText) {
        String goldenRoot = System.getProperty("pipeline.golden.dir");
        if (goldenRoot == null || goldenRoot.isBlank()) return;
        Path out = Path.of(goldenRoot).resolve(name);
        try {
            if (Files.isDirectory(root)) {
                try (Stream<Path> files = Files.walk(root)) {
                    for (Path f : files.filter(Files::isRegularFile).toList()) {
                        copyMasked(f, out.resolve("root").resolve(root.relativize(f).toString()));
                    }
                }
            }
            if (extraText != null) {
                // getBytes replaces malformed UTF-16 (a title cut mid-emoji) instead of throwing.
                Files.createDirectories(out);
                Files.write(out.resolve("extra.txt"), mask(extraText).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Golden dump failed for " + name, e);
        }
    }

    private void copyMasked(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        byte[] bytes = Files.readAllBytes(source);
        String text = new String(bytes, StandardCharsets.UTF_8);
        Files.write(target, mask(text).getBytes(StandardCharsets.UTF_8));
    }

    private String mask(String text) {
        // macOS temp dirs are reachable via /var and /private/var — mask both spellings.
        String r = root.toString();
        String real;
        try { real = root.toRealPath().toString(); } catch (IOException e) { real = r; }
        return text.replace(real, "<ROOT>").replace(r, "<ROOT>");
    }

    // -------------------------------------------------------------------------
    // Date helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the {@code n}-th upcoming weekday (Mon–Fri) counting from today inclusive —
     * the same sequence the timetable view renders pages for. {@code upcomingWeekday(0)}
     * is today on a weekday, next Monday on a weekend.
     */
    static LocalDate upcomingWeekday(int n) {
        LocalDate d = LocalDate.now();
        int found = 0;
        while (true) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                if (found == n) return d;
                found++;
            }
            d = d.plusDays(1);
        }
    }

    // -------------------------------------------------------------------------
    // Domain fixture builders (synthetic data only)
    // -------------------------------------------------------------------------

    static TimetableEntry lesson(String id, String subject, String teacher, String room,
                                 LocalDateTime start, LocalDateTime end) {
        TimetableEntry e = new TimetableEntry();
        e.setId(id);
        e.setSubject(subject);
        // Persisted snapshots always carry enrichedSubject (set at scrape time);
        // mirror that here — equal to subject when no enrichment rule matched.
        e.setEnrichedSubject(subject);
        e.setTeacher(teacher);
        e.setRoom(room);
        e.setStartTime(start);
        e.setEndTime(end);
        e.setStatus(EntryStatus.NORMAL);
        return e;
    }

    static TimetableEntry evalLesson(String id, String subject, String label,
                                     LocalDateTime start, LocalDateTime end) {
        TimetableEntry e = lesson(id, subject, null, null, start, end);
        e.setEval(true);
        e.setLessonLabel(label);
        return e;
    }

    static Assignment assignment(String id, String subject, String description,
                                 LocalDate dueDate, LocalDate assignedDate) {
        Assignment a = new Assignment();
        a.setId(id);
        a.setSubject(subject);
        a.setEnrichedSubject(subject);
        a.setDescription(description);
        a.setDueDate(dueDate);
        a.setAssignedDate(assignedDate);
        return a;
    }

    static Grade grade(String id, String subject, String value, double outOf, LocalDate date) {
        Grade g = new Grade();
        g.setId(id);
        g.setSubject(subject);
        g.setValue(value);
        g.setOutOf(outOf);
        g.setCoefficient(1.0);
        g.setDate(date);
        g.setPeriodName("Trimestre 3");
        return g;
    }

    static CompetenceEvaluation evaluationResult(String id, String subject, String name, LocalDate date) {
        CompetenceEvaluation e = new CompetenceEvaluation();
        e.setId(id);
        e.setSubject(subject);
        e.setName(name);
        e.setDate(date);
        e.setPeriodName("Trimestre 3");
        return e;
    }

    static SchoolLifeEvent schoolLifeEvent(String id, SchoolLifeEvent.EventType type,
                                           LocalDate date, String reasons) {
        SchoolLifeEvent e = new SchoolLifeEvent();
        e.setId(id);
        e.setType(type);
        e.setDate(date);
        e.setReasons(reasons);
        return e;
    }

    // -------------------------------------------------------------------------
    // Fake Pronote (for --mode fetch)
    // -------------------------------------------------------------------------

    /**
     * Canned scraper results. Records every call so tests can assert which types were fetched,
     * in which order, and that the attachment download and session save happened.
     * Setting {@link #failOn} makes that fetch throw, as a scraper failure would.
     */
    static final class FakeSource implements FetchSource {
        List<Assignment> assignments = new ArrayList<>();
        List<TimetableEntry> timetable = new ArrayList<>();
        List<Grade> grades = new ArrayList<>();
        List<CompetenceEvaluation> evaluations = new ArrayList<>();
        List<SchoolLifeEvent> schoolLife = new ArrayList<>();
        String failOn;
        final List<String> calls = new ArrayList<>();
        /** IDs of the assignments handed to the attachment download, manual entries included. */
        final List<String> downloadedFor = new ArrayList<>();

        private <T> List<T> fetch(String type, List<T> data) {
            calls.add(type);
            if (type.equals(failOn)) throw new RuntimeException("synthetic " + type + " failure");
            return new ArrayList<>(data);
        }

        @Override public List<Assignment> fetchAssignments() { return fetch("assignments", assignments); }
        @Override public List<TimetableEntry> fetchTimetable() { return fetch("timetable", timetable); }
        @Override public List<Grade> fetchGrades() { return fetch("grades", grades); }
        @Override public List<CompetenceEvaluation> fetchEvaluations() { return fetch("evaluations", evaluations); }
        @Override public List<SchoolLifeEvent> fetchSchoolLife() { return fetch("schoolLife", schoolLife); }

        @Override
        public void downloadAttachments(List<Assignment> list, Path attachmentsDir) {
            calls.add("downloadAttachments");
            list.forEach(a -> downloadedFor.add(a.getId()));
        }

        @Override
        public void saveSession() {
            calls.add("saveSession");
        }
    }

    /** An error notifier that records the alerts it is asked to deliver. */
    static final class RecordingNotifier implements NotificationService {
        final List<NotificationPayload> sent = new ArrayList<>();

        @Override
        public void send(NotificationPayload payload) {
            sent.add(payload);
        }
    }

    // -------------------------------------------------------------------------
    // Log capture (the dry-run notification preview is only observable via logs)
    // -------------------------------------------------------------------------

    static final class LogCapture implements AutoCloseable {
        private final ch.qos.logback.classic.Logger rootLogger;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        LogCapture() {
            rootLogger = (ch.qos.logback.classic.Logger)
                    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            appender.start();
            rootLogger.addAppender(appender);
        }

        List<String> messages() {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }

        boolean anyMessageContains(String fragment) {
            return messages().stream().anyMatch(m -> m.contains(fragment));
        }

        /** All captured messages joined with newlines — convenient for multi-line assertions. */
        String joined() {
            return String.join("\n", messages());
        }

        /** Just the dry-run notification preview block, for golden dumps (no logger names). */
        String preview() {
            return messages().stream().filter(m -> m.startsWith("┌") || m.startsWith("│") || m.startsWith("└"))
                    .reduce("", (a, b) -> a + b + "\n");
        }

        @Override
        public void close() {
            rootLogger.detachAppender(appender);
            appender.stop();
        }
    }
}
