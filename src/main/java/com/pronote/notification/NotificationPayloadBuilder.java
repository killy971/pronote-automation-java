package com.pronote.notification;

import com.pronote.domain.Assignment;
import com.pronote.domain.AttachmentRef;
import com.pronote.domain.CompetenceEvaluation;
import com.pronote.domain.EntryStatus;
import com.pronote.domain.Grade;
import com.pronote.domain.SchoolLifeEvent;
import com.pronote.domain.TimetableEntry;
import com.pronote.persistence.DiffResult;
import com.pronote.persistence.FieldChange;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.pronote.notification.NotificationFormatter.*;

/**
 * Builds a {@link NotificationPayload} from a set of diff results.
 *
 * <p>Pure: takes diff results in, returns the payload out, no I/O or side effects.
 * The class is stateless; the static {@link #build} entry point is the only public API.
 *
 * <p>Title shape: up to three change tokens joined by {@code " · "}, hard-capped at 72 chars
 * with an ellipsis. Body shape: per-category sections, prefixed with an emoji header when
 * more than one section has content. Priority is {@code HIGH} when a cancellation, a
 * timetable removal, or a school-life addition is present; {@code NORMAL} otherwise.
 */
public final class NotificationPayloadBuilder {

    private static final List<String> DEFAULT_TAGS = List.of("school", "pronote");

    private NotificationPayloadBuilder() {}

    /**
     * Builds a payload without a timetable snapshot to resolve slots against. Slot pairing then
     * falls back to the diff's own entries — see {@link TimetableChanges}. Prefer the overload
     * that takes the current timetable.
     */
    public static NotificationPayload build(DiffResult<Assignment> assignmentDiff,
                                            DiffResult<TimetableEntry> timetableDiff,
                                            DiffResult<Grade> gradeDiff,
                                            DiffResult<CompetenceEvaluation> evaluationDiff,
                                            DiffResult<SchoolLifeEvent> schoolLifeDiff) {
        return build(assignmentDiff, timetableDiff, gradeDiff, evaluationDiff, schoolLifeDiff,
                List.of());
    }

    /**
     * @param currentTimetable the timetable snapshot the diff produced — used to tell a real
     *                         cancellation from the cancelled ghost Pronote leaves behind when a
     *                         lesson merely changes room or teacher.
     */
    public static NotificationPayload build(DiffResult<Assignment> assignmentDiff,
                                            DiffResult<TimetableEntry> timetableDiff,
                                            DiffResult<Grade> gradeDiff,
                                            DiffResult<CompetenceEvaluation> evaluationDiff,
                                            DiffResult<SchoolLifeEvent> schoolLifeDiff,
                                            List<TimetableEntry> currentTimetable) {
        TimetableChanges tt = TimetableChanges.of(timetableDiff, currentTimetable);

        String title = buildTitle(assignmentDiff, tt, gradeDiff, evaluationDiff, schoolLifeDiff);

        int sectionsWithChanges = (assignmentDiff.isEmpty() ? 0 : 1)
                + (tt.isEmpty() ? 0 : 1)
                + (gradeDiff.isEmpty() ? 0 : 1)
                + (evaluationDiff.isEmpty() ? 0 : 1)
                + (schoolLifeDiff.isEmpty() ? 0 : 1);
        boolean multiSection = sectionsWithChanges > 1;

        StringBuilder body = new StringBuilder();
        if (!assignmentDiff.isEmpty()) {
            if (multiSection) body.append("📚 Devoirs\n");
            appendAssignmentLines(body, assignmentDiff);
            if (multiSection) body.append("\n");
        }
        if (!tt.isEmpty()) {
            if (multiSection) body.append("📅 Emploi du temps\n");
            appendTimetableLines(body, tt);
            if (multiSection) body.append("\n");
        }
        if (!gradeDiff.isEmpty()) {
            if (multiSection) body.append("📊 Notes\n");
            appendGradeLines(body, gradeDiff);
            if (multiSection) body.append("\n");
        }
        if (!evaluationDiff.isEmpty()) {
            if (multiSection) body.append("📋 Évaluations\n");
            appendEvaluationLines(body, evaluationDiff);
            if (multiSection) body.append("\n");
        }
        if (!schoolLifeDiff.isEmpty()) {
            if (multiSection) body.append("🏫 Vie scolaire\n");
            appendSchoolLifeLines(body, schoolLifeDiff);
        }

        // A lost lesson or a school-life event is worth interrupting for; a room change is not.
        NotificationPayload.Priority priority =
                (tt.hasLostLessons() || !tt.removals().isEmpty()
                        || !schoolLifeDiff.added().isEmpty())
                ? NotificationPayload.Priority.HIGH
                : NotificationPayload.Priority.NORMAL;

        return new NotificationPayload(title, body.toString().trim(), priority, DEFAULT_TAGS);
    }

