package com.pronote.config;

import com.pronote.domain.Assignment;
import com.pronote.domain.CompetenceEvaluation;
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
 * Loads manually-defined assignments and competence evaluations from a YAML file.
 *
 * <p>Intended for entries you know about but that teachers have not yet published on Pronote.
 * Manual entries are merged into the fetched lists before diff and snapshot, so they
 * participate in change detection: adding an entry fires a "new" notification, removing one
 * fires a "removed" notification.
 *
 * <p>All IDs are prefixed with {@code manual:} to prevent collision with Pronote session IDs.
 *
 * <p>If the file does not exist it is silently ignored. A missing required field ({@code subject},
 * {@code description}/{@code name}, {@code dueDate}/{@code date}) causes a fast failure.
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

        List<CompetenceEvaluation> evaluations = new ArrayList<>();
        if (file.getEvaluations() != null) {
            for (EvaluationEntry entry : file.getEvaluations()) {
                evaluations.add(toEvaluation(entry, enricher));
            }
        }

        log.info("Loaded {} manual assignment(s) and {} manual evaluation(s)",
                assignments.size(), evaluations.size());
        return new ManualEntries(assignments, evaluations);
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

    private static CompetenceEvaluation toEvaluation(EvaluationEntry e, SubjectEnricher enricher) {
        requireField(e.getSubject(), "subject", "evaluation");
        requireField(e.getName(), "name", "evaluation");
        requireField(e.getDate(), "date", "evaluation");

        LocalDate date = parseDate(e.getDate(), "date");

        CompetenceEvaluation ev = new CompetenceEvaluation();
        ev.setId("manual:" + e.getSubject() + "@" + date + "@" + e.getName());
        ev.setSubject(e.getSubject());
        ev.setEnrichedSubject(enricher.enrich(e.getSubject(), e.getTeacher()));
        ev.setName(e.getName());
        ev.setDate(date);
        ev.setTeacher(e.getTeacher());
        ev.setDescription(e.getDescription());
        ev.setPeriodName(e.getPeriodName());
        return ev;
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
        private final List<CompetenceEvaluation> evaluations;

        ManualEntries(List<Assignment> assignments, List<CompetenceEvaluation> evaluations) {
            this.assignments = assignments;
            this.evaluations = evaluations;
        }

        public List<Assignment> getAssignments() { return assignments; }
        public List<CompetenceEvaluation> getEvaluations() { return evaluations; }
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
        private String name;
        private String date;
        private String teacher;      // optional — enables teacher-specific enrichment rules
        private String description;  // optional — shown in views/notifications
        private String periodName;   // optional — e.g. "Trimestre 3"

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
