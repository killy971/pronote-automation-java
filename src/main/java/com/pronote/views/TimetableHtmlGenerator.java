package com.pronote.views;

import com.pronote.domain.EntryStatus;
import com.pronote.domain.TimetableEntry;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Generates a self-contained HTML5 page for a single day's timetable.
 *
 * <p>Each generated document:
 * <ul>
 *   <li>Embeds all CSS — no external resources needed.</li>
 *   <li>Adapts to system light/dark preference via {@code prefers-color-scheme}.</li>
 *   <li>Is responsive: single-column layout fits both mobile (360 px) and desktop.</li>
 *   <li>Requires no JavaScript.</li>
 * </ul>
 */
public class TimetableHtmlGenerator {

    /** Accent palette used as subject-coded left borders (deterministic by subject name hash). */
    private static final String[] ACCENT_COLORS = {
        "#3b82f6", "#10b981", "#f59e0b", "#ef4444", "#8b5cf6",
        "#ec4899", "#14b8a6", "#f97316", "#06b6d4", "#84cc16",
        "#a855f7", "#6366f1"
    };

    private static final DateTimeFormatter FULL_DATE_FMT =
        DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH);
    private static final DateTimeFormatter SHORT_DATE_FMT =
        DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH);

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates the full HTML document for the given day.
     *
     * @param date    the date to render
     * @param entries all timetable entries for this specific day (may be empty)
     * @return a complete, self-contained HTML5 document
     */
    public String generate(LocalDate date, List<TimetableEntry> entries) {
        String fullDate  = capitalize(date.format(FULL_DATE_FMT));
        String titleDate = capitalize(date.format(SHORT_DATE_FMT));
        String schedule  = entries.isEmpty()
            ? "<p class=\"empty-day\">Pas de cours ce jour.</p>\n"
            : renderSchedule(entries);

        return "<!DOCTYPE html>\n"
            + "<html lang=\"fr\">\n"
            + "<head>\n"
            + "  <meta charset=\"UTF-8\">\n"
            + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
            + "  <title>Emploi du temps \u2014 " + titleDate + "</title>\n"
            + "  <style>" + CSS + "</style>\n"
            + "</head>\n"
            + "<body>\n"
            + "  <div class=\"page\">\n"
            + "    <nav class=\"nav\"><a class=\"nav__back\" href=\"index.html\">\u2190\u00a0Semaine</a></nav>\n"
            + "    <header class=\"day-header\">\n"
            + "      <h1 class=\"day-header__date\"><time datetime=\"" + date + "\">" + fullDate + "</time></h1>\n"
            + "    </header>\n"
            + "    " + schedule
            + "  </div>\n"
            + "</body>\n"
            + "</html>\n";
    }

    // -------------------------------------------------------------------------
    // Schedule rendering
    // -------------------------------------------------------------------------

    /**
     * Pairs an entry to render with an optional cancelled predecessor at the same time slot.
     * The {@code cancelledSibling} is non-null only when a cancelled entry at the same start time
     * has a <em>different</em> subject — indicating a true class replacement. Same-subject pairs
     * (e.g. "cours maintenu" resolutions) carry a null sibling since no annotation is needed.
     */
    record MergedEntry(TimetableEntry entry, TimetableEntry cancelledSibling) {}

    /**
     * Collapses time-slot conflicts into single {@link MergedEntry} objects.
     *
     * <p>When multiple entries share the same start time, Pronote typically sends:
     * <ul>
     *   <li>a CANCELLED entry (the original/old slot), and</li>
     *   <li>an active entry (the replacement or the maintained class).</li>
     * </ul>
     * This method hides cancelled entries whenever at least one active sibling exists at the
     * same start time. If the cancelled sibling has a different subject (real replacement),
     * it is carried as {@code cancelledSibling} for optional display in the card.
     * Standalone cancellations (no active sibling) are kept as-is.
     */
    static List<MergedEntry> collapseSlots(List<TimetableEntry> entries) {
        Map<LocalDateTime, List<TimetableEntry>> byStart = entries.stream()
            .collect(Collectors.groupingBy(TimetableEntry::getStartTime, LinkedHashMap::new, Collectors.toList()));

        List<MergedEntry> result = new ArrayList<>();
        for (List<TimetableEntry> group : byStart.values()) {
            List<TimetableEntry> active = group.stream()
                .filter(e -> e.getStatus() != EntryStatus.CANCELLED
                          && e.getStatus() != EntryStatus.EXEMPTED)
                .toList();
            List<TimetableEntry> cancelled = group.stream()
                .filter(e -> e.getStatus() == EntryStatus.CANCELLED
                          || e.getStatus() == EntryStatus.EXEMPTED)
                .toList();

            if (active.isEmpty()) {
                // Standalone cancellations — show as-is
                cancelled.forEach(e -> result.add(new MergedEntry(e, null)));
            } else {
                for (TimetableEntry a : active) {
                    // Attach a cancelled sibling only when subjects differ (real replacement).
                    // Same-subject pairs (cours maintenu) need no annotation.
                    TimetableEntry sibling = cancelled.stream()
                        .filter(c -> !Objects.equals(c.getSubject(), a.getSubject()))
                        .findFirst()
                        .orElse(null);
                    result.add(new MergedEntry(a, sibling));
                }
            }
        }
        return result;
    }

    private String renderSchedule(List<TimetableEntry> entries) {
        List<MergedEntry> sorted = collapseSlots(entries).stream()
            .sorted(Comparator.comparing(me -> me.entry().getStartTime()))
            .toList();

        StringBuilder sb = new StringBuilder("<main class=\"schedule\">\n");

        // Cursor tracks the next hour slot we should render.
        // Initialised to the first lesson's start hour.
        int cursor = sorted.get(0).entry().getStartTime().getHour();

        for (MergedEntry me : sorted) {
            int lessonHour = me.entry().getStartTime().getHour();

            // Fill any gap hours between cursor and this lesson
            while (cursor < lessonHour) {
                sb.append(renderGapSlot(cursor));
                cursor++;
            }

            sb.append(renderLessonSlot(me));

            // Advance cursor to the hour when this lesson ends (ceiling to whole hour).
            // Use max(cursor, endHour) — not cursor+1 — so that same-start-hour lessons
            // (e.g. two entries at 10:00) do not incorrectly skip the next gap hour.
            // The fallback to lessonHour+1 only triggers for zero-duration entries.
            int endHour = me.entry().getEndTime().getHour();
            if (me.entry().getEndTime().getMinute() > 0) endHour++;
            cursor = Math.max(cursor, endHour);
            if (cursor <= lessonHour) cursor = lessonHour + 1;
        }

        sb.append("    </main>\n");
        return sb.toString();
    }

    private String renderLessonSlot(MergedEntry me) {
        TimetableEntry e = me.entry();
        String color    = ACCENT_COLORS[Math.abs(e.getSubject().hashCode()) % ACCENT_COLORS.length];
        boolean cancelled = e.getStatus() == EntryStatus.CANCELLED
                         || e.getStatus() == EntryStatus.EXEMPTED;

        String cardClass   = cancelled ? "lesson lesson--cancelled" : "lesson";
        String borderStyle = cancelled ? "" : " style=\"border-left-color:" + color + "\"";

        StringBuilder card = new StringBuilder();
        card.append("      <div class=\"").append(cardClass).append("\"").append(borderStyle).append(">\n");

        // Subject
        String displaySubject = e.getEnrichedSubject() != null && !e.getEnrichedSubject().isBlank()
            ? e.getEnrichedSubject() : e.getSubject();
        card.append("        <div class=\"lesson__subject\">").append(esc(displaySubject)).append("</div>\n");

        // Teacher
        if (e.getTeacher() != null && !e.getTeacher().isBlank()) {
            card.append("        <div class=\"lesson__teacher\">").append(esc(e.getTeacher())).append("</div>\n");
        }

        // Room
        if (e.getRoom() != null && !e.getRoom().isBlank()) {
            card.append("        <div class=\"lesson__room\">").append(esc(e.getRoom())).append("</div>\n");
        }

        // Memo
        if (e.getMemo() != null && !e.getMemo().isBlank()) {
            card.append("        <div class=\"lesson__memo\">").append(esc(e.getMemo())).append("</div>\n");
        }

        // Status / test badges
        List<String> badges = buildBadges(e);
        if (!badges.isEmpty()) {
            card.append("        <div class=\"lesson__footer\">\n");
            for (String b : badges) card.append("          ").append(b).append("\n");
            card.append("        </div>\n");
        }

        // "Replaces" note — only shown when a different-subject cancelled entry was collapsed
        TimetableEntry sibling = me.cancelledSibling();
        if (sibling != null) {
            String siblingSubject = sibling.getEnrichedSubject() != null && !sibling.getEnrichedSubject().isBlank()
                ? sibling.getEnrichedSubject() : sibling.getSubject();
            StringBuilder detail = new StringBuilder(esc(siblingSubject));
            if (sibling.getTeacher() != null && !sibling.getTeacher().isBlank()) {
                detail.append(" \u00b7 ").append(esc(sibling.getTeacher()));
            }
            if (sibling.getRoom() != null && !sibling.getRoom().isBlank()) {
                detail.append(" \u00b7 ").append(esc(sibling.getRoom()));
            }
            card.append("        <div class=\"lesson__replaced\">\n");
            card.append("          <span class=\"lesson__replaced-label\">Remplace\u00a0:</span>\n");
            card.append("          <span class=\"lesson__replaced-detail\">").append(detail).append("</span>\n");
            card.append("        </div>\n");
        }

        card.append("      </div>\n");

        return "    <div class=\"slot\">\n"
             + "      <span class=\"slot__time\">" + timeTag(e.getStartTime().toLocalTime()) + "</span>\n"
             + card
             + "    </div>\n";
    }

    private String renderGapSlot(int hour) {
        String timeHtml = timeTag(LocalTime.of(hour, 0));
        if (hour == 12) {
            return "    <div class=\"slot slot--lunch\">\n"
                 + "      <span class=\"slot__time\">" + timeHtml + "</span>\n"
                 + "      <div class=\"slot__content\">"
                 + "<span class=\"lunch-icon\" aria-hidden=\"true\">\uD83C\uDF7D</span>"
                 + "\u00a0Pause d\u00e9jeuner"
                 + "</div>\n"
                 + "    </div>\n";
        }
        return "    <div class=\"slot slot--empty\">\n"
             + "      <span class=\"slot__time\">" + timeHtml + "</span>\n"
             + "      <div class=\"slot__content\">Pas de cours</div>\n"
             + "    </div>\n";
    }

    // -------------------------------------------------------------------------
    // Badge helpers
    // -------------------------------------------------------------------------

    private static List<String> buildBadges(TimetableEntry e) {
        List<String> badges = new ArrayList<>();
        switch (e.getStatus()) {
            case CANCELLED -> {
                String label = e.getStatusLabel() != null ? e.getStatusLabel() : "Cours annul\u00e9";
                badges.add(badge("cancel", label));
            }
            case MODIFIED -> {
                String label = e.getStatusLabel() != null ? e.getStatusLabel() : "Cours modifi\u00e9";
                badges.add(badge("modify", label));
            }
            case EXEMPTED -> badges.add(badge("exempt", "Dispens\u00e9"));
            case NORMAL -> {
                if (e.getStatusLabel() != null && !e.getStatusLabel().isBlank()) {
                    badges.add(badge("modify", e.getStatusLabel()));
                }
            }
        }
        if (e.isTest()) badges.add(badge("test", "\u00c9valuation"));
        return badges;
    }

    private static String badge(String type, String text) {
        return "<span class=\"badge badge--" + type + "\">" + esc(text) + "</span>";
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /** Wraps a local time in a semantic {@code <time>} element with a French display label. */
    private static String timeTag(LocalTime t) {
        String datetime = String.format("%02d:%02d", t.getHour(), t.getMinute());
        String display  = t.getHour() + "h" + String.format("%02d", t.getMinute());
        return "<time datetime=\"" + datetime + "\">" + display + "</time>";
    }

    /** Returns the accent color for the given subject (deterministic). */
    static String accentColor(String subject) {
        return ACCENT_COLORS[Math.abs(subject.hashCode()) % ACCENT_COLORS.length];
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // -------------------------------------------------------------------------
    // Embedded CSS (shared by day pages and the index)
    // -------------------------------------------------------------------------

    static final String CSS = """

        /* ================================================================
           Emploi du temps — embedded stylesheet
           Light/dark via prefers-color-scheme · No JavaScript · Responsive
           ================================================================ */

        /* ----- Custom properties ----- */
        :root {
          --bg:          #f1f3f6;
          --surface:     #ffffff;
          --text-1:      #0f172a;
          --text-2:      #64748b;
          --text-3:      #94a3b8;
          --border:      #e2e8f0;

          --bdg-cancel-bg: #fee2e2; --bdg-cancel-fg: #b91c1c;
          --bdg-modify-bg: #fef9c3; --bdg-modify-fg: #854d0e;
          --bdg-test-bg:   #dbeafe; --bdg-test-fg:   #1e40af;
          --bdg-exempt-bg: #f3e8ff; --bdg-exempt-fg: #6b21a8;
        }

        @media (prefers-color-scheme: dark) {
          :root {
            --bg:      #0c0d13;
            --surface: #14151f;
            --text-1:  #e2e8f0;
            --text-2:  #7c84a0;
            --text-3:  #4a5068;
            --border:  #1e2030;

            --bdg-cancel-bg: #3f0a0a; --bdg-cancel-fg: #fca5a5;
            --bdg-modify-bg: #3d2509; --bdg-modify-fg: #fcd34d;
            --bdg-test-bg:   #0c1f40; --bdg-test-fg:   #93c5fd;
            --bdg-exempt-bg: #2d0a5e; --bdg-exempt-fg: #d8b4fe;
          }
        }

        /* ----- Reset ----- */
        *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

        /* ----- Base ----- */
        html {
          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
                       Helvetica, Arial, sans-serif;
          font-size: 16px;
          line-height: 1.5;
          -webkit-text-size-adjust: 100%;
          background: var(--bg);
          color: var(--text-1);
        }

        body { padding: 1rem 1rem 4rem; }

        .page {
          max-width: 480px;
          margin: 0 auto;
        }

        /* ----- Navigation back link ----- */
        .nav { margin-bottom: 0.5rem; }

        .nav__back {
          display: inline-flex;
          align-items: center;
          font-size: 0.875rem;
          color: var(--text-2);
          text-decoration: none;
        }
        .nav__back:hover { color: var(--text-1); }

        /* ----- Day header ----- */
        .day-header { padding: 0.5rem 0 1.25rem; }

        .day-header__date {
          font-size: 1.5rem;
          font-weight: 700;
          letter-spacing: -0.025em;
          line-height: 1.2;
        }

        /* ----- Timeline ----- */
        .schedule { display: flex; flex-direction: column; }

        .slot {
          display: grid;
          grid-template-columns: 2.75rem 1fr;
          column-gap: 0.625rem;
          align-items: start;
        }

        .slot__time {
          padding-top: 1rem;
          font-size: 0.6875rem;
          font-weight: 600;
          color: var(--text-3);
          letter-spacing: 0.04em;
          text-align: right;
          white-space: nowrap;
        }

        /* ----- Lesson card ----- */
        .lesson {
          margin: 0.3125rem 0;
          padding: 0.75rem 0.875rem;
          background: var(--surface);
          border-radius: 10px;
          border-left: 4px solid var(--border);
          box-shadow: 0 1px 3px rgba(0, 0, 0, .07);
          display: flex;
          flex-direction: column;
          gap: 0.2rem;
          min-width: 0;
        }

        .lesson--cancelled {
          background: var(--bg);
          box-shadow: none;
          border-left-color: var(--border) !important;
          opacity: .65;
        }

        .lesson__subject {
          font-size: 0.9375rem;
          font-weight: 700;
          letter-spacing: -0.015em;
          line-height: 1.3;
        }

        .lesson--cancelled .lesson__subject {
          text-decoration: line-through;
          color: var(--text-3);
        }

        .lesson__teacher,
        .lesson__room {
          font-size: 0.8125rem;
          color: var(--text-2);
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }

        .lesson__memo {
          font-size: 0.75rem;
          color: var(--text-2);
          font-style: italic;
          margin-top: 0.125rem;
        }

        .lesson__footer {
          display: flex;
          flex-wrap: wrap;
          gap: 0.3rem;
          margin-top: 0.25rem;
        }

        /* ----- Badges ----- */
        .badge {
          display: inline-flex;
          align-items: center;
          padding: 0.125rem 0.4375rem;
          border-radius: 999px;
          font-size: 0.6875rem;
          font-weight: 600;
          letter-spacing: 0.025em;
          white-space: nowrap;
        }

        .badge--cancel { background: var(--bdg-cancel-bg); color: var(--bdg-cancel-fg); }
        .badge--modify { background: var(--bdg-modify-bg); color: var(--bdg-modify-fg); }
        .badge--test   { background: var(--bdg-test-bg);   color: var(--bdg-test-fg); }
        .badge--exempt { background: var(--bdg-exempt-bg); color: var(--bdg-exempt-fg); }

        /* ----- Gap and lunch slots ----- */
        .slot__content {
          display: flex;
          align-items: center;
          gap: 0.4rem;
          min-height: 2.5rem;
          padding: 0 0.25rem;
          font-size: 0.8125rem;
          color: var(--text-3);
          font-style: italic;
        }

        .lunch-icon { font-size: 0.9375rem; line-height: 1; }

        /* ----- Empty day ----- */
        .empty-day {
          text-align: center;
          padding: 4rem 1rem;
          color: var(--text-3);
          font-size: 0.9375rem;
        }

        /* ----- Index: header ----- */
        .index-header { padding: 1.5rem 0 1rem; }

        .index-header__title {
          font-size: 1.5rem;
          font-weight: 700;
          letter-spacing: -0.025em;
        }

        .index-header__subtitle {
          font-size: 0.875rem;
          color: var(--text-2);
          margin-top: 0.25rem;
        }

        /* ----- Index: day cards grid ----- */
        .days-grid {
          display: grid;
          grid-template-columns: repeat(auto-fill, minmax(13rem, 1fr));
          gap: 0.625rem;
        }

        .day-card {
          display: block;
          padding: 1rem 1.125rem;
          background: var(--surface);
          border-radius: 12px;
          border: 1px solid var(--border);
          text-decoration: none;
          color: inherit;
          box-shadow: 0 1px 2px rgba(0, 0, 0, .05);
          transition: box-shadow .15s ease, border-color .15s ease;
        }

        .day-card:hover {
          box-shadow: 0 4px 14px rgba(0, 0, 0, .12);
          border-color: transparent;
        }

        .day-card--empty { opacity: .5; }

        .day-card__dow {
          font-size: 0.6875rem;
          font-weight: 700;
          letter-spacing: .08em;
          text-transform: uppercase;
          color: var(--text-2);
          margin-bottom: 0.3rem;
        }

        .day-card__date {
          font-size: 1.0625rem;
          font-weight: 700;
          letter-spacing: -0.02em;
          margin-bottom: 0.4rem;
        }

        .day-card__meta {
          font-size: 0.8125rem;
          color: var(--text-2);
          line-height: 1.5;
        }

        .day-card__alert {
          display: inline-flex;
          align-items: center;
          gap: 0.2rem;
          margin-top: 0.4rem;
          font-size: 0.75rem;
          font-weight: 600;
          color: var(--bdg-cancel-fg);
        }

        /* ----- Replaced-class note (class replacement inside a lesson card) ----- */
        .lesson__replaced {
          display: flex;
          align-items: baseline;
          flex-wrap: wrap;
          gap: 0.25rem;
          margin-top: 0.375rem;
          padding-top: 0.375rem;
          border-top: 1px solid var(--border);
          font-size: 0.725rem;
          color: var(--text-3);
        }

        .lesson__replaced-label {
          font-weight: 600;
          flex-shrink: 0;
        }

        .lesson__replaced-detail {
          text-decoration: line-through;
          text-decoration-color: var(--text-3);
        }

        /* ----- Footer ----- */
        .page-footer {
          margin-top: 2.5rem;
          font-size: 0.75rem;
          color: var(--text-3);
          text-align: center;
        }
        """;
}
