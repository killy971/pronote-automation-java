package com.pronote.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pronote.domain.Assignment;
import com.pronote.domain.CompetenceEvaluation;
import com.pronote.domain.Grade;
import com.pronote.domain.SchoolLifeEvent;
import com.pronote.domain.TimetableEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Writes a persistent trace of every diff run to disk, regardless of whether
 * notifications are enabled.
 *
 * <p>Two files are maintained under {@code dataDir}:
 * <ul>
 *   <li>{@code diff-latest.json} — full structured report of the most recent run,
 *       overwritten on every run. Useful for manual inspection.</li>
 *   <li>{@code diff-history.log} — append-only one-liner per run.
 *       Never deleted automatically; provides a permanent audit trail.</li>
 * </ul>
 *
 * <p>On the first run (no previous snapshot exists), both files are written with
 * {@code "firstRun": true} so the baseline establishment is clearly recorded.
 */
public class DiffReporter {

    private static final Logger log = LoggerFactory.getLogger(DiffReporter.class);

    private final Path dataDir;
    private final ObjectMapper jackson;

    public DiffReporter(Path dataDir) {
        this.dataDir = dataDir;
        this.jackson = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Records the outcome of a completed diff run.
     *
     * @param assignmentDiff   diff result for assignments
     * @param timetableDiff    diff result for timetable
     * @param gradeDiff        diff result for grades
     * @param evaluationDiff   diff result for competence evaluations
     * @param correspondenceDiff diff result for correspondence messages
     * @param isFirstRun       true when no previous snapshot existed — baseline establishment
     */
    public void record(DiffResult<Assignment> assignmentDiff,
                       DiffResult<TimetableEntry> timetableDiff,
                       DiffResult<Grade> gradeDiff,
                       DiffResult<CompetenceEvaluation> evaluationDiff,
                       DiffResult<SchoolLifeEvent> schoolLifeDiff,
                       boolean isFirstRun) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            log.warn("Could not create data directory for diff report: {}", e.getMessage());
            return;
        }

        Instant now = Instant.now();
        boolean hasChanges = !isFirstRun && (!assignmentDiff.isEmpty() || !timetableDiff.isEmpty()
                || !gradeDiff.isEmpty() || !evaluationDiff.isEmpty() || !schoolLifeDiff.isEmpty());

        // ---- diff-latest.json (full structured report) ---------------------
        writeDiffLatest(now, assignmentDiff, timetableDiff, gradeDiff, evaluationDiff,
                schoolLifeDiff, isFirstRun, hasChanges);

        // ---- diff-history.log (one-liner per run) --------------------------
        appendDiffHistory(now, assignmentDiff, timetableDiff, gradeDiff, evaluationDiff,
                schoolLifeDiff, isFirstRun, hasChanges);

