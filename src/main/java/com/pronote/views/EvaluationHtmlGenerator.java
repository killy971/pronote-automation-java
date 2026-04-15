package com.pronote.views;

import com.pronote.domain.CompetenceAcquisition;
import com.pronote.domain.CompetenceEvaluation;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Generates a self-contained HTML5 page listing the most recent competence evaluations.
 *
 * <p>Evaluations are sorted by date descending (newest first) and capped at {@code maxEntries}.
 * Each card shows subject (with deterministic accent colour), date, evaluation name, teacher,
 * and a row of level dots — one per acquisition, colour-coded by level abbreviation.
 *
 * <p>Level-dot colour scheme (matches Pronote official app):
 * <ul>
 *   <li>A+ — dark green dot with {@code +} text</li>
 *   <li>A  — green dot</li>
 *   <li>B  — yellow-green dot</li>
 *   <li>C  — yellow dot</li>
 *   <li>D  — orange dot</li>
 *   <li>E  — red dot</li>
 *   <li>Ne / ABS — outlined/grey dot</li>
 * </ul>
 * Hover tooltip on each dot reveals: {@code abbreviation · domain · item}.
 */
public class EvaluationHtmlGenerator {

    private static final String[] ACCENT_COLORS = {
        "#3b82f6", "#10b981", "#f59e0b", "#ef4444", "#8b5cf6",
        "#ec4899", "#14b8a6", "#f97316", "#06b6d4", "#84cc16",
        "#a855f7", "#6366f1"
    };

