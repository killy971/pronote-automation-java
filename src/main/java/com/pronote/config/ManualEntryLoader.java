package com.pronote.config;

import com.pronote.domain.Assignment;
import com.pronote.domain.EntryStatus;
import com.pronote.domain.TimetableEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads manually-declared assignments and upcoming evaluations from a YAML file.
 *
 * <p>Intended for work/tests you know about but that teachers have not yet published on Pronote.
 *
 * <h2>Terminology note — two distinct concepts named "evaluation"</h2>
 * <ul>
 *   <li><strong>Upcoming competence evaluation</strong> ({@code evaluations:} block in the YAML):
 *       a future timetable event where the teacher will assess competences. Represented as a
 *       synthetic {@link TimetableEntry} with {@code isEval=true}. Shows in the timetable day
 *       view, in the eval count on the timetable summary card, and in the upcoming-eval banner
 *       and date-group cards of the assignment view. Does NOT appear in the Bilan/Évaluations
 *       view (that view is for past results only).</li>
 *   <li><strong>Past competence evaluation result</strong> ({@link com.pronote.domain.CompetenceEvaluation}):
 *       a completed assessment fetched from Pronote via {@code DernieresEvaluations}, containing
 *       level scores (A/B/C/D/E) per competence item. Shown in the Bilan and Évaluations views.
 *       Manual entries never produce this type.</li>
 * </ul>
 *
 * <p>Manual entries are merged into the timetable list (evaluations) and assignments list before
 * diff and snapshot, so they participate in change detection: adding an entry fires a "new"
 * notification, removing one fires a "removed" notification.
 *
 * <p>All IDs are prefixed with {@code manual:} to prevent collision with Pronote session IDs.
 *
 * <p>If the file does not exist it is silently ignored. A missing required field causes a fast
 * failure with a descriptive error message.
 */
public class ManualEntryLoader {

    private static final Logger log = LoggerFactory.getLogger(ManualEntryLoader.class);

    private ManualEntryLoader() {}

    public static ManualEntries load(Path filePath, SubjectEnricher enricher) {
        if (!Files.exists(filePath)) {
            log.debug("No manual entries file at {} — skipping", filePath.toAbsolutePath());
            return new ManualEntries(List.of(), List.of());
        }

        log.info("Loading manual entries from {}", filePath.toAbsolutePath());
        EntriesFile file;
        try (InputStream is = Files.newInputStream(filePath)) {
            LoaderOptions opts = new LoaderOptions();
            Yaml yaml = new Yaml(new Constructor(EntriesFile.class, opts));
            file = yaml.load(is);
        } catch (IOException e) {
            throw new ConfigLoader.ConfigException(
                    "Failed to read manual entries file: " + e.getMessage(), e);
        }

        if (file == null) {
            return new ManualEntries(List.of(), List.of());
        }

        List<Assignment> assignments = new ArrayList<>();
        if (file.getAssignments() != null) {
            for (AssignmentEntry entry : file.getAssignments()) {
                assignments.add(toAssignment(entry, enricher));
            }
        }

        List<TimetableEntry> upcomingEvals = new ArrayList<>();
        if (file.getEvaluations() != null) {
            for (EvaluationEntry entry : file.getEvaluations()) {
                upcomingEvals.add(toTimetableEntry(entry, enricher));
            }
        }

        log.info("Loaded {} manual assignment(s) and {} manual upcoming evaluation(s)",
                assignments.size(), upcomingEvals.size());
        return new ManualEntries(assignments, upcomingEvals);
    }

    private static Assignment toAssignment(AssignmentEntry e, SubjectEnricher enricher) {
        requireField(e.getSubject(), "subject", "assignment");
        requireField(e.getDescription(), "description", "assignment");
        requireField(e.getDueDate(), "dueDate", "assignment");

        LocalDate dueDate = parseDate(e.getDueDate(), "dueDate");
        LocalDate assignedDate = (e.getAssignedDate() != null && !e.getAssignedDate().isBlank())
                ? parseDate(e.getAssignedDate(), "assignedDate")
                : dueDate;

        Assignment a = new Assignment();
        a.setId("manual:" + e.getSubject() + "@" + dueDate + "@" + e.getDescription());
        a.setSubject(e.getSubject());
        a.setEnrichedSubject(enricher.enrich(e.getSubject(), null));
        a.setDescription(e.getDescription());
        a.setDueDate(dueDate);
        a.setAssignedDate(assignedDate);
        a.setDone(false);
        return a;
    }

