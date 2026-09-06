package com.pronote.notification;

import com.pronote.domain.EntryStatus;
import com.pronote.domain.TimetableEntry;
import com.pronote.domain.TimetableSlots;
import com.pronote.persistence.DiffResult;
import com.pronote.persistence.FieldChange;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a timetable diff the way the timetable view reads a day — by slot rather than by entry.
 *
 * <p>Pronote expresses a room change (and a substitute teacher, and a maintained-but-altered
 * lesson) as a cancelled ghost of the original slot plus a live entry at the same start time.
 * Taken entry by entry that is "a cancellation and an addition"; taken slot by slot it is one
 * lesson that moved. {@link TimetableSlots} holds that reading; this class applies it to a
 * {@link DiffResult} so the notification says "salle B303 → B203" instead of "Cours annulé".
 *
 * <p>Every entry the diff carries lands in exactly one bucket, so the body renders each change
 * once and the title counts it once.
 */
final class TimetableChanges {

    /** A cancelled entry paired with the entry that took over its slot. */
    record SlotPair(TimetableEntry cancelled, TimetableEntry current) {}

    /** Cancelled with nothing taking the slot — the lesson really is off. */
    private final List<TimetableEntry> cancellations = new ArrayList<>();
    /** Cancelled, with a different subject in the slot. */
    private final List<SlotPair> replacements = new ArrayList<>();
    /** Cancelled ghost of a lesson that still happens — a room/teacher/detail change. */
    private final List<SlotPair> changes = new ArrayList<>();
    /** Newly announced upcoming competence evaluations. */
    private final List<TimetableEntry> addedEvals = new ArrayList<>();
    /** Plain new lessons, not part of any slot pairing. */
    private final List<TimetableEntry> additions = new ArrayList<>();
    private final List<TimetableEntry> removals;
    /** Field-level modifications not already told by a slot pairing. */
    private final Map<TimetableEntry, List<FieldChange>> modifications = new LinkedHashMap<>();

    static TimetableChanges of(DiffResult<TimetableEntry> diff, List<TimetableEntry> currentTimetable) {
        return new TimetableChanges(diff, currentTimetable);
    }

    private TimetableChanges(DiffResult<TimetableEntry> diff, List<TimetableEntry> currentTimetable) {
        TimetableSlots slots = TimetableSlots.index(slotContext(diff, currentTimetable));

        // Entries the diff surfaces as newly cancelled: a modified entry whose status flipped, or
        // an added entry that arrives already cancelled — Pronote's usual shape for a room change.
        List<TimetableEntry> ghosts = new ArrayList<>();
        for (Map.Entry<TimetableEntry, List<FieldChange>> e : diff.modified().entrySet()) {
            if (e.getKey().getStatus() == EntryStatus.CANCELLED
                    && e.getValue().stream().anyMatch(fc -> "status".equals(fc.fieldName()))) {
                ghosts.add(e.getKey());
            }
        }
        for (TimetableEntry e : diff.added()) {
            if (e.getStatus() == EntryStatus.CANCELLED) ghosts.add(e);
        }

        // Entries whose own added/modified line is superseded by a bucket line below.
        Set<TimetableEntry> covered = new HashSet<>(ghosts);
        for (TimetableEntry ghost : ghosts) {
            TimetableEntry current = slots.supersederOf(ghost);
            switch (slots.outcomeOf(ghost)) {
                case CANCELLED -> cancellations.add(ghost);
                case MAINTAINED -> {
                    changes.add(new SlotPair(ghost, current));
                    covered.add(current);
                }
                case REPLACED -> {
                    replacements.add(new SlotPair(ghost, current));
                    covered.add(current);
                }
            }
        }

        for (TimetableEntry e : diff.added()) {
            if (covered.contains(e)) continue;
            if (e.isEval()) addedEvals.add(e);
            else additions.add(e);
        }
        for (Map.Entry<TimetableEntry, List<FieldChange>> e : diff.modified().entrySet()) {
            if (covered.contains(e.getKey())) continue;
            modifications.put(e.getKey(), e.getValue());
        }
        removals = diff.removed();
    }

    /**
     * The entry list slots are resolved against: the current snapshot, which alone shows what
     * occupies a slot even when that entry did not itself change. Falls back to the diff's own
     * entries when no snapshot is supplied, which still resolves the common case where Pronote
     * touched both halves of the pair.
     */
    private static List<TimetableEntry> slotContext(DiffResult<TimetableEntry> diff,
                                                    List<TimetableEntry> currentTimetable) {
        if (currentTimetable != null && !currentTimetable.isEmpty()) return currentTimetable;
        List<TimetableEntry> fallback = new ArrayList<>(diff.added());
        fallback.addAll(diff.modified().keySet());
        return fallback;
    }

    // -------------------------------------------------------------------------

    List<TimetableEntry> cancellations()               { return cancellations; }
    List<SlotPair> replacements()                      { return replacements; }
    List<SlotPair> changes()                           { return changes; }
    List<TimetableEntry> addedEvals()                  { return addedEvals; }
    List<TimetableEntry> additions()                   { return additions; }
    List<TimetableEntry> removals()                    { return removals; }
    Map<TimetableEntry, List<FieldChange>> modifications() { return modifications; }

    /** True when nothing survives the slot reading — every diff entry was folded into a pairing. */
    boolean isEmpty() {
        return cancellations.isEmpty() && replacements.isEmpty() && changes.isEmpty()
                && addedEvals.isEmpty() && additions.isEmpty() && removals.isEmpty()
                && modifications.isEmpty();
    }

    /** Timetable changes other than cancellations, replacements and new evals. */
    long otherChangeCount() {
        return changes.size() + additions.size() + removals.size() + modifications.size();
    }

    /** Cancellations and replacements both mean the listed lesson is not happening. */
    boolean hasLostLessons() {
        return !cancellations.isEmpty() || !replacements.isEmpty();
    }
}