    // -------------------------------------------------------------------------
    // Title
    // -------------------------------------------------------------------------

    private static String buildTitle(DiffResult<Assignment> asgn,
                                     TimetableChanges tt,
                                     DiffResult<Grade> grades,
                                     DiffResult<CompetenceEvaluation> evals,
                                     DiffResult<SchoolLifeEvent> schoolLife) {
        List<String> tokens = new ArrayList<>();

        // 1. Cancellations (highest urgency) — only lessons nothing takes over
        List<TimetableEntry> cancelled = tt.cancellations();
        if (!cancelled.isEmpty()) {
            if (cancelled.size() == 1) {
                TimetableEntry e = cancelled.get(0);
                String reason = e.getStatusLabel() != null && !e.getStatusLabel().isBlank()
                        ? e.getStatusLabel() : "annulé";
                tokens.add("✗ " + subject(e) + " " + reason + " · " + fmtDateTime(e.getStartTime()));
            } else {
                tokens.add("✗ " + cancelled.size() + " cours annulés");
            }
        }

        // 1b. Replacements — the listed lesson is off, but another subject takes the slot
        List<TimetableChanges.SlotPair> replacements = tt.replacements();
        if (!replacements.isEmpty()) {
            if (replacements.size() == 1) {
                TimetableChanges.SlotPair p = replacements.get(0);
                tokens.add("🔄 " + subject(p.current()) + " remplace " + subject(p.cancelled())
                        + " · " + fmtDateTime(p.cancelled().getStartTime()));
            } else {
                tokens.add("🔄 " + replacements.size() + " cours remplacés");
            }
        }

        // 2. New grades
        if (!grades.added().isEmpty()) {
            if (grades.added().size() == 1) {
                Grade g = grades.added().get(0);
                tokens.add("📊 " + subject(g) + ": " + g.getValue() + "/" + fmtOutOf(g.getOutOf()));
            } else {
                tokens.add("📊 " + grades.added().size() + " notes");
            }
        }

        // 3. New assignments
        if (!asgn.added().isEmpty()) {
            if (asgn.added().size() == 1) {
                Assignment a = asgn.added().get(0);
                tokens.add("📚 " + subject(a) + " · " + fmtDate(a.getDueDate()));
            } else {
                tokens.add("📚 " + asgn.added().size() + " devoirs");
            }
        }

        // 4a. Newly announced upcoming competence evaluations (timetable isEval=true)
        List<TimetableEntry> addedEvalEntries = tt.addedEvals();
        if (!addedEvalEntries.isEmpty()) {
            if (addedEvalEntries.size() == 1) {
                TimetableEntry e = addedEvalEntries.get(0);
                tokens.add("📝 " + subject(e) + " éval · " + fmtDate(e.getStartTime().toLocalDate()));
            } else {
                tokens.add("📝 " + addedEvalEntries.size() + " évals à venir");
            }
        }

        // 4b. A single maintained-but-changed lesson: name the change, since "1 modif. EDT" is
        // exactly the case the reader has to open the app to understand.
        boolean anyLost = !cancelled.isEmpty() || !replacements.isEmpty();
        if (tt.changes().size() == 1 && tt.otherChangeCount() == 1) {
            TimetableChanges.SlotPair p = tt.changes().get(0);
            tokens.add("🔀 " + subject(p.current()) + " " + slotChangeSummary(p)
                    + " · " + fmtDate(p.current().getStartTime().toLocalDate()));
        } else {
            // 4c. Other timetable changes (additions, removals, modifications, room changes)
            long otherTtChanges = tt.otherChangeCount();
            if (otherTtChanges > 0) {
                tokens.add(anyLost
                        ? "📅 +" + otherTtChanges + " modif."
                        : "📅 " + otherTtChanges + " modif. EDT");
            }
        }

        // 5. New school life events
        if (!schoolLife.added().isEmpty()) {
            if (schoolLife.added().size() == 1) {
                SchoolLifeEvent e = schoolLife.added().get(0);
                tokens.add("🏫 " + fmtEventType(e.getType()) + " · " + fmtDate(e.getDate()));
            } else {
                tokens.add("🏫 " + schoolLife.added().size() + " événements");
            }
        }

        // 6. New evaluations
        if (!evals.added().isEmpty()) {
            if (evals.added().size() == 1) {
                tokens.add("📋 " + subject(evals.added().get(0)) + " éval.");
            } else {
                tokens.add("📋 " + evals.added().size() + " évals.");
            }
        }

        // 7. Catch-all: modifications/removals only
        if (tokens.isEmpty()) {
            int mods = asgn.modified().size() + (int) tt.otherChangeCount() + grades.modified().size()
                    + evals.modified().size() + schoolLife.modified().size()
                    + asgn.removed().size() + grades.removed().size()
                    + evals.removed().size() + schoolLife.removed().size();
            tokens.add("Pronote: " + mods + " modification(s)");
        }

        String joined = String.join(" · ", tokens.subList(0, Math.min(3, tokens.size())));
        return joined.length() <= 72 ? joined : joined.substring(0, 70) + "…";
    }

