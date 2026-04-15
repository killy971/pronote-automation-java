package com.pronote.views;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Generates the top-level portal index page for the Pronote GitHub Pages section.
 *
 * <p>The page contains one card per enabled view section (timetable, assignments, evaluations,
 * school-life), each linking to its subdirectory {@code index.html}. The card grid adapts to
 * one or two columns depending on viewport width.
 */
public class PortalIndexHtmlGenerator {

    private static final DateTimeFormatter DATETIME_FMT =
        DateTimeFormatter.ofPattern("d MMMM yyyy '\u00e0' HH'h'mm", Locale.FRENCH);

    /**
     * Descriptor for one section card in the portal index.
     *
     * @param emoji       section icon (emoji character, rendered large)
     * @param title       section title
     * @param description one-line description shown below the title
     * @param href        relative URL to the section (e.g. {@code "timetable/index.html"})
     */
    public record Section(String emoji, String title, String description, String href) {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates the portal index HTML document.
     *
     * @param sections ordered list of sections to display; empty list renders an empty grid
     * @return a complete, self-contained HTML5 document
     */
    public String generate(List<Section> sections) {
        String generatedAt = capitalize(LocalDateTime.now().format(DATETIME_FMT));

        StringBuilder cards = new StringBuilder();
        for (Section s : sections) {
            cards.append(renderCard(s));
        }

        String grid = sections.isEmpty()
            ? "    <p class=\"empty-state\">Aucune section configur\u00e9e.</p>\n"
            : "    <nav class=\"sections-grid\" aria-label=\"Sections\">\n" + cards + "    </nav>\n";

        return "<!DOCTYPE html>\n"
            + "<html lang=\"fr\">\n"
            + "<head>\n"
            + "  <meta charset=\"UTF-8\">\n"
            + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
            + "  <title>Pronote</title>\n"
            + "  <style>" + CSS + "</style>\n"
            + "</head>\n"
            + "<body>\n"
            + "  <div class=\"page\">\n"
            + "    <header class=\"page-header\">\n"
            + "      <h1 class=\"page-header__title\">Pronote</h1>\n"
            + "      <p class=\"page-header__subtitle\">Mis \u00e0 jour le " + generatedAt + "</p>\n"
            + "    </header>\n"
            + grid
            + "  </div>\n"
            + "</body>\n"
            + "</html>\n";
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    private String renderCard(Section s) {
        return "      <a class=\"section-card\" href=\"" + esc(s.href()) + "\">\n"
             + "        <span class=\"section-card__icon\" aria-hidden=\"true\">" + s.emoji() + "</span>\n"
             + "        <div class=\"section-card__title\">" + esc(s.title()) + "</div>\n"
             + "        <div class=\"section-card__desc\">" + esc(s.description()) + "</div>\n"
             + "      </a>\n";
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String esc(String s) {
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
           Pronote portal index — embedded stylesheet
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
        }

        @media (prefers-color-scheme: dark) {
          :root {
            --bg:      #0c0d13;
            --surface: #14151f;
            --text-1:  #e2e8f0;
            --text-2:  #7c84a0;
            --text-3:  #4a5068;
            --border:  #1e2030;
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

        body { padding: 1.5rem 1rem 4rem; }

        .page {
          max-width: 640px;
          margin: 0 auto;
        }

        /* ----- Page header ----- */
        .page-header { padding: 0.5rem 0 1.75rem; }

        .page-header__title {
          font-size: 2rem;
          font-weight: 700;
          letter-spacing: -0.03em;
        }

        .page-header__subtitle {
          font-size: 0.875rem;
          color: var(--text-2);
          margin-top: 0.25rem;
        }

        /* ----- Sections grid ----- */
        .sections-grid {
          display: grid;
          grid-template-columns: repeat(auto-fill, minmax(12rem, 1fr));
          gap: 0.75rem;
        }

        /* ----- Section card ----- */
        .section-card {
          display: flex;
          flex-direction: column;
          gap: 0.4rem;
          padding: 1.25rem 1.125rem;
          background: var(--surface);
          border-radius: 14px;
          border: 1px solid var(--border);
          text-decoration: none;
          color: inherit;
          box-shadow: 0 1px 2px rgba(0, 0, 0, .05);
          transition: box-shadow .15s ease, border-color .15s ease;
        }

        .section-card:hover {
          box-shadow: 0 4px 16px rgba(0, 0, 0, .12);
          border-color: transparent;
        }

        .section-card__icon {
          font-size: 2rem;
          line-height: 1;
          margin-bottom: 0.25rem;
        }

        .section-card__title {
          font-size: 1rem;
          font-weight: 700;
          letter-spacing: -0.015em;
        }

        .section-card__desc {
          font-size: 0.8125rem;
          color: var(--text-2);
          line-height: 1.4;
        }

        /* ----- Empty state ----- */
        .empty-state {
          text-align: center;
          padding: 4rem 1rem;
          color: var(--text-3);
          font-size: 0.9375rem;
        }
        """;
}
