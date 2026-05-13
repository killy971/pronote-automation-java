package com.pronote.notification;

import com.pronote.domain.Assignment;
import com.pronote.domain.CompetenceEvaluation;
import com.pronote.domain.Grade;
import com.pronote.domain.SchoolLifeEvent;
import com.pronote.domain.TimetableEntry;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Pure formatting helpers shared by {@link NotificationPayloadBuilder}.
 *
 * <p>Stateless, side-effect-free, package-internal-ish — public so tests can pin behaviour.
 */
public final class NotificationFormatter {

    /** Abbreviated French weekday names (Mon..Sun), used in compact date displays. */
    private static final String[] DAYS_FR =
            {"lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim."};

    private NotificationFormatter() {}

    /** {@code "lun. 1/05"} format. Returns {@code "?"} for {@code null}. */
    public static String fmtDate(LocalDate d) {
        if (d == null) return "?";
        return DAYS_FR[d.getDayOfWeek().getValue() - 1]
                + " " + d.getDayOfMonth() + "/" + String.format("%02d", d.getMonthValue());
    }

    /** {@code "lun. 1/05 08h30"} format. Returns {@code "?"} for {@code null}. */
    public static String fmtDateTime(LocalDateTime dt) {
        if (dt == null) return "?";
        return fmtDate(dt.toLocalDate())
                + " " + String.format("%02dh%02d", dt.getHour(), dt.getMinute());
    }

    /** Drops the {@code .0} suffix from integer-valued doubles (e.g. {@code 20.0 → "20"}). */
    public static String fmtOutOf(double outOf) {
        return outOf == (long) outOf ? String.valueOf((long) outOf) : String.valueOf(outOf);
    }

    /** Drops the {@code .0} suffix from integer-valued coefficient doubles. */
    public static String fmtCoef(double coef) {
        return coef == (long) coef ? String.valueOf((long) coef) : String.valueOf(coef);
    }

    /** Human-readable French label for a school-life event type. */
    public static String fmtEventType(SchoolLifeEvent.EventType type) {
        if (type == null) return "Événement";
        return switch (type) {
            case ABSENCE     -> "Absence";
            case DELAY       -> "Retard";
            case INFIRMARY   -> "Infirmerie";
            case PUNISHMENT  -> "Punition";
            case OBSERVATION -> "Observation";
            case OTHER       -> "Événement";
        };
    }

    /** Truncates with an ellipsis if longer than {@code maxLen}. */
    public static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }

    // ---- subject helpers (enrichedSubject with fallback) --------------------

    public static String subject(Assignment a) {
        String s = a.getEnrichedSubject();
        return s != null && !s.isBlank() ? s : (a.getSubject() != null ? a.getSubject() : "?");
    }

    public static String subject(TimetableEntry e) {
        String s = e.getEnrichedSubject();
        return s != null && !s.isBlank() ? s : (e.getSubject() != null ? e.getSubject() : "?");
    }

    public static String subject(Grade g) {
        String s = g.getEnrichedSubject();
        return s != null && !s.isBlank() ? s : (g.getSubject() != null ? g.getSubject() : "?");
    }

    public static String subject(CompetenceEvaluation ev) {
        String s = ev.getEnrichedSubject();
        return s != null && !s.isBlank() ? s : (ev.getSubject() != null ? ev.getSubject() : "?");
    }
}
