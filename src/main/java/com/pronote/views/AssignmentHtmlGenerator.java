package com.pronote.views;

import com.pronote.domain.Assignment;
import com.pronote.domain.AttachmentRef;
import com.pronote.domain.TimetableEntry;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generates a single self-contained HTML5 page listing all upcoming assignments.
 *
 * <p>Assignments are grouped by {@code dueDate} ascending, then by subject within each date.
 * Local attachments (G=1) are linked via relative paths from the output directory.
 * Hyperlink attachments (G=0) are linked to their external URL.
 *
 * <p>The generated document:
 * <ul>
 *   <li>Embeds all CSS — no external resources needed.</li>
 *   <li>Adapts to system light/dark preference via {@code prefers-color-scheme}.</li>
 *   <li>Is responsive and print-friendly ({@code @media print} rules included).</li>
 *   <li>Requires no JavaScript.</li>
 * </ul>
 */
public class AssignmentHtmlGenerator {

    /** Accent palette — same as timetable views; colour index = {@code abs(subject.hashCode()) % 12}. */
    private static final String[] ACCENT_COLORS = {
        "#3b82f6", "#10b981", "#f59e0b", "#ef4444", "#8b5cf6",
        "#ec4899", "#14b8a6", "#f97316", "#06b6d4", "#84cc16",
        "#a855f7", "#6366f1"
    };

