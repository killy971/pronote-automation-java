package com.pronote.views;

import com.pronote.domain.CompetenceAcquisition;
import com.pronote.domain.CompetenceEvaluation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a self-contained HTML5 summary page for competence evaluations,
 * organised by trimester (latest first), then by subject (alphabetical).
 *
 * <p>Within each subject block the subject name and teacher are shown once as a
 * header; individual evaluations are then listed as compact single-line rows
 * (date + name + level dots) without repeating those fields.  Tapping/clicking
 * a row still opens the same full detail dialog as the flat list page.
 *
 * <p>The base CSS and JS are shared with {@link EvaluationHtmlGenerator}; a small
 * {@link #SUMMARY_CSS} block appended for period/subject/compact-row layout.
 */
public class EvaluationSummaryHtmlGenerator {

    private static final DateTimeFormatter SHORT_DATE_FMT =
        DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH);
    private static final DateTimeFormatter DATETIME_FMT =
        DateTimeFormatter.ofPattern("d MMMM yyyy '\u00e0' HH'h'mm", Locale.FRENCH);
    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d+");

    private final EvaluationHtmlGenerator helper = new EvaluationHtmlGenerator();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates the full HTML summary document.
     *
     * @param evaluations all competence evaluations (any order)
     * @return a complete, self-contained HTML5 document
     */
    public String generate(List<CompetenceEvaluation> evaluations) {
        // Sort all evaluations by date desc (null dates sorted last), then by subject
        List<CompetenceEvaluation> sorted = new ArrayList<>(evaluations);
        sorted.sort(Comparator
            .comparing((CompetenceEvaluation e) -> e.getDate() != null ? e.getDate() : LocalDate.MIN)
            .reversed()
            .thenComparing(e -> EvaluationHtmlGenerator.displaySubject(e)));

        // Collect distinct period names, latest trimester first
        List<String> periods = sorted.stream()
            .map(e -> e.getPeriodName() != null ? e.getPeriodName() : "")
            .distinct()
            .sorted(Comparator.comparingInt(this::periodOrder).reversed()
                .thenComparing(Comparator.<String>reverseOrder()))
            .toList();

        String content;
        if (periods.isEmpty()) {
            content = "      <p class=\"empty-state\">Aucune \u00e9valuation disponible.</p>\n";
        } else {
            StringBuilder sb = new StringBuilder();
            for (String period : periods) {
                sb.append(renderPeriodSection(period, sorted));
            }
            content = sb.toString();
        }

        String generatedAt = capitalize(LocalDateTime.now().format(DATETIME_FMT));

        return "<!DOCTYPE html>\n"
            + "<html lang=\"fr\">\n"
            + "<head>\n"
            + "  <meta charset=\"UTF-8\">\n"
            + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
            + "  <title>Bilan des \u00e9valuations</title>\n"
            + "  <style>" + EvaluationHtmlGenerator.CSS + SUMMARY_CSS + "</style>\n"
            + "</head>\n"
            + "<body>\n"
            + "  <div class=\"page\">\n"
            + "    <nav class=\"nav\"><a class=\"nav__back\" href=\"../index.html\">\u2190\u00a0Pronote</a></nav>\n"
            + "    <header class=\"page-header\">\n"
            + "      <h1 class=\"page-header__title\">Bilan des \u00e9valuations</h1>\n"
            + "      <p class=\"page-header__subtitle\">Mis \u00e0 jour le " + generatedAt + "</p>\n"
            + "    </header>\n"
            + "    <main class=\"summary-main\">\n"
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
            + "  <script>" + SUMMARY_JS + "</script>\n"
            + "</body>\n"
            + "</html>\n";
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    private String renderPeriodSection(String period, List<CompetenceEvaluation> sorted) {
        List<CompetenceEvaluation> forPeriod = sorted.stream()
            .filter(e -> Objects.equals(e.getPeriodName() != null ? e.getPeriodName() : "", period))
            .toList();

        // Subjects for this period, alphabetical
        List<String> subjects = forPeriod.stream()
            .map(EvaluationHtmlGenerator::displaySubject)
            .distinct()
            .sorted()
            .toList();

        String title = period.isBlank() ? "Sans p\u00e9riode" : period;

        StringBuilder sb = new StringBuilder();
        sb.append("      <section class=\"period-section\">\n");
        sb.append("        <h2 class=\"period-section__title\">")
            .append(EvaluationHtmlGenerator.esc(title)).append("</h2>\n");

        for (String subject : subjects) {
            sb.append(renderSubjectGroup(subject, forPeriod));
        }

        sb.append("      </section>\n");
        return sb.toString();
    }

    private String renderSubjectGroup(String subject, List<CompetenceEvaluation> forPeriod) {
        List<CompetenceEvaluation> forSubject = forPeriod.stream()
            .filter(e -> Objects.equals(EvaluationHtmlGenerator.displaySubject(e), subject))
            .toList(); // already sorted date desc

        String color = EvaluationHtmlGenerator.ACCENT_COLORS[
            Math.abs(subject.hashCode()) % EvaluationHtmlGenerator.ACCENT_COLORS.length];

        // Collect unique, non-blank teacher names for this group
        String teachers = forSubject.stream()
            .map(CompetenceEvaluation::getTeacher)
            .filter(t -> t != null && !t.isBlank())
            .distinct()
            .sorted()
            .reduce((a, b) -> a + ", " + b)
            .orElse(null);

        StringBuilder sb = new StringBuilder();
        sb.append("        <div class=\"subject-group\">\n");

        // Subject title (once, with accent colour)
        sb.append("          <div class=\"subject-group__header\" style=\"border-left-color:").append(color).append("\">\n");
        sb.append("            <span class=\"subject-group__title\" style=\"color:").append(color).append(";\">")
            .append(EvaluationHtmlGenerator.esc(subject)).append("</span>\n");
        if (teachers != null) {
            sb.append("            <span class=\"subject-group__teacher\">")
                .append(EvaluationHtmlGenerator.esc(teachers)).append("</span>\n");
        }
        sb.append("          </div>\n");

        // Compact eval rows (no subject/teacher repeated)
        sb.append("          <div class=\"compact-list\">\n");
        for (CompetenceEvaluation eval : forSubject) {
            sb.append(renderCompactRow(eval));
        }
        sb.append("          </div>\n");

        sb.append("        </div>\n");
        return sb.toString();
    }

    private String renderCompactRow(CompetenceEvaluation eval) {
        String dateStr = eval.getDate() != null ? eval.getDate().format(SHORT_DATE_FMT) : "";

        List<CompetenceAcquisition> ordered = eval.getAcquisitions() == null
            ? List.of()
            : eval.getAcquisitions().stream()
                .sorted(Comparator.comparingInt(CompetenceAcquisition::getOrder))
                .toList();

        StringBuilder row = new StringBuilder();
        row.append("            <div class=\"eval-compact\" role=\"button\" tabindex=\"0\">\n");
        row.append("              <div class=\"eval-compact__row\">\n");

        // Date
        if (!dateStr.isBlank()) {
            row.append("                <time class=\"eval-compact__date\">")
                .append(EvaluationHtmlGenerator.esc(dateStr)).append("</time>\n");
        }

        // Evaluation name
        String name = (eval.getName() != null && !eval.getName().isBlank()) ? eval.getName() : "\u2014";
        row.append("                <span class=\"eval-compact__name\">")
            .append(EvaluationHtmlGenerator.esc(name)).append("</span>\n");

        // Level dots
        if (!ordered.isEmpty()) {
            row.append("                <div class=\"eval-compact__dots\">\n");
            for (CompetenceAcquisition acq : ordered) {
                row.append("                  ").append(helper.renderDot(acq)).append("\n");
            }
            row.append("                </div>\n");
        }

        row.append("              </div>\n");

        // Hidden detail panel for dialog (reused from EvaluationHtmlGenerator)
        row.append(helper.renderDetailPanel(eval));

        row.append("            </div>\n"); // end eval-compact
        return row.toString();
    }

    // -------------------------------------------------------------------------
    // Period ordering
    // -------------------------------------------------------------------------

    /**
     * Extracts the leading integer from a period name for sort ordering.
     * "Trimestre 3" → 3, "1er Trimestre" → 1, unknown → 0, blank → -1.
     */
    private int periodOrder(String name) {
        if (name == null || name.isBlank()) return -1;
        Matcher m = DIGIT_PATTERN.matcher(name);
        return m.find() ? Integer.parseInt(m.group()) : 0;
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // -------------------------------------------------------------------------
    // Embedded JS (same as EvaluationHtmlGenerator but selects .eval-compact too)
    // -------------------------------------------------------------------------

    static final String SUMMARY_JS = """
        (function () {
          var dialog  = document.getElementById('eval-dialog');
          var body    = document.getElementById('eval-dialog-body');
          var closeBtn = document.getElementById('eval-dialog-close');

          function openDialog(card) {
            var detail = card.querySelector('.eval-detail');
            if (!detail) return;
            body.innerHTML = '';
            var clone = detail.cloneNode(true);
            clone.removeAttribute('hidden');
            body.appendChild(clone);

            // Align dialog top with the card's top
            var refTop = card.getBoundingClientRect().top;
            dialog.style.top = Math.max(8, refTop) + 'px';
            dialog.showModal();
            // Clamp so the dialog doesn't overflow the bottom of the viewport
            var overflow = dialog.offsetTop + dialog.offsetHeight - window.innerHeight + 8;
            if (overflow > 0) {
              dialog.style.top = Math.max(8, refTop - overflow) + 'px';
            }
          }

          document.querySelectorAll('.eval-card, .eval-compact').forEach(function (card) {
            card.addEventListener('click', function () { openDialog(card); });
            card.addEventListener('keydown', function (e) {
              if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openDialog(card); }
            });
          });

          closeBtn.addEventListener('click', function () { dialog.close(); });

          dialog.addEventListener('click', function (e) {
            if (e.target === dialog) dialog.close();
          });
        })();
        """;

    // -------------------------------------------------------------------------
    // Additional embedded CSS (appended after EvaluationHtmlGenerator.CSS)
    // -------------------------------------------------------------------------

    static final String SUMMARY_CSS = """

        /* ================================================================
           \u00c9valuations — r\u00e9capitulatif par trimestre / mati\u00e8re
           ================================================================ */

        .summary-main {
          display: flex;
          flex-direction: column;
        }

        /* ----- Period section ----- */
        .period-section {
          margin-bottom: 2.5rem;
        }

        .period-section__title {
          font-size: 1.125rem;
          font-weight: 700;
          letter-spacing: -0.02em;
          color: var(--text-1);
          padding-bottom: 0.625rem;
          margin-bottom: 1rem;
          border-bottom: 2px solid var(--border);
        }

        /* ----- Subject group ----- */
        .subject-group {
          margin-bottom: 1.25rem;
        }

        .subject-group__header {
          display: flex;
          align-items: baseline;
          gap: 0.625rem;
          border-left: 3px solid var(--border);
          padding-left: 0.625rem;
          margin-bottom: 0.5rem;
        }

        .subject-group__title {
          font-size: 0.8125rem;
          font-weight: 700;
          letter-spacing: 0.05em;
          text-transform: uppercase;
        }

        .subject-group__teacher {
          font-size: 0.75rem;
          color: var(--text-2);
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }

        /* ----- Compact eval list ----- */
        .compact-list {
          display: flex;
          flex-direction: column;
          gap: 0.25rem;
        }

        /* ----- Compact eval row ----- */
        .eval-compact {
          display: flex;
          flex-direction: column;
          background: var(--surface);
          border-radius: 8px;
          box-shadow: 0 1px 2px rgba(0, 0, 0, .05);
          cursor: pointer;
          overflow: hidden;
          -webkit-tap-highlight-color: transparent;
          user-select: none;
          transition: box-shadow 0.15s ease;
        }

        .eval-compact:hover {
          box-shadow: 0 3px 8px rgba(0, 0, 0, .1);
        }

        .eval-compact:active {
          opacity: 0.85;
        }

        .eval-compact:focus-visible {
          outline: 2px solid var(--text-2);
          outline-offset: 2px;
        }

        .eval-compact__row {
          display: flex;
          align-items: center;
          gap: 0.625rem;
          padding: 0.5rem 0.75rem;
          min-width: 0;
        }

        .eval-compact__date {
          font-size: 0.75rem;
          color: var(--text-2);
          white-space: nowrap;
          flex-shrink: 0;
          min-width: 3.25rem;
        }

        .eval-compact__name {
          font-size: 0.875rem;
          color: var(--text-1);
          flex: 1;
          min-width: 0;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }

        .eval-compact__dots {
          display: flex;
          flex-wrap: wrap;
          gap: 0.2rem;
          flex-shrink: 0;
        }
        """;
}
