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
 * <p>Tapping / clicking a card opens a {@code <dialog>} overlay listing every acquisition
 * component: level dot, full level label, item name, and domain. The dialog is closed by the
 * × button, by pressing Escape, or by clicking/tapping the backdrop. A small amount of inline
 * JavaScript (~20 lines) wires the open/close behaviour; all styling is in the embedded CSS.
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
            + "\n"
            + "  <dialog id=\"eval-dialog\" aria-modal=\"true\" aria-label=\"D\u00e9tail de l'\u00e9valuation\">\n"
            + "    <div class=\"dialog-inner\">\n"
            + "      <button class=\"dialog__close\" id=\"eval-dialog-close\" aria-label=\"Fermer\">\u00d7</button>\n"
            + "      <div id=\"eval-dialog-body\"></div>\n"
            + "    </div>\n"
            + "  </dialog>\n"
            + "\n"
            + "  <script>" + JS + "</script>\n"
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

        List<CompetenceAcquisition> ordered = eval.getAcquisitions() == null
            ? List.of()
            : eval.getAcquisitions().stream()
                .sorted(Comparator.comparingInt(CompetenceAcquisition::getOrder))
                .toList();

        StringBuilder card = new StringBuilder();
        card.append("      <div class=\"eval-card\" style=\"border-left-color:").append(color).append("\"")
            .append(" role=\"button\" tabindex=\"0\">\n");

        // ---- Visible card content ----

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
        if (!ordered.isEmpty()) {
            card.append("        <div class=\"eval-card__dots\">\n");
            for (CompetenceAcquisition acq : ordered) {
                card.append("          ").append(renderDot(acq)).append("\n");
            }
            card.append("        </div>\n");
        }

        // ---- Hidden detail panel (cloned into dialog on click) ----
        card.append("        <div class=\"eval-detail\" hidden>\n");

        // Detail header: subject + name + date
        card.append("          <div class=\"eval-detail__header\" style=\"border-bottom-color:").append(color).append("\">\n");
        card.append("            <div class=\"eval-detail__header-left\">\n");
        card.append("              <span class=\"eval-detail__subject\" style=\"color:").append(color).append("\">")
            .append(esc(subject)).append("</span>\n");
        if (eval.getName() != null && !eval.getName().isBlank()) {
            card.append("              <span class=\"eval-detail__name\">").append(esc(eval.getName())).append("</span>\n");
        }
        card.append("            </div>\n");
        if (!dateStr.isBlank()) {
            card.append("            <time class=\"eval-detail__date\">").append(esc(dateStr)).append("</time>\n");
        }
        card.append("          </div>\n");

        // Optional description
        if (eval.getDescription() != null && !eval.getDescription().isBlank()) {
            card.append("          <p class=\"eval-detail__desc\">").append(esc(eval.getDescription())).append("</p>\n");
        }

        // Acquisition list
        if (!ordered.isEmpty()) {
            card.append("          <ul class=\"eval-detail__list\">\n");
            for (CompetenceAcquisition acq : ordered) {
                String dotClass = levelClass(acq.getAbbreviation());
                String dotText  = "A+".equalsIgnoreCase(acq.getAbbreviation()) ? "+" : "";
                card.append("            <li class=\"eval-detail__item\">\n");
                card.append("              <span class=\"level-dot ").append(dotClass).append("\">")
                    .append(dotText).append("</span>\n");
                card.append("              <div class=\"eval-detail__item-text\">\n");
                if (acq.getLevel() != null && !acq.getLevel().isBlank()) {
                    card.append("                <span class=\"eval-detail__item-level\">")
                        .append(esc(acq.getLevel())).append("</span>\n");
                }
                if (acq.getName() != null && !acq.getName().isBlank()) {
                    card.append("                <span class=\"eval-detail__item-name\">")
                        .append(esc(acq.getName())).append("</span>\n");
                }
                if (acq.getDomain() != null && !acq.getDomain().isBlank()) {
                    card.append("                <span class=\"eval-detail__item-domain\">")
                        .append(esc(acq.getDomain())).append("</span>\n");
                }
                card.append("              </div>\n");
                card.append("            </li>\n");
            }
            card.append("          </ul>\n");
        }

        card.append("        </div>\n"); // end eval-detail

        card.append("      </div>\n"); // end eval-card
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
    // Embedded JavaScript
    // -------------------------------------------------------------------------

    static final String JS = """
        (function () {
          var dialog  = document.getElementById('eval-dialog');
          var body    = document.getElementById('eval-dialog-body');
          var closeBtn = document.getElementById('eval-dialog-close');

          // Open dialog when a card is clicked or activated via keyboard
          document.querySelectorAll('.eval-card').forEach(function (card) {
            card.addEventListener('click', function () {
              var detail = card.querySelector('.eval-detail');
              if (!detail) return;
              body.innerHTML = '';
              var clone = detail.cloneNode(true);
              clone.removeAttribute('hidden');
              body.appendChild(clone);
              dialog.showModal();
            });
            card.addEventListener('keydown', function (e) {
              if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); card.click(); }
            });
          });

          // Close on × button
          closeBtn.addEventListener('click', function () { dialog.close(); });

          // Close when clicking the backdrop (outside dialog-inner)
          dialog.addEventListener('click', function (e) {
            if (e.target === dialog) dialog.close();
          });
        })();
        """;

    // -------------------------------------------------------------------------
    // Embedded CSS
    // -------------------------------------------------------------------------

    static final String CSS = """

        /* ================================================================
           Évaluations — embedded stylesheet
           Light/dark via prefers-color-scheme · Responsive
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
          cursor: pointer;
          transition: box-shadow 0.15s ease, transform 0.1s ease;
          -webkit-tap-highlight-color: transparent;
          user-select: none;
        }

        .eval-card:hover {
          box-shadow: 0 3px 10px rgba(0, 0, 0, .12);
        }

        .eval-card:active {
          transform: scale(0.99);
        }

        .eval-card:focus-visible {
          outline: 2px solid var(--text-2);
          outline-offset: 2px;
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

        /* ================================================================
           Dialog / detail overlay
           ================================================================ */

        dialog {
          border: none;
          padding: 0;
          background: var(--surface);
          color: var(--text-1);
          border-radius: 16px;
          width: calc(100% - 2rem);
          max-width: 560px;
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
          padding: 0;
        }

        /* ----- Close button ----- */
        .dialog__close {
          position: sticky;
          top: 0.75rem;
          float: right;
          margin: 0.75rem 0.75rem 0 0;
          display: flex;
          align-items: center;
          justify-content: center;
          width: 1.75rem;
          height: 1.75rem;
          border: none;
          border-radius: 50%;
          background: var(--border);
          color: var(--text-2);
          font-size: 1.125rem;
          line-height: 1;
          cursor: pointer;
          z-index: 1;
          flex-shrink: 0;
        }

        .dialog__close:hover {
          background: var(--text-3);
          color: var(--text-1);
        }

        /* ----- Dialog body (cloned eval-detail) ----- */
        .eval-detail {
          padding: 1rem 1.25rem 1.5rem;
        }

        .eval-detail__header {
          display: flex;
          justify-content: space-between;
          align-items: flex-start;
          gap: 0.75rem;
          padding-bottom: 0.875rem;
          margin-bottom: 0.875rem;
          border-bottom: 2px solid var(--border);
        }

        .eval-detail__header-left {
          display: flex;
          flex-direction: column;
          gap: 0.2rem;
          min-width: 0;
        }

        .eval-detail__subject {
          font-size: 0.8125rem;
          font-weight: 700;
          letter-spacing: 0.04em;
          text-transform: uppercase;
        }

        .eval-detail__name {
          font-size: 0.9375rem;
          font-style: italic;
          color: var(--text-1);
          line-height: 1.4;
        }

        .eval-detail__date {
          font-size: 0.8125rem;
          color: var(--text-2);
          white-space: nowrap;
          flex-shrink: 0;
          padding-top: 0.1rem;
        }

        .eval-detail__desc {
          font-size: 0.875rem;
          color: var(--text-2);
          margin-bottom: 0.875rem;
          line-height: 1.5;
        }

        /* ----- Acquisition list ----- */
        .eval-detail__list {
          list-style: none;
          display: flex;
          flex-direction: column;
          gap: 0.625rem;
        }

        .eval-detail__item {
          display: flex;
          align-items: flex-start;
          gap: 0.625rem;
        }

        .eval-detail__item .level-dot {
          margin-top: 0.125rem;
          flex-shrink: 0;
          cursor: default;
        }

        .eval-detail__item-text {
          display: flex;
          flex-direction: column;
          gap: 0.1rem;
          min-width: 0;
        }

        .eval-detail__item-level {
          font-size: 0.75rem;
          font-weight: 600;
          color: var(--text-2);
          text-transform: uppercase;
          letter-spacing: 0.03em;
        }

        .eval-detail__item-name {
          font-size: 0.9375rem;
          color: var(--text-1);
          line-height: 1.4;
        }

        .eval-detail__item-domain {
          font-size: 0.8125rem;
          color: var(--text-3);
          line-height: 1.3;
        }
        """;
}