        // ---- Structured log output at INFO level ---------------------------
        logDiffSummary(assignmentDiff, timetableDiff, gradeDiff, evaluationDiff,
                schoolLifeDiff, isFirstRun, hasChanges);
    }

    // -------------------------------------------------------------------------

    private void writeDiffLatest(Instant runAt,
                                 DiffResult<Assignment> asgn,
                                 DiffResult<TimetableEntry> tt,
                                 DiffResult<Grade> grades,
                                 DiffResult<CompetenceEvaluation> evals,
                                 DiffResult<SchoolLifeEvent> schoolLife,
                                 boolean isFirstRun, boolean hasChanges) {
        ObjectNode root = jackson.createObjectNode();
        root.put("runAt", runAt.toString());
        root.put("firstRun", isFirstRun);
        root.put("hasChanges", hasChanges);

        root.set("assignments",  buildSection(asgn,       isFirstRun));
        root.set("timetable",    buildSection(tt,         isFirstRun));
        root.set("grades",       buildSection(grades,     isFirstRun));
        root.set("evaluations",  buildSection(evals,      isFirstRun));
        root.set("schoolLife",   buildSection(schoolLife, isFirstRun));

        Path target = dataDir.resolve("diff-latest.json");
        try {
            jackson.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), root);
            log.debug("diff-latest.json written to {}", target);
        } catch (IOException e) {
            log.warn("Could not write diff-latest.json: {}", e.getMessage());
        }
    }

    private <T extends Identifiable> ObjectNode buildSection(DiffResult<T> diff, boolean isFirstRun) {
        ObjectNode section = jackson.createObjectNode();
        if (isFirstRun) {
            section.put("note", "first run — baseline established, no diff computed");
            return section;
        }
        section.put("added",    diff.getAdded().size());
        section.put("removed",  diff.getRemoved().size());
        section.put("modified", diff.getModified().size());

        ArrayNode addedArr = section.putArray("addedItems");
        for (T item : diff.getAdded()) {
            addedArr.add(jackson.valueToTree(item));
        }

        ArrayNode removedArr = section.putArray("removedItems");
        for (T item : diff.getRemoved()) {
            removedArr.add(jackson.valueToTree(item));
        }

        ArrayNode modifiedArr = section.putArray("modifiedItems");
        for (Map.Entry<T, List<FieldChange>> entry : diff.getModified().entrySet()) {
            ObjectNode modNode = jackson.createObjectNode();
            modNode.set("item", jackson.valueToTree(entry.getKey()));
            ArrayNode changes = modNode.putArray("changes");
            for (FieldChange fc : entry.getValue()) {
                ObjectNode changeNode = jackson.createObjectNode();
                changeNode.put("field",    fc.getFieldName());
                changeNode.put("oldValue", fc.getOldValue() != null ? fc.getOldValue().toString() : null);
                changeNode.put("newValue", fc.getNewValue() != null ? fc.getNewValue().toString() : null);
                changes.add(changeNode);
            }
            modifiedArr.add(modNode);
        }

        return section;
    }

    private void appendDiffHistory(Instant runAt,
                                   DiffResult<Assignment> asgn,
                                   DiffResult<TimetableEntry> tt,
                                   DiffResult<Grade> grades,
                                   DiffResult<CompetenceEvaluation> evals,
                                   DiffResult<SchoolLifeEvent> schoolLife,
                                   boolean isFirstRun, boolean hasChanges) {
        String line;
        if (isFirstRun) {
            line = runAt + " | FIRST_RUN  | baseline established";
        } else if (hasChanges) {
            int total = asgn.getAdded().size()       + asgn.getRemoved().size()       + asgn.getModified().size()
                    + tt.getAdded().size()          + tt.getRemoved().size()          + tt.getModified().size()
                    + grades.getAdded().size()      + grades.getRemoved().size()      + grades.getModified().size()
                    + evals.getAdded().size()       + evals.getRemoved().size()       + evals.getModified().size()
                    + schoolLife.getAdded().size()  + schoolLife.getRemoved().size()  + schoolLife.getModified().size();
            line = String.format(
                    "%s | CHANGES(%d) | assignments: +%d -%d ~%d | timetable: +%d -%d ~%d"
                            + " | grades: +%d -%d ~%d | evals: +%d -%d ~%d | school-life: +%d -%d ~%d",
                    runAt, total,
                    asgn.getAdded().size(),        asgn.getRemoved().size(),        asgn.getModified().size(),
                    tt.getAdded().size(),           tt.getRemoved().size(),           tt.getModified().size(),
                    grades.getAdded().size(),       grades.getRemoved().size(),       grades.getModified().size(),
                    evals.getAdded().size(),        evals.getRemoved().size(),        evals.getModified().size(),
                    schoolLife.getAdded().size(),   schoolLife.getRemoved().size(),   schoolLife.getModified().size());
        } else {
            line = String.format(
                    "%s | NO_CHANGE  | assignments: +0 -0 ~0 | timetable: +0 -0 ~0"
                            + " | grades: +0 -0 ~0 | evals: +0 -0 ~0 | school-life: +0 -0 ~0",
                    runAt);
        }

        Path historyFile = dataDir.resolve("diff-history.log");
        try {
            Files.writeString(historyFile, line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Could not append to diff-history.log: {}", e.getMessage());
        }
    }

    private void logDiffSummary(DiffResult<Assignment> asgn, DiffResult<TimetableEntry> tt,
                                DiffResult<Grade> grades, DiffResult<CompetenceEvaluation> evals,
                                DiffResult<SchoolLifeEvent> schoolLife,
                                boolean isFirstRun, boolean hasChanges) {
        if (isFirstRun) {
            log.info("First run — baseline snapshot established. No diff computed.");
            return;
        }

        if (!hasChanges) {
            log.info("Diff result: no changes (assignments +0 -0 ~0 | timetable +0 -0 ~0 "
                    + "| grades +0 -0 ~0 | evals +0 -0 ~0 | school-life +0 -0 ~0)");
            return;
        }

        log.info("Diff result: CHANGES DETECTED");
        log.info("  Assignments   : +{} added  -{} removed  ~{} modified",
                asgn.getAdded().size(), asgn.getRemoved().size(), asgn.getModified().size());
        for (Assignment a : asgn.getAdded()) {
            log.info("    + NEW      : [{}] {} — due {}", a.getSubject(), truncate(a.getDescription(), 60), a.getDueDate());
        }
        for (Assignment a : asgn.getRemoved()) {
            log.info("    - REMOVED  : [{}] due {}", a.getSubject(), a.getDueDate());
        }
        for (Map.Entry<Assignment, List<FieldChange>> e : asgn.getModified().entrySet()) {
            log.info("    ~ MODIFIED : [{}] due {}", e.getKey().getSubject(), e.getKey().getDueDate());
            for (FieldChange fc : e.getValue()) {
                log.info("        {} : {} → {}", fc.getFieldName(), fc.getOldValue(), fc.getNewValue());
            }
        }

        log.info("  Timetable     : +{} added  -{} removed  ~{} modified",
                tt.getAdded().size(), tt.getRemoved().size(), tt.getModified().size());
        for (TimetableEntry e : tt.getAdded()) {
            log.info("    + NEW      : [{}] {} room:{}", e.getSubject(), e.getStartTime(), e.getRoom());
        }
        for (TimetableEntry e : tt.getRemoved()) {
            log.info("    - REMOVED  : [{}] {}", e.getSubject(), e.getStartTime());
        }
        for (Map.Entry<TimetableEntry, List<FieldChange>> e : tt.getModified().entrySet()) {
            log.info("    ~ MODIFIED : [{}] {}", e.getKey().getSubject(), e.getKey().getStartTime());
            for (FieldChange fc : e.getValue()) {
                log.info("        {} : {} → {}", fc.getFieldName(), fc.getOldValue(), fc.getNewValue());
            }
        }

        log.info("  Grades        : +{} added  -{} removed  ~{} modified",
                grades.getAdded().size(), grades.getRemoved().size(), grades.getModified().size());
        for (Grade g : grades.getAdded()) {
            log.info("    + NEW      : [{}] {} — {} (coeff:{}) period:{}",
                    g.getSubject(), g.getValue(), g.getDate(), g.getCoefficient(), g.getPeriodName());
        }
        for (Grade g : grades.getRemoved()) {
            log.info("    - REMOVED  : [{}] {} ({})", g.getSubject(), g.getDate(), g.getPeriodName());
        }
        for (Map.Entry<Grade, List<FieldChange>> e : grades.getModified().entrySet()) {
            log.info("    ~ MODIFIED : [{}] {}", e.getKey().getSubject(), e.getKey().getDate());
            for (FieldChange fc : e.getValue()) {
                log.info("        {} : {} → {}", fc.getFieldName(), fc.getOldValue(), fc.getNewValue());
            }
        }

        log.info("  Evaluations   : +{} added  -{} removed  ~{} modified",
                evals.getAdded().size(), evals.getRemoved().size(), evals.getModified().size());
        for (CompetenceEvaluation ev : evals.getAdded()) {
            log.info("    + NEW      : [{}] \"{}\" — {} period:{}",
                    ev.getSubject(), ev.getName(), ev.getDate(), ev.getPeriodName());
        }
        for (CompetenceEvaluation ev : evals.getRemoved()) {
            log.info("    - REMOVED  : [{}] \"{}\" ({})", ev.getSubject(), ev.getName(), ev.getDate());
        }
        for (Map.Entry<CompetenceEvaluation, List<FieldChange>> e : evals.getModified().entrySet()) {
            log.info("    ~ MODIFIED : [{}] \"{}\" {}", e.getKey().getSubject(), e.getKey().getName(), e.getKey().getDate());
            for (FieldChange fc : e.getValue()) {
                log.info("        {} : {} → {}", fc.getFieldName(), fc.getOldValue(), fc.getNewValue());
            }
        }

        log.info("  School Life   : +{} added  -{} removed  ~{} modified",
                schoolLife.getAdded().size(), schoolLife.getRemoved().size(), schoolLife.getModified().size());
        for (SchoolLifeEvent e : schoolLife.getAdded()) {
            log.info("    + NEW      : [{}] {} reasons:\"{}\"", e.getType(), e.getDate(), e.getReasons());
        }
        for (SchoolLifeEvent e : schoolLife.getRemoved()) {
            log.info("    - REMOVED  : [{}] {}", e.getType(), e.getDate());
        }
        for (Map.Entry<SchoolLifeEvent, List<FieldChange>> e : schoolLife.getModified().entrySet()) {
            log.info("    ~ MODIFIED : [{}] {}", e.getKey().getType(), e.getKey().getDate());
            for (FieldChange fc : e.getValue()) {
                log.info("        {} : {} → {}", fc.getFieldName(), fc.getOldValue(), fc.getNewValue());
            }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null || s.isBlank()) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