    private static final DateTimeFormatter DATE_HEADER_FMT =
        DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH);
    private static final DateTimeFormatter DATE_ATTR_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT =
        DateTimeFormatter.ofPattern("d MMMM yyyy '\u00e0' HH'h'mm", Locale.FRENCH);

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates the full HTML document.
     *
     * @param assignments all assignments (upcoming ones are filtered internally: dueDate >= today)
     * @param timetable   full timetable snapshot; competence evaluations (isEval=true, future date)
     *                    are shown in a dedicated section above assignments. Pass empty list if
     *                    timetable data is unavailable.
     * @param outputDir   absolute, normalised output directory used to compute relative paths for
     *                    local attachment links
     * @return a complete, self-contained HTML5 document
     */
    public String generate(List<Assignment> assignments, List<TimetableEntry> timetable, Path outputDir) {
        LocalDate today = LocalDate.now();

        List<Assignment> upcoming = assignments.stream()
            .filter(a -> a.getDueDate() != null && !a.getDueDate().isBefore(today))
            .sorted(Comparator.comparing(Assignment::getDueDate)
                .thenComparing(a -> displaySubject(a)))
            .toList();

        List<TimetableEntry> upcomingEvals = timetable.stream()
            .filter(e -> e.isEval()
                      && e.getStartTime() != null
                      && !e.getStartTime().toLocalDate().isBefore(today))
            .sorted(Comparator.comparing(TimetableEntry::getStartTime))
            .toList();

        String evalSection = upcomingEvals.isEmpty() ? "" : renderUpcomingEvals(upcomingEvals);

        String content = upcoming.isEmpty()
            ? "      <p class=\"empty-state\">Aucun devoir \u00e0 venir.</p>\n"
            : renderAssignments(upcoming, outputDir);

        String generatedAt = capitalize(LocalDateTime.now().format(DATETIME_FMT));

        return "<!DOCTYPE html>\n"
            + "<html lang=\"fr\">\n"
            + "<head>\n"
            + "  <meta charset=\"UTF-8\">\n"
            + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
            + "  <title>Devoirs \u00e0 venir</title>\n"
            + "  <style>" + CSS + "</style>\n"
            + "</head>\n"
            + "<body>\n"
            + "  <div class=\"page\">\n"
            + "    <nav class=\"nav\"><a class=\"nav__back\" href=\"../index.html\">\u2190\u00a0Pronote</a></nav>\n"
            + "    <header class=\"page-header\">\n"
            + "      <h1 class=\"page-header__title\">Devoirs \u00e0 venir</h1>\n"
            + "      <p class=\"page-header__subtitle\">Mis \u00e0 jour le " + generatedAt + "</p>\n"
            + "    </header>\n"
            + evalSection
            + "    <main class=\"assignments\">\n"
            + content
            + "    </main>\n"
            + "  </div>\n"
            + "</body>\n"
            + "</html>\n";
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    private String renderUpcomingEvals(List<TimetableEntry> evals) {
        StringBuilder sb = new StringBuilder();
        sb.append("    <section class=\"eval-section\">\n");
        sb.append("      <h2 class=\"eval-section__title\">\u00c9valuations de comp\u00e9tences \u00e0 venir</h2>\n");
        sb.append("      <ul class=\"eval-list\">\n");
        for (TimetableEntry e : evals) {
            LocalDate date = e.getStartTime().toLocalDate();
            String dateStr = capitalize(date.format(DATE_HEADER_FMT));
            String dateAttr = date.format(DATE_ATTR_FMT);
            String subject = e.getEnrichedSubject() != null && !e.getEnrichedSubject().isBlank()
                ? e.getEnrichedSubject() : e.getSubject();
            String color = ACCENT_COLORS[Math.abs(e.getSubject().hashCode()) % ACCENT_COLORS.length];
            String label = e.getLessonLabel() != null ? e.getLessonLabel() : "\u00c9val. de comp\u00e9tences";
            sb.append("        <li class=\"eval-item\" style=\"border-left-color:").append(color).append("\">\n");
            sb.append("          <span class=\"eval-item__subject\">").append(esc(subject)).append("</span>\n");
            sb.append("          <time class=\"eval-item__date\" datetime=\"").append(dateAttr).append("\">")
              .append(esc(dateStr)).append("</time>\n");
            sb.append("          <span class=\"badge badge--eval\">").append(esc(label)).append("</span>\n");
            sb.append("        </li>\n");
        }
        sb.append("      </ul>\n");
        sb.append("    </section>\n");
        return sb.toString();
    }

    private String renderAssignments(List<Assignment> upcoming, Path outputDir) {
        // Group by dueDate (insertion order preserves ascending sort from the stream)
        Map<LocalDate, List<Assignment>> byDate = new LinkedHashMap<>();
        for (Assignment a : upcoming) {
            byDate.computeIfAbsent(a.getDueDate(), k -> new ArrayList<>()).add(a);
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<LocalDate, List<Assignment>> dateEntry : byDate.entrySet()) {
            sb.append(renderDateGroup(dateEntry.getKey(), dateEntry.getValue(), outputDir));
        }
        return sb.toString();
    }

    private String renderDateGroup(LocalDate date, List<Assignment> dayAssignments, Path outputDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("      <section class=\"date-group\" id=\"date-").append(date.format(DATE_ATTR_FMT)).append("\">\n");
        sb.append("        <h2 class=\"date-group__heading\">"
            + "<time datetime=\"").append(date.format(DATE_ATTR_FMT)).append("\">")
            .append(esc(capitalize(date.format(DATE_HEADER_FMT))))
            .append("</time></h2>\n");

        // Group by subject within this date (insertion order = sort order from upstream)
        Map<String, List<Assignment>> bySubject = new LinkedHashMap<>();
        for (Assignment a : dayAssignments) {
            bySubject.computeIfAbsent(displaySubject(a), k -> new ArrayList<>()).add(a);
        }

        for (Map.Entry<String, List<Assignment>> subjectEntry : bySubject.entrySet()) {
            sb.append(renderSubjectGroup(subjectEntry.getKey(), subjectEntry.getValue(), outputDir));
        }

        sb.append("      </section>\n");
        return sb.toString();
    }

    private String renderSubjectGroup(String subject, List<Assignment> assignments, Path outputDir) {
        String color = ACCENT_COLORS[Math.abs(subject.hashCode()) % ACCENT_COLORS.length];
        StringBuilder sb = new StringBuilder();

        sb.append("        <div class=\"subject-group\">\n");
        sb.append("          <div class=\"subject-group__header\" style=\"border-left-color:")
            .append(color).append("\">\n");
        sb.append("            <span class=\"subject-group__name\">").append(esc(subject)).append("</span>\n");
        sb.append("          </div>\n");

        for (Assignment a : assignments) {
            sb.append(renderAssignmentCard(a, outputDir));
        }

        sb.append("        </div>\n");
        return sb.toString();
    }

    private String renderAssignmentCard(Assignment a, Path outputDir) {
        boolean done = a.isDone();
        StringBuilder card = new StringBuilder();
        card.append("          <div class=\"assignment-card")
            .append(done ? " assignment-card--done" : "")
            .append("\">\n");

        if (done) {
            card.append("            <span class=\"badge badge--done\">Fait</span>\n");
        }

        String desc = a.getDescription();
        if (desc != null && !desc.isBlank()) {
            card.append("            <p class=\"assignment__description\">")
                .append(esc(desc))
                .append("</p>\n");
        }

        List<AttachmentRef> refs = a.getAttachments();
        if (refs != null && !refs.isEmpty()) {
            List<AttachmentRef> displayable = refs.stream()
                .filter(r -> (r.isUploadedFile() && r.getLocalPath() != null)
                          || (!r.isUploadedFile() && r.getUrl() != null))
                .toList();
            if (!displayable.isEmpty()) {
                card.append("            <ul class=\"assignment__attachments\">\n");
                for (AttachmentRef ref : displayable) {
                    card.append(renderAttachment(ref, outputDir));
                }
                card.append("            </ul>\n");
            }
        }

        card.append("          </div>\n");
        return card.toString();
    }

    private String renderAttachment(AttachmentRef ref, Path outputDir) {
        String href;
        if (ref.isUploadedFile()) {
            try {
                Path attachment = Path.of(ref.getLocalPath()).toAbsolutePath().normalize();
                href = outputDir.relativize(attachment).toString();
            } catch (Exception e) {
                // Paths on different roots (Windows) or other edge cases — fall back to absolute
                href = ref.getLocalPath();
            }
        } else {
            href = ref.getUrl();
        }
        return "              <li class=\"attachment\">"
            + "<a class=\"attachment__link\" href=\"" + esc(href) + "\" target=\"_blank\">"
            + "\uD83D\uDCCE\u00a0" + esc(ref.getFileName())
            + "</a></li>\n";
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static String displaySubject(Assignment a) {
        String e = a.getEnrichedSubject();
        return (e != null && !e.isBlank()) ? e : a.getSubject();
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
           Devoirs à venir — embedded stylesheet
           Light/dark via prefers-color-scheme · No JavaScript
           Responsive · Print-friendly
           ================================================================ */

        /* ----- Custom properties ----- */
        :root {
          --bg:      #f1f3f6;
          --surface: #ffffff;
          --text-1:  #0f172a;
          --text-2:  #64748b;
          --text-3:  #94a3b8;
          --border:  #e2e8f0;

          --bdg-done-bg:    #dcfce7; --bdg-done-fg:    #15803d;
          --bdg-eval-bg:    #fef3c7; --bdg-eval-fg:    #92400e;
          --eval-section-bg:#fffbeb; --eval-section-border: #fcd34d;
          --attach-bg:      #f0f7ff; --attach-fg:      #1e40af;
          --attach-border:  #bfdbfe;
        }

        @media (prefers-color-scheme: dark) {
          :root {
            --bg:      #0c0d13;
            --surface: #14151f;
            --text-1:  #e2e8f0;
            --text-2:  #7c84a0;
            --text-3:  #4a5068;
            --border:  #1e2030;

            --bdg-done-bg:   #052e16; --bdg-done-fg:   #4ade80;
            --bdg-eval-bg:   #3d2800; --bdg-eval-fg:   #fcd34d;
            --eval-section-bg:#1c1500; --eval-section-border: #854d0e;
            --attach-bg:     #0c1f40; --attach-fg:     #93c5fd;
            --attach-border: #1e3a5f;
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

        /* ----- Date group ----- */
        .date-group {
          margin-bottom: 2rem;
        }

        .date-group__heading {
          font-size: 1.0625rem;
          font-weight: 700;
          letter-spacing: -0.015em;
          padding-bottom: 0.5rem;
          border-bottom: 2px solid var(--border);
          margin-bottom: 0.875rem;
        }

        /* ----- Subject group ----- */
        .subject-group {
          margin-bottom: 0.875rem;
        }

        .subject-group__header {
          display: flex;
          align-items: center;
          padding: 0.35rem 0.75rem;
          border-left: 4px solid var(--border);
          margin-bottom: 0.375rem;
          background: var(--surface);
          border-radius: 0 6px 6px 0;
        }

        .subject-group__name {
          font-size: 0.75rem;
          font-weight: 700;
          letter-spacing: 0.06em;
          text-transform: uppercase;
          color: var(--text-2);
        }

        /* ----- Assignment card ----- */
        .assignment-card {
          padding: 0.75rem 0.875rem;
          background: var(--surface);
          border-radius: 8px;
          border: 1px solid var(--border);
          box-shadow: 0 1px 3px rgba(0, 0, 0, .06);
          margin-bottom: 0.375rem;
          display: flex;
          flex-direction: column;
          gap: 0.5rem;
        }

        .assignment-card--done { opacity: .55; }

        /* ----- Description ----- */
        .assignment__description {
          font-size: 0.9375rem;
          color: var(--text-1);
          line-height: 1.6;
          white-space: pre-wrap;
          word-break: break-word;
        }

        .assignment-card--done .assignment__description {
          text-decoration: line-through;
          color: var(--text-3);
        }

        /* ----- Badge ----- */
        .badge {
          display: inline-flex;
          align-items: center;
          align-self: flex-start;
          padding: 0.125rem 0.4375rem;
          border-radius: 999px;
          font-size: 0.6875rem;
          font-weight: 600;
          letter-spacing: 0.025em;
          white-space: nowrap;
        }

        .badge--done { background: var(--bdg-done-bg); color: var(--bdg-done-fg); }
        .badge--eval { background: var(--bdg-eval-bg); color: var(--bdg-eval-fg); }

        /* ----- Upcoming competence evaluations section ----- */
        .eval-section {
          background: var(--eval-section-bg);
          border: 1px solid var(--eval-section-border);
          border-radius: 10px;
          padding: 1rem 1.125rem 0.875rem;
          margin-bottom: 1.5rem;
        }

        .eval-section__title {
          font-size: 0.8125rem;
          font-weight: 700;
          letter-spacing: 0.06em;
          text-transform: uppercase;
          color: var(--bdg-eval-fg);
          margin-bottom: 0.625rem;
        }

        .eval-list {
          list-style: none;
          display: flex;
          flex-direction: column;
          gap: 0.5rem;
        }

        .eval-item {
          display: flex;
          align-items: center;
          gap: 0.625rem;
          padding: 0.5rem 0.75rem;
          background: var(--surface);
          border-radius: 7px;
          border-left: 3px solid transparent;
        }

        .eval-item__subject {
          font-weight: 600;
          font-size: 0.875rem;
          color: var(--text-1);
          flex: 1;
        }

        .eval-item__date {
          font-size: 0.8125rem;
          color: var(--text-2);
          white-space: nowrap;
        }

        /* ----- Attachments ----- */
        .assignment__attachments {
          list-style: none;
          display: flex;
          flex-direction: column;
          gap: 0.3rem;
        }

        .attachment__link {
          display: inline-flex;
          align-items: center;
          gap: 0.35rem;
          font-size: 0.8125rem;
          font-weight: 500;
          color: var(--attach-fg);
          background: var(--attach-bg);
          border: 1px solid var(--attach-border);
          padding: 0.25rem 0.5625rem;
          border-radius: 5px;
          text-decoration: none;
          word-break: break-all;
          max-width: 100%;
        }

        .attachment__link:hover { text-decoration: underline; }

        /* ----- Empty state ----- */
        .empty-state {
          text-align: center;
          padding: 4rem 1rem;
          color: var(--text-3);
          font-size: 0.9375rem;
        }

        /* ================================================================
           Print styles
           ================================================================ */
        @media print {
          :root {
            --bg:      #ffffff;
            --surface: #ffffff;
            --text-1:  #000000;
            --text-2:  #444444;
            --text-3:  #888888;
            --border:  #cccccc;

            --bdg-done-bg:   #e8f5e9; --bdg-done-fg:   #1b5e20;
            --bdg-eval-bg:   #fef9c3; --bdg-eval-fg:   #78350f;
            --eval-section-bg:#fffef0; --eval-section-border: #d97706;
            --attach-bg:     #e3f2fd; --attach-fg:     #0d47a1;
            --attach-border: #bbdefb;
          }

          body { padding: 0; background: white; }

          .page { max-width: 100%; }

          .page-header { padding: 0.5rem 0 0.75rem; }

          .page-header__subtitle { display: none; }

          .assignment-card {
            box-shadow: none;
            border: 1px solid #cccccc;
            break-inside: avoid;
          }

          .date-group { break-inside: avoid; }

          .subject-group { break-inside: avoid; }

          .attachment__link {
            color: var(--attach-fg);
          }

          /* Show the href after the link text for printed copies */
          .attachment__link::after {
            content: " (" attr(href) ")";
            font-size: 0.6875rem;
            color: #666666;
            word-break: break-all;
          }
        }
        """;
}
