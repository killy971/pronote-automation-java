package com.pronote.views;

import com.pronote.domain.SchoolLifeEvent;
import com.pronote.domain.SchoolLifeEvent.EventType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Generates a self-contained HTML5 page listing the most recent school-life events.
 *
 * <p>Events are sorted by date descending (newest first) and capped at {@code maxEntries}.
 * Each card displays a colour-coded type badge and type-specific detail lines.
 *
 * <p>Event type → badge:
 * <ul>
 *   <li>ABSENCE     → red "Absence"</li>
 *   <li>DELAY       → orange "Retard"</li>
 *   <li>INFIRMARY   → blue "Infirmerie"</li>
 *   <li>PUNISHMENT  → purple "Punition"</li>
 *   <li>OBSERVATION → yellow "Observation"</li>
 *   <li>OTHER       → grey "Autre"</li>
 * </ul>
 */
public class SchoolLifeHtmlGenerator {

    private static final DateTimeFormatter EVENT_DATE_FMT =
        DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH);
    private static final DateTimeFormatter DATETIME_FMT =
        DateTimeFormatter.ofPattern("d MMMM yyyy '\u00e0' HH'h'mm", Locale.FRENCH);

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates the full HTML document.
     *
     * @param events     all school-life events (sorted + limited internally)
     * @param maxEntries maximum number of events to display (newest first)
     * @return a complete, self-contained HTML5 document
     */
    public String generate(List<SchoolLifeEvent> events, int maxEntries) {
        List<SchoolLifeEvent> sorted = events.stream()
            .filter(e -> e.getDate() != null)
            .sorted(Comparator.comparing(SchoolLifeEvent::getDate).reversed()
                .thenComparing(e -> e.getTime() != null ? e.getTime() : ""))
            .limit(maxEntries)
            .toList();

        String content = sorted.isEmpty()
            ? "      <p class=\"empty-state\">Aucun \u00e9v\u00e9nement \u00e0 afficher.</p>\n"
            : renderList(sorted);

        String generatedAt = capitalize(LocalDateTime.now().format(DATETIME_FMT));

        return "<!DOCTYPE html>\n"
            + "<html lang=\"fr\">\n"
            + "<head>\n"
            + "  <meta charset=\"UTF-8\">\n"
            + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
            + "  <title>Vie scolaire</title>\n"
            + "  <style>" + CSS + "</style>\n"
            + "</head>\n"
            + "<body>\n"
            + "  <div class=\"page\">\n"
            + "    <nav class=\"nav\"><a class=\"nav__back\" href=\"../index.html\">\u2190\u00a0Pronote</a></nav>\n"
            + "    <header class=\"page-header\">\n"
            + "      <h1 class=\"page-header__title\">Vie scolaire</h1>\n"
            + "      <p class=\"page-header__subtitle\">Mis \u00e0 jour le " + generatedAt + "</p>\n"
            + "    </header>\n"
            + "    <main class=\"event-list\">\n"
            + content
            + "    </main>\n"
            + "  </div>\n"
            + "</body>\n"
            + "</html>\n";
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    private String renderList(List<SchoolLifeEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (SchoolLifeEvent event : events) {
            sb.append(renderCard(event));
        }
        return sb.toString();
    }

    private String renderCard(SchoolLifeEvent event) {
        StringBuilder card = new StringBuilder();
        card.append("      <div class=\"event-card\">\n");
        card.append(renderHeader(event));
        card.append(renderDetails(event));
        card.append("      </div>\n");
        return card.toString();
    }

    private String renderHeader(SchoolLifeEvent event) {
        String typeBadge = typeBadge(event.getType());
        String dateStr   = formatDate(event);

        StringBuilder hdr = new StringBuilder();
        hdr.append("        <div class=\"event-card__header\">\n");
        hdr.append("          <div class=\"event-card__meta\">\n");
        hdr.append("            ").append(typeBadge).append("\n");

        // Justified/unjustified badge for absences and delays
        if (event.getType() == EventType.ABSENCE || event.getType() == EventType.DELAY) {
            if (event.isJustified()) {
                hdr.append("            <span class=\"badge badge--justified\">Justifi\u00e9e</span>\n");
            } else {
                hdr.append("            <span class=\"badge badge--unjustified\">Non justifi\u00e9e</span>\n");
            }
        }

        hdr.append("          </div>\n");
        if (dateStr != null) {
            hdr.append("          <time class=\"event-card__date\">").append(esc(dateStr)).append("</time>\n");
        }
        hdr.append("        </div>\n");
        return hdr.toString();
    }

    private String renderDetails(SchoolLifeEvent event) {
        List<String> lines = new ArrayList<>();

        switch (event.getType()) {
            case ABSENCE -> {
                // Date range
                if (event.getEndDate() != null && !event.getEndDate().equals(event.getDate())) {
                    lines.add("Du " + event.getDate().format(EVENT_DATE_FMT)
                        + " au " + event.getEndDate().format(EVENT_DATE_FMT));
                }
                if (event.getMinutes() > 0) {
                    lines.add(formatDuration(event.getMinutes()));
                }
                if (event.getReasons() != null && !event.getReasons().isBlank()) {
                    lines.add("Motif\u00a0: " + event.getReasons());
                }
                if (event.getCircumstances() != null && !event.getCircumstances().isBlank()) {
                    lines.add(event.getCircumstances());
                }
            }
            case DELAY -> {
                if (event.getMinutes() > 0) {
                    lines.add(formatDuration(event.getMinutes()) + " de retard");
                }
                if (event.getReasons() != null && !event.getReasons().isBlank()) {
                    lines.add("Motif\u00a0: " + event.getReasons());
                }
            }
            case INFIRMARY -> {
                // Time range
                if (event.getTime() != null && !event.getTime().isBlank()) {
                    String end = "";
                    if (event.getEndDate() != null) {
                        if (!event.getEndDate().equals(event.getDate())) {
                            end = " \u2192 " + event.getEndDate().format(EVENT_DATE_FMT);
                        }
                    }
                    lines.add(formatTime(event.getTime()) + end);
                }
                if (event.getReasons() != null && !event.getReasons().isBlank()) {
                    lines.add("Actes\u00a0: " + event.getReasons());
                }
                if (event.getCircumstances() != null && !event.getCircumstances().isBlank()) {
                    lines.add("Sympt\u00f4mes\u00a0: " + event.getCircumstances());
                }
            }
            case PUNISHMENT -> {
                List<String> parts = new ArrayList<>();
                if (event.getNature() != null && !event.getNature().isBlank()) {
                    parts.add(event.getNature());
                }
                if (event.getMinutes() > 0) {
                    parts.add(formatDuration(event.getMinutes()));
                }
                if (!parts.isEmpty()) {
                    lines.add(String.join(" \u00b7 ", parts));
                }
                if (event.getGiver() != null && !event.getGiver().isBlank()) {
                    lines.add("Par " + event.getGiver());
                }
                if (event.getReasons() != null && !event.getReasons().isBlank()) {
                    lines.add("Motif\u00a0: " + event.getReasons());
                }
                if (event.getCircumstances() != null && !event.getCircumstances().isBlank()) {
                    lines.add(event.getCircumstances());
                }
            }
            case OBSERVATION -> {
                List<String> parts = new ArrayList<>();
                if (event.getLabel() != null && !event.getLabel().isBlank()) {
                    parts.add(event.getLabel());
                }
                if (event.getSubject() != null && !event.getSubject().isBlank()) {
                    String subj = event.getEnrichedSubject() != null && !event.getEnrichedSubject().isBlank()
                        ? event.getEnrichedSubject() : event.getSubject();
                    parts.add(subj);
                }
                if (!parts.isEmpty()) {
                    lines.add(String.join(" \u00b7 ", parts));
                }
                if (event.getGiver() != null && !event.getGiver().isBlank()) {
                    lines.add("Par " + event.getGiver());
                }
                if (event.getReasons() != null && !event.getReasons().isBlank()) {
                    lines.add("Motif\u00a0: " + event.getReasons());
                }
                if (event.getCircumstances() != null && !event.getCircumstances().isBlank()) {
                    lines.add(event.getCircumstances());
                }
            }
            case OTHER -> {
                if (event.getReasons() != null && !event.getReasons().isBlank()) {
                    lines.add(event.getReasons());
                }
                if (event.getCircumstances() != null && !event.getCircumstances().isBlank()) {
                    lines.add(event.getCircumstances());
                }
            }
        }

        if (lines.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("        <div class=\"event-card__details\">\n");
        for (String line : lines) {
            sb.append("          <div class=\"event-card__detail\">").append(esc(line)).append("</div>\n");
        }
        sb.append("        </div>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Badge helpers
    // -------------------------------------------------------------------------

    private static String typeBadge(EventType type) {
        if (type == null) return badge("other", "Autre");
        return switch (type) {
            case ABSENCE     -> badge("absence",     "Absence");
            case DELAY       -> badge("delay",       "Retard");
            case INFIRMARY   -> badge("infirmary",   "Infirmerie");
            case PUNISHMENT  -> badge("punishment",  "Punition");
            case OBSERVATION -> badge("observation", "Observation");
            case OTHER       -> badge("other",       "Autre");
        };
    }

    private static String badge(String type, String text) {
        return "<span class=\"badge badge--" + type + "\">" + esc(text) + "</span>";
    }

    // -------------------------------------------------------------------------
    // Date/time formatting
    // -------------------------------------------------------------------------

    /** Returns a short date+time string for the event header (e.g. "lun. 8 avr. à 8h15"). */
    private static String formatDate(SchoolLifeEvent event) {
        if (event.getDate() == null) return null;
        String dateStr = event.getDate().format(EVENT_DATE_FMT);
        if (event.getTime() != null && !event.getTime().isBlank()) {
            dateStr += " \u00e0 " + formatTime(event.getTime());
        }
        return capitalize(dateStr);
    }

    /** Converts "HH:mm" to "Hhmm" display format, e.g. "08:15" → "8h15". */
    private static String formatTime(String time) {
        if (time == null || time.isBlank()) return "";
        try {
            String[] parts = time.split(":");
            int h = Integer.parseInt(parts[0]);
            String m = parts.length > 1 ? String.format("%02d", Integer.parseInt(parts[1])) : "00";
            return h + "h" + m;
        } catch (NumberFormatException e) {
            return time.replace(":", "h");
        }
    }

    /** Formats a duration in minutes to a human-readable string. */
    private static String formatDuration(int minutes) {
        if (minutes < 60) return minutes + "\u00a0min";
        int h = minutes / 60;
        int m = minutes % 60;
        return m == 0 ? h + "\u00a0h" : h + "h" + String.format("%02d", m);
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

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
    // Embedded CSS
    // -------------------------------------------------------------------------

    static final String CSS = """

        /* ================================================================
           Vie scolaire — embedded stylesheet
           Light/dark via prefers-color-scheme · No JavaScript · Responsive
           ================================================================ */

        /* ----- Custom properties ----- */
        :root {
          --bg:      #f1f3f6;
          --surface: #ffffff;
          --text-1:  #0f172a;
          --text-2:  #64748b;
          --text-3:  #94a3b8;
          --border:  #e2e8f0;

          --bdg-absence-bg:      #fee2e2; --bdg-absence-fg:      #b91c1c;
          --bdg-delay-bg:        #ffedd5; --bdg-delay-fg:        #c2410c;
          --bdg-infirmary-bg:    #dbeafe; --bdg-infirmary-fg:    #1e40af;
          --bdg-punishment-bg:   #f3e8ff; --bdg-punishment-fg:   #6b21a8;
          --bdg-observation-bg:  #fef9c3; --bdg-observation-fg:  #854d0e;
          --bdg-other-bg:        #f1f5f9; --bdg-other-fg:        #475569;
          --bdg-justified-bg:    #dcfce7; --bdg-justified-fg:    #15803d;
          --bdg-unjustified-bg:  #fee2e2; --bdg-unjustified-fg:  #b91c1c;
        }

        @media (prefers-color-scheme: dark) {
          :root {
            --bg:      #0c0d13;
            --surface: #14151f;
            --text-1:  #e2e8f0;
            --text-2:  #7c84a0;
            --text-3:  #4a5068;
            --border:  #1e2030;

            --bdg-absence-bg:      #3f0a0a; --bdg-absence-fg:      #fca5a5;
            --bdg-delay-bg:        #3c1a05; --bdg-delay-fg:        #fdba74;
            --bdg-infirmary-bg:    #0c1f40; --bdg-infirmary-fg:    #93c5fd;
            --bdg-punishment-bg:   #2d0a5e; --bdg-punishment-fg:   #d8b4fe;
            --bdg-observation-bg:  #3d2509; --bdg-observation-fg:  #fcd34d;
            --bdg-other-bg:        #1e2030; --bdg-other-fg:        #94a3b8;
            --bdg-justified-bg:    #052e16; --bdg-justified-fg:    #4ade80;
            --bdg-unjustified-bg:  #3f0a0a; --bdg-unjustified-fg:  #fca5a5;
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
          max-width: 640px;
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

        /* ----- Page header ----- */
        .page-header { padding: 1.5rem 0 1.25rem; }

        .page-header__title {
          font-size: 1.5rem;
          font-weight: 700;
          letter-spacing: -0.025em;
        }

        .page-header__subtitle {
          font-size: 0.875rem;
          color: var(--text-2);
          margin-top: 0.25rem;
        }

        /* ----- Event list ----- */
        .event-list {
          display: flex;
          flex-direction: column;
          gap: 0.5rem;
        }

        /* ----- Event card ----- */
        .event-card {
          padding: 0.75rem 0.875rem;
          background: var(--surface);
          border-radius: 10px;
          border: 1px solid var(--border);
          box-shadow: 0 1px 3px rgba(0, 0, 0, .07);
          display: flex;
          flex-direction: column;
          gap: 0.35rem;
          min-width: 0;
        }

        .event-card__header {
          display: flex;
          align-items: center;
          justify-content: space-between;
          gap: 0.5rem;
          flex-wrap: wrap;
        }

        .event-card__meta {
          display: flex;
          align-items: center;
          gap: 0.35rem;
          flex-wrap: wrap;
        }

        .event-card__date {
          font-size: 0.8125rem;
          color: var(--text-2);
          white-space: nowrap;
          margin-left: auto;
          flex-shrink: 0;
        }

        .event-card__details {
          display: flex;
          flex-direction: column;
          gap: 0.15rem;
        }

        .event-card__detail {
          font-size: 0.8125rem;
          color: var(--text-2);
          line-height: 1.5;
        }

        /* ----- Badges ----- */
        .badge {
          display: inline-flex;
          align-items: center;
          padding: 0.125rem 0.4375rem;
          border-radius: 999px;
          font-size: 0.6875rem;
          font-weight: 600;
          letter-spacing: 0.02em;
          white-space: nowrap;
        }

        .badge--absence     { background: var(--bdg-absence-bg);     color: var(--bdg-absence-fg); }
        .badge--delay       { background: var(--bdg-delay-bg);       color: var(--bdg-delay-fg); }
        .badge--infirmary   { background: var(--bdg-infirmary-bg);   color: var(--bdg-infirmary-fg); }
        .badge--punishment  { background: var(--bdg-punishment-bg);  color: var(--bdg-punishment-fg); }
        .badge--observation { background: var(--bdg-observation-bg); color: var(--bdg-observation-fg); }
        .badge--other       { background: var(--bdg-other-bg);       color: var(--bdg-other-fg); }
        .badge--justified   { background: var(--bdg-justified-bg);   color: var(--bdg-justified-fg); }
        .badge--unjustified { background: var(--bdg-unjustified-bg); color: var(--bdg-unjustified-fg); }

        /* ----- Empty state ----- */
        .empty-state {
          text-align: center;
          padding: 4rem 1rem;
          color: var(--text-3);
          font-size: 0.9375rem;
        }
        """;
}