    // -------------------------------------------------------------------------
    // Per-category body renderers
    // -------------------------------------------------------------------------

    private static void appendAssignmentLines(StringBuilder b, DiffResult<Assignment> diff) {
        for (Assignment a : diff.added()) {
            b.append("+ ").append(subject(a)).append(" · ").append(fmtDate(a.getDueDate()));
            if (a.getDescription() != null && !a.getDescription().isBlank()) {
                b.append(" — ").append(truncate(a.getDescription(), 80));
            }
            List<AttachmentRef> att = a.getAttachments();
            if (att != null && !att.isEmpty()) {
                long files = att.stream().filter(AttachmentRef::isUploadedFile).count();
                long links = att.stream().filter(r -> !r.isUploadedFile()).count();
                if (files == 1) {
                    String fname = att.stream().filter(AttachmentRef::isUploadedFile)
                            .map(AttachmentRef::getFileName).findFirst().orElse("fichier");
                    b.append(" [📎 ").append(truncate(fname, 20)).append("]");
                } else if (files > 1) {
                    b.append(" [📎×").append(files).append("]");
                }
                if (links == 1) {
                    String fname = att.stream().filter(r -> !r.isUploadedFile())
                            .map(AttachmentRef::getFileName).findFirst().orElse("lien");
                    b.append(" [🔗 ").append(truncate(fname, 20)).append("]");
                } else if (links > 1) {
                    b.append(" [🔗×").append(links).append("]");
                }
            }
            b.append("\n");
        }
        for (Assignment a : diff.removed()) {
            b.append("- ").append(subject(a)).append(" · ").append(fmtDate(a.getDueDate()))
                    .append(" [supprimé]\n");
        }
        for (Map.Entry<Assignment, List<FieldChange>> entry : diff.modified().entrySet()) {
            Assignment a = entry.getKey();
            b.append("~ ").append(subject(a)).append(" · ").append(fmtDate(a.getDueDate()));
            List<FieldChange> changes = entry.getValue().stream()
                    .filter(fc -> !"enrichedSubject".equals(fc.fieldName())).toList();
            if (changes.size() == 1) {
                b.append(" — ").append(fmtAssignmentChange(changes.get(0)));
            } else if (!changes.isEmpty()) {
                b.append(" [").append(changes.size()).append(" champs modifiés]");
            }
            b.append("\n");
        }
    }

    private static void appendTimetableLines(StringBuilder b, TimetableChanges tt) {
        // Cancellations first: a lesson that is off outranks anything else in the section.
        for (TimetableEntry e : tt.cancellations()) {
            b.append("✗ ").append(subject(e)).append(" — ").append(fmtDateTime(e.getStartTime()));
            if (e.getStatusLabel() != null && !e.getStatusLabel().isBlank()) {
                b.append(" · ").append(e.getStatusLabel());
            }
            b.append("\n");
        }
        for (TimetableChanges.SlotPair p : tt.replacements()) {
            TimetableEntry c = p.current();
            b.append("🔄 ").append(subject(c)).append(" remplace ").append(subject(p.cancelled()))
                    .append(" — ").append(fmtDateTime(c.getStartTime()));
            if (c.getRoom() != null && !c.getRoom().isBlank()) {
                b.append(" (").append(c.getRoom()).append(")");
            }
            b.append("\n");
        }
        // Same subject on both sides of the slot: the lesson holds, its details moved.
        for (TimetableChanges.SlotPair p : tt.changes()) {
            b.append("🔀 ").append(subject(p.current())).append(" — ")
                    .append(fmtDateTime(p.current().getStartTime()))
                    .append(" · ").append(slotChangeDetail(p)).append("\n");
        }
        for (TimetableEntry e : tt.addedEvals()) {
            // The eval's own label (e.g. "DS énergie") replaces the room field, since rooms are
            // uninformative for evaluations.
            b.append("📝 ").append(subject(e)).append(" — ").append(fmtDateTime(e.getStartTime()));
            if (e.getLessonLabel() != null && !e.getLessonLabel().isBlank()) {
                b.append(" — ").append(truncate(e.getLessonLabel(), 60));
            }
            b.append("\n");
        }
        for (TimetableEntry e : tt.additions()) {
            b.append("+ ").append(subject(e)).append(" — ").append(fmtDateTime(e.getStartTime()));
            if (e.getRoom() != null && !e.getRoom().isBlank()) {
                b.append(" (").append(e.getRoom()).append(")");
            }
            b.append("\n");
        }
        for (TimetableEntry e : tt.removals()) {
            b.append("- ").append(subject(e)).append(" — ").append(fmtDateTime(e.getStartTime()))
                    .append(" [supprimé]\n");
        }
        for (Map.Entry<TimetableEntry, List<FieldChange>> entry : tt.modifications().entrySet()) {
            TimetableEntry e = entry.getKey();
            String prefix = e.getStatus() == EntryStatus.CANCELLED ? "✗ " : "~ ";
            b.append(prefix).append(subject(e)).append(" — ").append(fmtDateTime(e.getStartTime()));
            String detail = fmtTimetableChanges(e, entry.getValue());
            if (!detail.isBlank()) b.append(" · ").append(detail);
            b.append("\n");
        }
    }