    private static final DateTimeFormatter SHORT_DATE_FMT =
        DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH);
    private static final DateTimeFormatter DATETIME_FMT =
        DateTimeFormatter.ofPattern("d MMMM yyyy '\u00e0' HH'h'mm", Locale.FRENCH);

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates the full HTML document.
     *
     * @param evaluations all competence evaluations (sorted + limited internally)
     * @param maxEntries  maximum number of evaluations to display (newest first)
     * @return a complete, self-contained HTML5 document
     */
    public String generate(List<CompetenceEvaluation> evaluations, int maxEntries) {
        List<CompetenceEvaluation> sorted = evaluations.stream()
            .filter(e -> e.getDate() != null)
            .sorted(Comparator.comparing(CompetenceEvaluation::getDate).reversed()
                .thenComparing(EvaluationHtmlGenerator::displaySubject))
            .limit(maxEntries)
            .toList();

        String content = sorted.isEmpty()
            ? "      <p class=\"empty-state\">Aucune \u00e9valuation disponible.</p>\n"
            : renderList(sorted);

        String generatedAt = capitalize(LocalDateTime.now().format(DATETIME_FMT));

        return "<!DOCTYPE html>\n"
            + "<html lang=\"fr\">\n"
            + "<head>\n"
            + "  <meta charset=\"UTF-8\">\n"
            + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
            + "  <title>\u00c9valuations</title>\n"
            + "  <style>" + CSS + "</style>\n"
            + "</head>\n"
            + "<body>\n"
            + "  <div class=\"page\">\n"
            + "    <nav class=\"nav\"><a class=\"nav__back\" href=\"../index.html\">\u2190\u00a0Pronote</a></nav>\n"
            + "    <header class=\"page-header\">\n"
            + "      <h1 class=\"page-header__title\">\u00c9valuations</h1>\n"
            + "      <p class=\"page-header__subtitle\">Mis \u00e0 jour le " + generatedAt + "</p>\n"
            + "    </header>\n"
            + "    <main class=\"eval-list\">\n"
            + content
            + "    </main>\n"
            + "  </div>\n"
            + "</body>\n"
            + "</html>\n";
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    private String renderList(List<CompetenceEvaluation> evaluations) {
        StringBuilder sb = new StringBuilder();
        for (CompetenceEvaluation eval : evaluations) {
            sb.append(renderCard(eval));
        }
        return sb.toString();
    }

    private String renderCard(CompetenceEvaluation eval) {
        String subject = displaySubject(eval);
        String color   = ACCENT_COLORS[Math.abs(subject.hashCode()) % ACCENT_COLORS.length];
        String dateStr = eval.getDate() != null ? eval.getDate().format(SHORT_DATE_FMT) : "";

        StringBuilder card = new StringBuilder();
        card.append("      <div class=\"eval-card\" style=\"border-left-color:").append(color).append("\">\n");

        // Header: subject + date
        card.append("        <div class=\"eval-card__header\">\n");
        card.append("          <span class=\"eval-card__subject\">").append(esc(subject)).append("</span>\n");
        if (!dateStr.isBlank()) {
            card.append("          <time class=\"eval-card__date\">").append(esc(dateStr)).append("</time>\n");
        }
        card.append("        </div>\n");

        // Evaluation name
        if (eval.getName() != null && !eval.getName().isBlank()) {
            card.append("        <div class=\"eval-card__name\">").append(esc(eval.getName())).append("</div>\n");
        }

        // Teacher
        if (eval.getTeacher() != null && !eval.getTeacher().isBlank()) {
            card.append("        <div class=\"eval-card__teacher\">").append(esc(eval.getTeacher())).append("</div>\n");
        }

        // Competence dots
        List<CompetenceAcquisition> acquisitions = eval.getAcquisitions();
        if (acquisitions != null && !acquisitions.isEmpty()) {
            List<CompetenceAcquisition> ordered = acquisitions.stream()
                .sorted(Comparator.comparingInt(CompetenceAcquisition::getOrder))
                .toList();
            card.append("        <div class=\"eval-card__dots\">\n");
            for (CompetenceAcquisition acq : ordered) {
                card.append("          ").append(renderDot(acq)).append("\n");
            }
            card.append("        </div>\n");
        }

        card.append("      </div>\n");
        return card.toString();
    }

    private String renderDot(CompetenceAcquisition acq) {
        String cssClass  = levelClass(acq.getAbbreviation());
        String dotText   = "A+".equalsIgnoreCase(acq.getAbbreviation()) ? "+" : "";

        // tooltip: abbreviation · domain · item
        StringBuilder tooltip = new StringBuilder();
        if (acq.getAbbreviation() != null && !acq.getAbbreviation().isBlank()) {
            tooltip.append(acq.getAbbreviation());
        }
        if (acq.getDomain() != null && !acq.getDomain().isBlank()) {
            tooltip.append(" \u00b7 ").append(acq.getDomain());
        }
        if (acq.getName() != null && !acq.getName().isBlank()) {
            tooltip.append(" \u00b7 ").append(acq.getName());
        }

        return "<span class=\"level-dot " + cssClass + "\" title=\"" + esc(tooltip.toString()) + "\">"
             + dotText + "</span>";
    }

    // -------------------------------------------------------------------------
    // Level-dot helpers
    // -------------------------------------------------------------------------

    private static String levelClass(String abbrev) {
        if (abbrev == null) return "level-dot--ne";
        return switch (abbrev.trim().toUpperCase()) {
            case "A+"  -> "level-dot--aplus";
            case "A"   -> "level-dot--a";
            case "B"   -> "level-dot--b";
            case "C"   -> "level-dot--c";
            case "D"   -> "level-dot--d";
            case "E"   -> "level-dot--e";
            case "ABS" -> "level-dot--abs";
            default    -> "level-dot--ne";
        };
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static String displaySubject(CompetenceEvaluation e) {
        String s = e.getEnrichedSubject();
        return (s != null && !s.isBlank()) ? s : (e.getSubject() != null ? e.getSubject() : "");
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
    // Embedded CSS
    // -------------------------------------------------------------------------

    static final String CSS = """

        /* ================================================================
           Évaluations — embedded stylesheet
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

          --lvl-aplus:      #15803d;
          --lvl-a:          #22c55e;
          --lvl-b:          #84cc16;
          --lvl-c:          #eab308;
          --lvl-d:          #f97316;
          --lvl-e:          #ef4444;
          --lvl-ne-border:  #94a3b8;
        }

        @media (prefers-color-scheme: dark) {
          :root {
            --bg:      #0c0d13;
            --surface: #14151f;
            --text-1:  #e2e8f0;
            --text-2:  #7c84a0;
            --text-3:  #4a5068;
            --border:  #1e2030;

            --lvl-aplus:      #16a34a;
            --lvl-a:          #4ade80;
            --lvl-b:          #a3e635;
            --lvl-c:          #facc15;
            --lvl-d:          #fb923c;
            --lvl-e:          #f87171;
            --lvl-ne-border:  #4a5068;
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

        /* ----- Evaluation list ----- */
        .eval-list {
          display: flex;
          flex-direction: column;
          gap: 0.5rem;
        }

        /* ----- Evaluation card ----- */
        .eval-card {
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

        .eval-card__header {
          display: flex;
          justify-content: space-between;
          align-items: baseline;
          gap: 0.5rem;
          min-width: 0;
        }

        .eval-card__subject {
          font-size: 0.875rem;
          font-weight: 700;
          letter-spacing: 0.03em;
          text-transform: uppercase;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }

        .eval-card__date {
          font-size: 0.75rem;
          color: var(--text-2);
          white-space: nowrap;
          flex-shrink: 0;
        }

        .eval-card__name {
          font-size: 0.875rem;
          color: var(--text-1);
          font-style: italic;
          line-height: 1.4;
          margin-top: 0.1rem;
        }

        .eval-card__teacher {
          font-size: 0.75rem;
          color: var(--text-2);
        }

        /* ----- Competence dots row ----- */
        .eval-card__dots {
          display: flex;
          flex-wrap: wrap;
          gap: 0.3rem;
          margin-top: 0.375rem;
          padding-top: 0.375rem;
          border-top: 1px solid var(--border);
        }

        .level-dot {
          display: inline-flex;
          align-items: center;
          justify-content: center;
          width: 1.125rem;
          height: 1.125rem;
          border-radius: 50%;
          font-size: 0.5625rem;
          font-weight: 900;
          color: #fff;
          flex-shrink: 0;
          cursor: default;
          line-height: 1;
        }

        .level-dot--aplus { background: var(--lvl-aplus); }
        .level-dot--a     { background: var(--lvl-a); }
        .level-dot--b     { background: var(--lvl-b); }
        .level-dot--c     { background: var(--lvl-c); }
        .level-dot--d     { background: var(--lvl-d); }
        .level-dot--e     { background: var(--lvl-e); }
        .level-dot--ne    { background: transparent; border: 2px solid var(--lvl-ne-border); }
        .level-dot--abs   { background: var(--text-3); }

        /* ----- Empty state ----- */
        .empty-state {
          text-align: center;
          padding: 4rem 1rem;
          color: var(--text-3);
          font-size: 0.9375rem;
        }
        """;
}
