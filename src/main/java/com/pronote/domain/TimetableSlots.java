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
 * <p><b>The replacement need not share the cancelled lesson's hours.</b> A school outing or a
 * whole-morning activity is one entry running, say, 11:00–13:00 over two cancelled one-hour
 * lessons. Matching superseders on an exact start time saw only the first of those as taken over
 * and announced the second as a plain cancellation. A candidate therefore qualifies when its own
 * range <em>covers the cancelled entry's start</em> — which still matches the same-start twin of a
 * room change, and additionally matches the later lessons a long entry swallows. Merely
 * overlapping is not enough: two lessons at 14:00 and 14:30 overlap without one standing in for
 * the other, and pairing those would read a normal day as a replacement.
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
    /** Every indexed entry, in encounter order — the candidate pool for superseder lookups. */
    private final List<TimetableEntry> all;

    private TimetableSlots(Map<LocalDateTime, List<TimetableEntry>> byStart,
                           List<TimetableEntry> all) {
        this.byStart = byStart;
        this.all = all;
    }

    /** Groups entries by start time, preserving encounter order. Null start times are dropped. */
    public static TimetableSlots index(List<TimetableEntry> entries) {
        Map<LocalDateTime, List<TimetableEntry>> byStart = new LinkedHashMap<>();
        List<TimetableEntry> all = new ArrayList<>();
        for (TimetableEntry e : entries) {
            if (e.getStartTime() == null) continue;
            byStart.computeIfAbsent(e.getStartTime(), k -> new ArrayList<>()).add(e);
            all.add(e);
        }
        return new TimetableSlots(byStart, all);
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
        return superseder(inactive, all);
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
     * Picks the entry that stands in for {@code inactive} out of {@code candidates}, or
     * {@code null} when none does. Candidates may be any entry list — the covering rule below
     * does the filtering, so callers need not pre-group by start time.
     *
     * <p>Ranking, most specific first: the same subject (a room change resolves to its own lesson
     * rather than to an unrelated class at the same hour), then the same start time, then the
     * longest overlap. Ties keep encounter order.
     *
     * <p>Manual entries are never candidates: a synthetic upcoming-eval marker sitting on the same
     * hour must not make a real cancellation look maintained.
     */
    public static TimetableEntry superseder(TimetableEntry inactive, List<TimetableEntry> candidates) {
        TimetableEntry best = null;
        long bestRank = Long.MIN_VALUE;
        for (TimetableEntry candidate : candidates) {
            if (candidate == inactive || !isActive(candidate) || isManual(candidate)) continue;
            if (!covers(candidate, inactive)) continue;
            long rank = rank(inactive, candidate);
            if (rank > bestRank) {
                bestRank = rank;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * True when {@code candidate} occupies the hour {@code inactive} was scheduled to start at —
     * either by starting with it or by running across it. Entries with no end time fall back to
     * requiring the same start, which is how the field behaved before end times were consulted.
     */
    private static boolean covers(TimetableEntry candidate, TimetableEntry inactive) {
        LocalDateTime start = inactive.getStartTime();
        LocalDateTime from = candidate.getStartTime();
        if (start == null || from == null) return false;
        if (from.equals(start)) return true;
        LocalDateTime to = candidate.getEndTime();
        return to != null && from.isBefore(start) && to.isAfter(start);
    }

    /** Higher is a better superseder. See {@link #superseder} for the ordering this encodes. */
    private static long rank(TimetableEntry inactive, TimetableEntry candidate) {
        long rank = 0;
        if (Objects.equals(candidate.getSubject(), inactive.getSubject())) rank += 1L << 40;
        if (candidate.getStartTime().equals(inactive.getStartTime()))      rank += 1L << 39;
        return rank + overlapMinutes(inactive, candidate);
    }

    /** Minutes the two ranges share; 0 when either has no end time. */
    private static long overlapMinutes(TimetableEntry a, TimetableEntry b) {
        if (a.getEndTime() == null || b.getEndTime() == null) return 0;
        LocalDateTime from = a.getStartTime().isAfter(b.getStartTime()) ? a.getStartTime() : b.getStartTime();
        LocalDateTime to   = a.getEndTime().isBefore(b.getEndTime())   ? a.getEndTime()   : b.getEndTime();
        return from.isBefore(to) ? java.time.Duration.between(from, to).toMinutes() : 0;
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