    private static void appendGradeLines(StringBuilder b, DiffResult<Grade> diff) {
        for (Grade g : diff.added()) {
            b.append("+ ").append(subject(g)).append(": ").append(g.getValue())
                    .append("/").append(fmtOutOf(g.getOutOf()));
            if (g.getComment() != null && !g.getComment().isBlank()) {
                b.append(" — \"").append(truncate(g.getComment(), 40)).append("\"");
            }
            if (g.getCoefficient() != 1.0) {
                b.append(" (coef.").append(fmtCoef(g.getCoefficient())).append(")");
            }
            b.append("\n");
        }
        for (Grade g : diff.removed()) {
            b.append("- ").append(subject(g)).append(" · ").append(fmtDate(g.getDate()))
                    .append(" [supprimé]\n");
        }
        for (Map.Entry<Grade, List<FieldChange>> entry : diff.modified().entrySet()) {
            Grade g = entry.getKey();
            b.append("~ ").append(subject(g)).append(" · ").append(fmtDate(g.getDate()));
            FieldChange valChange = entry.getValue().stream()
                    .filter(fc -> "value".equals(fc.fieldName())).findFirst().orElse(null);
            if (valChange != null) {
                b.append(": ").append(valChange.oldValue()).append("→")
                        .append(valChange.newValue()).append("/").append(fmtOutOf(g.getOutOf()));
            } else {
                b.append(" [modifié]");
            }
            b.append("\n");
        }
    }

    private static void appendEvaluationLines(StringBuilder b, DiffResult<CompetenceEvaluation> diff) {
        for (CompetenceEvaluation e : diff.added()) {
            b.append("+ ").append(subject(e));
            if (e.getName() != null && !e.getName().isBlank()) {
                b.append(" \"").append(truncate(e.getName(), 40)).append("\"");
            }
            if (e.getDate() != null) b.append(" — ").append(fmtDate(e.getDate()));
            if (e.getPeriodName() != null && !e.getPeriodName().isBlank()) {
                b.append(" [").append(e.getPeriodName()).append("]");
            }
            b.append("\n");
        }
        for (CompetenceEvaluation e : diff.removed()) {
            b.append("- ").append(subject(e));
            if (e.getName() != null) b.append(" \"").append(truncate(e.getName(), 30)).append("\"");
            b.append(" [supprimé]\n");
        }
        for (Map.Entry<CompetenceEvaluation, List<FieldChange>> entry : diff.modified().entrySet()) {
            CompetenceEvaluation e = entry.getKey();
            b.append("~ ").append(subject(e));
            if (e.getName() != null) b.append(" \"").append(truncate(e.getName(), 30)).append("\"");
            b.append(" [modifié]\n");
        }
    }

