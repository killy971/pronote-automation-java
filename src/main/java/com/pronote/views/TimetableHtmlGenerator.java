package com.pronote.views;

import com.pronote.domain.Assignment;
import com.pronote.domain.EntryStatus;
import com.pronote.domain.TimetableEntry;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
     * @param date                  the date to render
     * @param entries               all timetable entries for this specific day (may be empty)
     * @param assignmentsBySubject  assignments due this day, keyed by display-subject name;
     *                              empty map means no assignment chips will be rendered
     * @return a complete, self-contained HTML5 document
     */
    public String generate(LocalDate date, List<TimetableEntry> entries,
                           Map<String, List<Assignment>> assignmentsBySubject) {
        String fullDate  = capitalize(date.format(FULL_DATE_FMT));
        String titleDate = capitalize(date.format(SHORT_DATE_FMT));
        String schedule  = entries.isEmpty()
            ? "<p class=\"empty-day\">Pas de cours ce jour.</p>\n"
            : renderSchedule(entries, assignmentsBySubject);

        boolean hasChips = !assignmentsBySubject.isEmpty();

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n")
          .append("<html lang=\"fr\">\n")
          .append("<head>\n")
          .append("  <meta charset=\"UTF-8\">\n")
          .append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
          .append("  <title>Emploi du temps \u2014 ").append(titleDate).append("</title>\n")
          .append("  <style>").append(CSS).append("</style>\n")
          .append("</head>\n")
          .append("<body>\n")
          .append("  <div class=\"page\">\n")
          .append("    <nav class=\"nav\"><a class=\"nav__back\" href=\"index.html\">\u2190\u00a0Semaine</a></nav>\n")
          .append("    <header class=\"day-header\">\n")
          .append("      <h1 class=\"day-header__date\"><time datetime=\"").append(date).append("\">").append(fullDate).append("</time></h1>\n")
          .append("    </header>\n")
          .append("    ").append(schedule)
          .append("  </div>\n");

        if (hasChips) {
            sb.append("\n")
              .append("  <dialog id=\"assign-dialog\" aria-modal=\"true\" aria-label=\"Devoirs\">\n")
              .append("    <div class=\"dialog-inner\">\n")
              .append("      <button class=\"dialog__close\" id=\"assign-dialog-close\" aria-label=\"Fermer\">\u00d7</button>\n")
              .append("      <div id=\"assign-dialog-body\"></div>\n")
              .append("    </div>\n")
              .append("  </dialog>\n")
              .append("\n")
              .append("  <script>").append(ASSIGN_JS).append("</script>\n");
        }

        sb.append("</body>\n")
          .append("</html>\n");

        return sb.toString();
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

    private String renderSchedule(List<TimetableEntry> entries,
                                   Map<String, List<Assignment>> assignmentsBySubject) {
        List<MergedEntry> sorted = collapseSlots(entries).stream()
            .sorted(Comparator.comparing(me -> me.entry().getStartTime()))
            .toList();

        StringBuilder sb = new StringBuilder("<main class=\"schedule\">\n");

        // Cursor tracks the next hour slot we should render.
        // Initialised to the first lesson's start hour.
        int cursor = sorted.get(0).entry().getStartTime().getHour();

        // Track which subjects have already received an assignment chip so we only
        // show the chip on the first occurrence of each subject within the day.
        Set<String> seenSubjects = new HashSet<>();

        for (MergedEntry me : sorted) {
            int lessonHour = me.entry().getStartTime().getHour();

            // Fill any gap hours between cursor and this lesson
            while (cursor < lessonHour) {
                sb.append(renderGapSlot(cursor));
                cursor++;
            }

            // First occurrence of this subject gets the assignment chip; subsequent ones don't.
            String subject = displaySubject(me.entry());
            List<Assignment> subjectAssignments = seenSubjects.add(subject)
                ? assignmentsBySubject.getOrDefault(subject, List.of())
                : null;

            sb.append(renderLessonSlot(me, subjectAssignments));

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

    /**
     * @param subjectAssignments assignments due today for this subject — non-null only for the
     *                           first occurrence of the subject within the day; null means no chip.
     *                           An empty (non-null) list means first occurrence but no assignments.
     */
    private String renderLessonSlot(MergedEntry me, List<Assignment> subjectAssignments) {
        TimetableEntry e = me.entry();
        String color    = ACCENT_COLORS[Math.abs(e.getSubject().hashCode()) % ACCENT_COLORS.length];
        boolean cancelled = e.getStatus() == EntryStatus.CANCELLED
                         || e.getStatus() == EntryStatus.EXEMPTED;

        String cardClass   = cancelled ? "lesson lesson--cancelled" : "lesson";
        String borderStyle = cancelled ? "" : " style=\"border-left-color:" + color + "\"";

        boolean showChip = subjectAssignments != null && !subjectAssignments.isEmpty();
        String subjectLabel = displaySubject(e);

        StringBuilder card = new StringBuilder();
        card.append("      <div class=\"").append(cardClass).append("\"").append(borderStyle).append(">\n");

        // Subject — wrapped with assignment chip when present
        if (showChip) {
            card.append("        <div class=\"lesson__head\">\n");
            card.append("          <div class=\"lesson__subject\">").append(esc(subjectLabel)).append("</div>\n");
            card.append(renderAssignChip(subjectLabel, subjectAssignments));
            card.append("        </div>\n");
        } else {
            card.append("        <div class=\"lesson__subject\">").append(esc(subjectLabel)).append("</div>\n");
        }

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
            String siblingSubject = displaySubject(sibling);
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

    // -------------------------------------------------------------------------
    // Assignment chip
    // -------------------------------------------------------------------------

    /** Renders the chip button and its hidden detail panel (cloned into dialog on click). */
    private String renderAssignChip(String displaySubject, List<Assignment> assignments) {
        int count = assignments.size();
        String label = count + "\u00a0devoir" + (count > 1 ? "s" : "");
        String ariaLabel = label + "\u00a0\u2014\u00a0" + esc(displaySubject);
        return "          <div class=\"lesson__assign-wrap\">\n"
             + "            <button class=\"lesson__assign-chip\" aria-label=\"" + ariaLabel + "\">"
             + count + "</button>\n"
             + renderAssignDetail(displaySubject, assignments)
             + "          </div>\n";
    }

    /** Hidden panel whose content is cloned into the dialog when the chip is clicked. */
    private String renderAssignDetail(String displaySubject, List<Assignment> assignments) {
        int count = assignments.size();
        String title = count + "\u00a0devoir" + (count > 1 ? "s" : "")
                     + "\u00a0\u00b7\u00a0" + esc(displaySubject);
        StringBuilder sb = new StringBuilder();
        sb.append("            <div class=\"lesson__assign-detail\" hidden>\n");
        sb.append("              <div class=\"assign-popup__title\">").append(title).append("</div>\n");
        for (Assignment a : assignments) {
            boolean done = a.isDone();
            sb.append("              <div class=\"assign-popup__item")
              .append(done ? " assign-popup__item--done" : "")
              .append("\">\n");
            String desc = a.getDescription();
            if (desc != null && !desc.isBlank()) {
                sb.append("                <p class=\"assign-popup__desc\">").append(esc(desc)).append("</p>\n");
            } else {
                sb.append("                <p class=\"assign-popup__desc assign-popup__desc--empty\">(aucune description)</p>\n");
            }
            sb.append("              </div>\n");
        }
        sb.append("            </div>\n");
        return sb.toString();
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

    /** Returns the display name for a timetable entry: enrichedSubject if set, else subject. */
    private static String displaySubject(TimetableEntry e) {
        String enriched = e.getEnrichedSubject();
        return (enriched != null && !enriched.isBlank()) ? enriched : e.getSubject();
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
    // Assignment dialog JS
    // -------------------------------------------------------------------------

    static final String ASSIGN_JS = """
        (function () {
          var dialog   = document.getElementById('assign-dialog');
          var body     = document.getElementById('assign-dialog-body');
          var closeBtn = document.getElementById('assign-dialog-close');

          document.querySelectorAll('.lesson__assign-chip').forEach(function (chip) {
            chip.addEventListener('click', function (e) {
              e.stopPropagation();
              var detail = chip.parentElement.querySelector('.lesson__assign-detail');
              if (!detail) return;
              body.innerHTML = '';
              var clone = detail.cloneNode(true);
              clone.removeAttribute('hidden');
              body.appendChild(clone);
              dialog.showModal();
            });
            chip.addEventListener('keydown', function (e) {
              if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); chip.click(); }
            });
          });

          closeBtn.addEventListener('click', function () { dialog.close(); });

          dialog.addEventListener('click', function (e) {
            if (e.target === dialog) dialog.close();
          });
        })();
        """;

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

        /* ----- Lesson head (subject + assignment chip row) ----- */
        .lesson__head {
          display: flex;
          align-items: flex-start;
          justify-content: space-between;
          gap: 0.5rem;
          min-width: 0;
        }

        .lesson__head .lesson__subject {
          flex: 1;
          min-width: 0;
        }

        .lesson__assign-wrap {
          flex-shrink: 0;
        }

        .lesson__assign-chip {
          display: inline-flex;
          align-items: center;
          padding: 0.125rem 0.4375rem;
          border-radius: 999px;
          font-size: 0.625rem;
          font-weight: 700;
          letter-spacing: 0.03em;
          line-height: 1.4;
          white-space: nowrap;
          border: 1px solid var(--border);
          background: var(--bg);
          color: var(--text-2);
          cursor: pointer;
        }

        .lesson__assign-chip:hover {
          color: var(--text-1);
        }

        /* ----- Assignment popup content ----- */
        .assign-popup__title {
          font-size: 0.75rem;
          font-weight: 700;
          color: var(--text-2);
          letter-spacing: 0.05em;
          text-transform: uppercase;
          padding: 1rem 1rem 0.625rem;
          border-bottom: 1px solid var(--border);
        }

        .assign-popup__item {
          padding: 0.625rem 1rem;
          border-bottom: 1px solid var(--border);
        }

        .assign-popup__item:last-child {
          border-bottom: none;
          padding-bottom: 0.75rem;
        }

        .assign-popup__desc {
          font-size: 0.875rem;
          color: var(--text-1);
          line-height: 1.5;
          white-space: pre-wrap;
          word-break: break-word;
        }

        .assign-popup__item--done .assign-popup__desc {
          text-decoration: line-through;
          color: var(--text-3);
          opacity: 0.7;
        }

        .assign-popup__desc--empty {
          font-style: italic;
          color: var(--text-3);
        }

        /* ----- Dialog / overlay ----- */
        dialog {
          border: none;
          padding: 0;
          background: var(--surface);
          border-radius: 16px;
          width: min(400px, 92vw);
          max-height: 80vh;
          overflow: hidden;
          box-shadow: 0 8px 40px rgba(0, 0, 0, .25);
          animation: dialog-in 0.2s ease;
        }

        dialog::backdrop {
          background: rgba(0, 0, 0, .45);
          animation: backdrop-in 0.2s ease;
        }

        @keyframes dialog-in {
          from { opacity: 0; transform: translateY(12px) scale(0.97); }
          to   { opacity: 1; transform: translateY(0)    scale(1); }
        }

        @keyframes backdrop-in {
          from { opacity: 0; }
          to   { opacity: 1; }
        }

        .dialog-inner {
          position: relative;
          overflow-y: auto;
          max-height: 80vh;
          padding-bottom: 0.5rem;
        }

        .dialog__close {
          position: sticky;
          top: 0.75rem;
          float: right;
          margin: 0.75rem 0.75rem 0 0;
          width: 1.75rem;
          height: 1.75rem;
          border-radius: 50%;
          border: none;
          background: var(--border);
          color: var(--text-2);
          font-size: 1rem;
          line-height: 1;
          cursor: pointer;
          display: inline-flex;
          align-items: center;
          justify-content: center;
          flex-shrink: 0;
        }

        .dialog__close:hover {
          background: var(--text-3);
          color: var(--text-1);
        }
        """;
}
