package com.pronote.views;

import com.pronote.domain.CompetenceEvaluation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a self-contained HTML5 summary page for competence evaluations,
 * organised by trimester (latest first), then by subject (alphabetical), then
 * by evaluation date descending within each subject.
 *
 * <p>The card design, CSS, and detail dialog are shared with
 * {@link EvaluationHtmlGenerator}: the same {@code .eval-card} markup is
 * reused via {@link EvaluationHtmlGenerator#renderCard(CompetenceEvaluation)},
 * and the base CSS is embedded verbatim so both pages look identical at the
 * card level.
 *
 * <p>Additional CSS for the period/subject grouping (period titles, subject
 * headings) is appended via {@link #SUMMARY_CSS}.
 */
public class EvaluationSummaryHtmlGenerator {

    private static final DateTimeFormatter DATETIME_FMT =
        DateTimeFormatter.ofPattern("d MMMM yyyy '\u00e0' HH'h'mm", Locale.FRENCH);
    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d+");

    private final EvaluationHtmlGenerator cardRenderer = new EvaluationHtmlGenerator();

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
            + "  <script>" + EvaluationHtmlGenerator.JS + "</script>\n"
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

        StringBuilder sb = new StringBuilder();
        sb.append("        <div class=\"subject-group\">\n");
        sb.append("          <h3 class=\"subject-group__title\" style=\"color:")
            .append(color).append(";\">")
            .append(EvaluationHtmlGenerator.esc(subject)).append("</h3>\n");
        sb.append("          <div class=\"eval-list\">\n");
        for (CompetenceEvaluation eval : forSubject) {
            sb.append(cardRenderer.renderCard(eval));
        }
        sb.append("          </div>\n");
        sb.append("        </div>\n");
        return sb.toString();
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
    // Additional embedded CSS (appended after EvaluationHtmlGenerator.CSS)
    // -------------------------------------------------------------------------

    static final String SUMMARY_CSS = """

        /* ================================================================
           \u00c9valuations — r\u00e9capitulatif par trimestre / mati\u00e8re
           ================================================================ */

        .summary-main {
          display: flex;
          flex-direction: column;
          gap: 0;
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

        .subject-group__title {
          font-size: 0.75rem;
          font-weight: 700;
          letter-spacing: 0.06em;
          text-transform: uppercase;
          margin-bottom: 0.5rem;
        }
        """;
}