    private static void appendSchoolLifeLines(StringBuilder b, DiffResult<SchoolLifeEvent> diff) {
        for (SchoolLifeEvent e : diff.added()) {
            b.append("+ ").append(fmtEventType(e.getType())).append(" ").append(fmtDate(e.getDate()));
            if (e.getTime() != null && !e.getTime().isBlank()) {
                b.append(" ").append(e.getTime().replace(":", "h"));
            }
            if (e.getMinutes() > 0) b.append(" (").append(e.getMinutes()).append(" min)");
            String label = e.getNature() != null && !e.getNature().isBlank() ? e.getNature()
                    : (e.getLabel() != null && !e.getLabel().isBlank() ? e.getLabel() : null);
            if (label != null) b.append(" — ").append(label);
            if (e.getReasons() != null && !e.getReasons().isBlank()) {
                b.append(" [").append(truncate(e.getReasons(), 40)).append("]");
            }
            if (e.isJustified()) b.append(" ✓");
            b.append("\n");
        }
        for (SchoolLifeEvent e : diff.removed()) {
            b.append("- ").append(fmtEventType(e.getType())).append(" ").append(fmtDate(e.getDate()))
                    .append(" [supprimé]\n");
        }
        for (Map.Entry<SchoolLifeEvent, List<FieldChange>> entry : diff.modified().entrySet()) {
            SchoolLifeEvent e = entry.getKey();
            b.append("~ ").append(fmtEventType(e.getType())).append(" ").append(fmtDate(e.getDate()))
                    .append(" [modifié]\n");
        }
    }

    // -------------------------------------------------------------------------
    // Field-change formatters
    // -------------------------------------------------------------------------

    private static String fmtAssignmentChange(FieldChange fc) {
        return switch (fc.fieldName()) {
            case "description" -> "description modifiée";
            case "dueDate"     -> "date: " + fc.oldValue() + "→" + fc.newValue();
            case "done"        -> Boolean.parseBoolean(String.valueOf(fc.newValue()))
                                  ? "marqué fait" : "marqué non fait";
            default            -> fc.fieldName() + " modifié";
        };
    }

    /**
     * What moved between the cancelled ghost and the lesson that replaced it, for the body:
     * {@code "salle B303 → B203"}. Falls back to Pronote's own status label
     * ({@code "Changement de salle"}) when the visible fields are identical.
     */
    private static String slotChangeDetail(TimetableChanges.SlotPair p) {
        List<String> parts = new ArrayList<>();
        if (differs(p.cancelled().getRoom(), p.current().getRoom())) {
            parts.add("salle " + orDash(p.cancelled().getRoom()) + " → " + orDash(p.current().getRoom()));
        }
        if (differs(p.cancelled().getTeacher(), p.current().getTeacher())) {
            parts.add("prof. " + orDash(p.cancelled().getTeacher()) + " → " + orDash(p.current().getTeacher()));
        }
        if (!java.util.Objects.equals(p.cancelled().getEndTime(), p.current().getEndTime())) {
            parts.add("horaire modifié");
        }
        if (parts.isEmpty()) {
            String label = p.current().getStatusLabel();
            return label != null && !label.isBlank() ? label : "modifié";
        }
        return String.join(", ", parts.subList(0, Math.min(2, parts.size())));
    }

    /** The same change in title form — new value only, no arrow, kept short. */
    private static String slotChangeSummary(TimetableChanges.SlotPair p) {
        if (differs(p.cancelled().getRoom(), p.current().getRoom())) {
            return "salle " + truncate(orDash(p.current().getRoom()), 20);
        }
        if (differs(p.cancelled().getTeacher(), p.current().getTeacher())) {
            return "prof. " + truncate(orDash(p.current().getTeacher()), 20);
        }
        if (!java.util.Objects.equals(p.cancelled().getEndTime(), p.current().getEndTime())) {
            return "horaire modifié";
        }
        String label = p.current().getStatusLabel();
        return label != null && !label.isBlank() ? truncate(label, 30) : "modifié";
    }

    private static boolean differs(String a, String b) {
        return !java.util.Objects.equals(blankToNull(a), blankToNull(b));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static String fmtTimetableChanges(TimetableEntry entry, List<FieldChange> changes) {
        // Prefer the human-readable status label from Pronote when available
        if (entry.getStatusLabel() != null && !entry.getStatusLabel().isBlank()) {
            return entry.getStatusLabel();
        }
        List<String> parts = new ArrayList<>();
        for (FieldChange fc : changes) {
            switch (fc.fieldName()) {
                case "room"                  -> parts.add("salle " + fc.oldValue() + "→" + fc.newValue());
                case "teacher"               -> parts.add("prof. modifié");
                case "startTime", "endTime"  -> parts.add("horaire modifié");
                // status change reflected by the ✗/~ prefix; statusLabel already handled above
                case "status", "statusLabel", "enrichedSubject", "subject", "isTest", "memo" -> { }
                default                      -> parts.add(fc.fieldName() + " modifié");
            }
        }
        return String.join(", ", parts.subList(0, Math.min(2, parts.size())));
    }
}
