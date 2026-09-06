package com.pronote.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Slot-level reading of a timetable: which entries share a start time and — when Pronote cancels
 * one and lists another in its place — which entry supersedes which.
 *
 * <p><b>Why this exists.</b> Pronote does not model a room change, a substitute teacher or a
 * maintained-but-altered lesson as a field edit on the lesson. It keeps the original slot and
 * flags it {@code estAnnule}, then lists a <em>second</em> entry at the same start time carrying
 * the new details. Read entry by entry that looks like "class cancelled (+ a new class)"; read
 * slot by slot it is one lesson whose room changed.
 *
 * <p>The timetable view has always applied the slot reading (that is what collapses the two cards
 * into one). This class holds that logic so the notification builder applies it too — otherwise a
 * room change goes out titled "✗ Mathématiques Cours annulé".
 *
 * <p>Immutable; build one with {@link #index(List)} and query it.
 */
public final class TimetableSlots {

    /** Prefix of every synthetic id produced by {@code ManualEntryLoader}. */
    private static final String MANUAL_PREFIX = "manual:";

    /** What actually happens to a slot whose entry Pronote marked cancelled/exempted. */
    public enum SlotOutcome {
        /** Nothing takes the slot — the lesson really is off. */
        CANCELLED,
        /** Another subject occupies the slot — a replacement class. */
        REPLACED,
        /** The same subject occupies the slot — the lesson happens, with changed details. */
        MAINTAINED
    }

    private final Map<LocalDateTime, List<TimetableEntry>> byStart;

    private TimetableSlots(Map<LocalDateTime, List<TimetableEntry>> byStart) {
        this.byStart = byStart;
    }

    /** Groups entries by start time, preserving encounter order. Null start times are dropped. */
    public static TimetableSlots index(List<TimetableEntry> entries) {
        Map<LocalDateTime, List<TimetableEntry>> byStart = new LinkedHashMap<>();
        for (TimetableEntry e : entries) {
            if (e.getStartTime() == null) continue;
            byStart.computeIfAbsent(e.getStartTime(), k -> new ArrayList<>()).add(e);
        }
        return new TimetableSlots(byStart);
    }

    /** All slot groups, in encounter order of their first entry. */
    public Collection<List<TimetableEntry>> slots() {
        return byStart.values();
    }

    /** All entries sharing {@code entry}'s start time, including {@code entry} itself. */
    public List<TimetableEntry> slotOf(TimetableEntry entry) {
        if (entry.getStartTime() == null) return List.of();
        return byStart.getOrDefault(entry.getStartTime(), List.of());
    }

    /**
     * The entry standing in the place of a cancelled/exempted one, or {@code null} when the slot
     * is genuinely empty. Same-subject candidates win over different-subject ones, so a room
     * change resolves to its own lesson rather than to an unrelated class at the same hour.
     */
    public TimetableEntry supersederOf(TimetableEntry inactive) {
        return superseder(inactive, slotOf(inactive));
    }

    /** Classifies what became of a cancelled/exempted entry. See {@link SlotOutcome}. */
    public SlotOutcome outcomeOf(TimetableEntry inactive) {
        TimetableEntry replacement = supersederOf(inactive);
        if (replacement == null) return SlotOutcome.CANCELLED;
        return Objects.equals(replacement.getSubject(), inactive.getSubject())
                ? SlotOutcome.MAINTAINED
                : SlotOutcome.REPLACED;
    }

    /**
     * Picks the superseding entry within an already-grouped slot.
     *
     * <p>Manual entries are never candidates: a synthetic upcoming-eval marker sitting on the same
     * hour must not make a real cancellation look maintained.
     */
    public static TimetableEntry superseder(TimetableEntry inactive, List<TimetableEntry> slot) {
        TimetableEntry fallback = null;
        for (TimetableEntry candidate : slot) {
            if (candidate == inactive || !isActive(candidate) || isManual(candidate)) continue;
            if (Objects.equals(candidate.getSubject(), inactive.getSubject())) return candidate;
            if (fallback == null) fallback = candidate;
        }
        return fallback;
    }

    /** True when Pronote says this entry is not happening as listed (cancelled or exempted). */
    public static boolean isInactive(TimetableEntry e) {
        return e.getStatus() == EntryStatus.CANCELLED || e.getStatus() == EntryStatus.EXEMPTED;
    }

    /** True when the entry is a lesson that is actually taking place. */
    public static boolean isActive(TimetableEntry e) {
        return !isInactive(e);
    }

    /** True for synthetic entries loaded from {@code manual-entries.yaml}. */
    public static boolean isManual(TimetableEntry e) {
        return e.getId() != null && e.getId().startsWith(MANUAL_PREFIX);
    }
}