    /**
     * Converts a manual evaluation entry into a synthetic {@link TimetableEntry} with
     * {@code isEval=true}. This makes it flow through all timetable-based pipelines:
     * day view, summary eval count, and assignment view eval banner/cards.
     *
     * <p>{@code periodName} in the YAML is parsed but intentionally ignored — it has no
     * equivalent in the timetable domain and is only meaningful for past evaluation results.
     */
    private static TimetableEntry toTimetableEntry(EvaluationEntry e, SubjectEnricher enricher) {
        requireField(e.getSubject(), "subject", "evaluation");
        requireField(e.getName(), "name", "evaluation");
        requireField(e.getDate(), "date", "evaluation");

        LocalDate date = parseDate(e.getDate(), "date");

        TimetableEntry entry = new TimetableEntry();
        entry.setId("manual:" + e.getSubject() + "@" + date + "@" + e.getName());
        entry.setSubject(e.getSubject());
        entry.setEnrichedSubject(enricher.enrich(e.getSubject(), e.getTeacher()));
        entry.setTeacher(e.getTeacher());
        entry.setStartTime(date.atTime(8, 0));
        entry.setEndTime(date.atTime(9, 0));
        entry.setEval(true);
        entry.setLessonLabel(e.getName());
        entry.setMemo(e.getDescription());
        entry.setStatus(EntryStatus.NORMAL);
        return entry;
    }

    private static void requireField(String value, String field, String entryType) {
        if (value == null || value.isBlank()) {
            throw new ConfigLoader.ConfigException(
                    "Manual " + entryType + " entry is missing required field: " + field);
        }
    }

    private static LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (Exception ex) {
            throw new ConfigLoader.ConfigException(
                    "Manual entry field '" + field + "' is not a valid ISO date (YYYY-MM-DD): " + value);
        }
    }

    // -------------------------------------------------------------------------
    // Result holder
    // -------------------------------------------------------------------------

    public static class ManualEntries {
        private final List<Assignment> assignments;
        /** Synthetic timetable entries with {@code isEval=true} — not past evaluation results. */
        private final List<TimetableEntry> upcomingEvals;

        ManualEntries(List<Assignment> assignments, List<TimetableEntry> upcomingEvals) {
            this.assignments = assignments;
            this.upcomingEvals = upcomingEvals;
        }

        public List<Assignment> getAssignments() { return assignments; }
        public List<TimetableEntry> getUpcomingEvals() { return upcomingEvals; }
    }

    // -------------------------------------------------------------------------
    // SnakeYAML POJOs — flat beans, String dates (SnakeYAML doesn't handle LocalDate natively)
    // -------------------------------------------------------------------------

    public static class EntriesFile {
        private List<AssignmentEntry> assignments;
        private List<EvaluationEntry> evaluations;

        public List<AssignmentEntry> getAssignments() { return assignments; }
        public void setAssignments(List<AssignmentEntry> assignments) { this.assignments = assignments; }

        public List<EvaluationEntry> getEvaluations() { return evaluations; }
        public void setEvaluations(List<EvaluationEntry> evaluations) { this.evaluations = evaluations; }
    }

    public static class AssignmentEntry {
        private String subject;
        private String description;
        private String dueDate;
        private String assignedDate;  // optional — defaults to dueDate if absent

        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getDueDate() { return dueDate; }
        public void setDueDate(String dueDate) { this.dueDate = dueDate; }

        public String getAssignedDate() { return assignedDate; }
        public void setAssignedDate(String assignedDate) { this.assignedDate = assignedDate; }
    }

    public static class EvaluationEntry {
        private String subject;
        private String name;           // required — the evaluation title; shown as lessonLabel in timetable
        private String date;           // required — ISO date YYYY-MM-DD
        private String teacher;        // optional — enables teacher-specific enrichment rules
        private String description;    // optional — shown as memo in timetable day view
        private String periodName;     // optional — parsed but not used (no timetable equivalent)

        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }

        public String getTeacher() { return teacher; }
        public void setTeacher(String teacher) { this.teacher = teacher; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getPeriodName() { return periodName; }
        public void setPeriodName(String periodName) { this.periodName = periodName; }
    }
}
