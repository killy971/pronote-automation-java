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
import java.util.TreeMap;

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
            .filter(a -> !isBlankAssignment(a))
            .sorted(Comparator.comparing(Assignment::getDueDate)
                .thenComparing(a -> displaySubject(a)))
            .toList();

        List<TimetableEntry> upcomingEvals = timetable.stream()
            .filter(e -> e.isEval()
                      && e.getStartTime() != null
                      && !e.getStartTime().toLocalDate().isBefore(today))
            .sorted(Comparator.comparing(TimetableEntry::getStartTime))
            .toList();

        // Group evals by date for injection into the main assignment date groups
        Map<LocalDate, List<TimetableEntry>> evalsByDate = new LinkedHashMap<>();
        for (TimetableEntry e : upcomingEvals) {
            evalsByDate.computeIfAbsent(e.getStartTime().toLocalDate(), k -> new ArrayList<>()).add(e);
        }

        String evalBanner = upcomingEvals.isEmpty() ? "" : renderEvalBanner(upcomingEvals);

        String content = upcoming.isEmpty() && evalsByDate.isEmpty()
            ? "      <p class=\"empty-state\">Aucun devoir \u00e0 venir.</p>\n"
            : renderAssignments(upcoming, evalsByDate, outputDir);

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
            + evalBanner
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

    /** Compact amber banner listing upcoming evals — one line per entry. */
    private String renderEvalBanner(List<TimetableEntry> evals) {
        StringBuilder sb = new StringBuilder();
        sb.append("    <div class=\"eval-banner\">\n");
        sb.append("      <span class=\"eval-banner__title\">\u00c9val. de comp\u00e9tences \u00e0 venir\u00a0:</span>\n");
        sb.append("      <ul class=\"eval-banner__list\">\n");
        for (TimetableEntry e : evals) {
            LocalDate date = e.getStartTime().toLocalDate();
            String subject = e.getEnrichedSubject() != null && !e.getEnrichedSubject().isBlank()
                ? e.getEnrichedSubject() : e.getSubject();
            String shortDate = capitalize(date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)));
            sb.append("        <li>").append(esc(subject))
              .append(" <span class=\"eval-banner__date\">\u2014\u00a0").append(esc(shortDate)).append("</span></li>\n");
        }
        sb.append("      </ul>\n");
        sb.append("    </div>\n");
        return sb.toString();
    }

    private String renderAssignments(List<Assignment> upcoming,
                                      Map<LocalDate, List<TimetableEntry>> evalsByDate,
                                      Path outputDir) {
        Map<LocalDate, List<Assignment>> byDate = new LinkedHashMap<>();
        for (Assignment a : upcoming) {
            byDate.computeIfAbsent(a.getDueDate(), k -> new ArrayList<>()).add(a);
        }

        // Merge dates from both assignments and evals, sorted ascending
        Map<LocalDate, List<Assignment>> merged = new TreeMap<>();
        byDate.forEach(merged::put);
        evalsByDate.keySet().forEach(d -> merged.computeIfAbsent(d, k -> new ArrayList<>()));

        StringBuilder sb = new StringBuilder();
        for (LocalDate date : merged.keySet()) {
            List<Assignment> dayAssignments = byDate.getOrDefault(date, List.of());
            List<TimetableEntry> dayEvals = evalsByDate.getOrDefault(date, List.of());
            sb.append(renderDateGroup(date, dayAssignments, dayEvals, outputDir));
        }
        return sb.toString();
    }

    private String renderDateGroup(LocalDate date, List<Assignment> dayAssignments,
                                    List<TimetableEntry> dayEvals, Path outputDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("      <section class=\"date-group\" id=\"date-").append(date.format(DATE_ATTR_FMT)).append("\">\n");
        sb.append("        <h2 class=\"date-group__heading\">")
          .append("<time datetime=\"").append(date.format(DATE_ATTR_FMT)).append("\">")
          .append(esc(capitalize(date.format(DATE_HEADER_FMT))))
          .append("</time></h2>\n");

        // Regular assignments grouped by subject
        Map<String, List<Assignment>> bySubject = new LinkedHashMap<>();
        for (Assignment a : dayAssignments) {
            bySubject.computeIfAbsent(displaySubject(a), k -> new ArrayList<>()).add(a);
        }
        for (Map.Entry<String, List<Assignment>> subjectEntry : bySubject.entrySet()) {
            sb.append(renderSubjectGroup(subjectEntry.getKey(), subjectEntry.getValue(), outputDir));
        }

        // Synthetic eval cards appended after regular assignments
        for (TimetableEntry e : dayEvals) {
            String subject = e.getEnrichedSubject() != null && !e.getEnrichedSubject().isBlank()
                ? e.getEnrichedSubject() : e.getSubject();
            String color = ACCENT_COLORS[Math.abs(e.getSubject().hashCode()) % ACCENT_COLORS.length];
            String label = e.getLessonLabel() != null ? e.getLessonLabel() : "\u00c9val. de comp\u00e9tences";
            sb.append("        <div class=\"subject-group\">\n");
            sb.append("          <div class=\"subject-group__header\" style=\"border-left-color:").append(color).append("\">\n");
            sb.append("            <span class=\"subject-group__name\">").append(esc(subject)).append("</span>\n");
            sb.append("          </div>\n");
            sb.append("          <div class=\"assignment-card assignment-card--eval\">\n");
            sb.append("            <p class=\"assignment__description\">").append(esc(label)).append("</p>\n");
            sb.append("          </div>\n");
            sb.append("        </div>\n");
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

    /**
     * Returns true when an assignment carries no meaningful content and should be hidden.
     * Matches entries where teachers cleared the description without deleting the entry:
     * null/blank description, or a description consisting entirely of non-alphanumeric
     * characters (e.g. ".", "-", "...", "---"), AND no downloadable or linked attachments.
     */
    static boolean isBlankAssignment(Assignment a) {
        String desc = a.getDescription();
        boolean trivialDesc = desc == null || desc.isBlank()
            || desc.strip().chars().allMatch(c -> !Character.isLetterOrDigit(c));
        if (!trivialDesc) return false;
        List<AttachmentRef> refs = a.getAttachments();
        boolean hasContent = refs != null && refs.stream()
            .anyMatch(r -> (r.isUploadedFile() && r.getLocalPath() != null)
                        || (!r.isUploadedFile() && r.getUrl() != null));
        return !hasContent;
    }

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

          --bdg-done-bg:     #dcfce7; --bdg-done-fg:    #15803d;
          --bdg-eval-bg:     #fef3c7; --bdg-eval-fg:    #92400e;
          --eval-banner-bg:  #fffbeb; --eval-banner-border: #fcd34d;
          --eval-card-bg:    #fffdf5;
          --attach-bg:       #f0f7ff; --attach-fg:      #1e40af;
          --attach-border:   #bfdbfe;
        }

        @media (prefers-color-scheme: dark) {
          :root {
            --bg:      #0c0d13;
            --surface: #14151f;
            --text-1:  #e2e8f0;
            --text-2:  #7c84a0;
            --text-3:  #4a5068;
            --border:  #1e2030;

            --bdg-done-bg:    #052e16; --bdg-done-fg:   #4ade80;
            --bdg-eval-bg:    #3d2800; --bdg-eval-fg:   #fcd34d;
            --eval-banner-bg: #1c1500; --eval-banner-border: #854d0e;
            --eval-card-bg:   #1a1200;
            --attach-bg:      #0c1f40; --attach-fg:     #93c5fd;
            --attach-border:  #1e3a5f;
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

        /* ----- Eval banner (compact summary above assignments) ----- */
        .eval-banner {
          display: flex;
          flex-wrap: wrap;
          align-items: baseline;
          gap: 0.25rem 0.5rem;
          background: var(--eval-banner-bg);
          border: 1px solid var(--eval-banner-border);
          border-radius: 8px;
          padding: 0.625rem 0.875rem;
          margin-bottom: 1.25rem;
          font-size: 0.8125rem;
        }

        .eval-banner__title {
          font-weight: 700;
          color: var(--bdg-eval-fg);
          white-space: nowrap;
        }

        .eval-banner__list {
          list-style: none;
          display: flex;
          flex-wrap: wrap;
          gap: 0 0.75rem;
          margin: 0;
          padding: 0;
        }

        .eval-banner__list li {
          color: var(--text-1);
          font-weight: 500;
        }

        .eval-banner__date {
          color: var(--text-2);
          font-weight: 400;
        }

        /* ----- Synthetic eval card inside date groups ----- */
        .assignment-card--eval {
          background: var(--eval-card-bg);
          border-left: 3px solid var(--eval-banner-border);
        }

        .assignment-card--eval .assignment__description {
          color: var(--bdg-eval-fg);
          font-style: italic;
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

            --bdg-done-bg:    #e8f5e9; --bdg-done-fg:   #1b5e20;
            --bdg-eval-bg:    #fef9c3; --bdg-eval-fg:   #78350f;
            --eval-banner-bg: #fffef0; --eval-banner-border: #d97706;
            --eval-card-bg:   #fffef5;
            --attach-bg:      #e3f2fd; --attach-fg:     #0d47a1;
            --attach-border:  #bbdefb;
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
