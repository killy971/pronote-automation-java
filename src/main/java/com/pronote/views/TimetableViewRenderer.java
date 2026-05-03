package com.pronote.views;

import com.pronote.config.AppConfig;
import com.pronote.domain.Assignment;
import com.pronote.domain.EntryStatus;
import com.pronote.domain.TimetableEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Generates and writes static HTML timetable view files.
 *
 * <p>On each invocation, {@link #render} produces:
 * <ul>
 *   <li>One {@code YYYY-MM-DD.html} per upcoming weekday configured by {@code timetableView.daysAhead}.</li>
 *   <li>An {@code index.html} overview linking to all day pages.</li>
 * </ul>
 * All files are written to {@code timetableView.outputDirectory}. The operation is idempotent —
 * files are overwritten on every run, always reflecting the current snapshot.
 */
public class TimetableViewRenderer {

    private static final Logger log = LoggerFactory.getLogger(TimetableViewRenderer.class);

    private static final DateTimeFormatter DAY_OF_WEEK_FMT =
        DateTimeFormatter.ofPattern("EEEE", Locale.FRENCH);
    private static final DateTimeFormatter SHORT_DATE_FMT =
        DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH);
    private static final DateTimeFormatter DATETIME_FMT =
        DateTimeFormatter.ofPattern("d MMMM yyyy '\u00e0' HH'h'mm", Locale.FRENCH);

    private final AppConfig.TimetableViewConfig viewConfig;
    private final TimetableHtmlGenerator generator = new TimetableHtmlGenerator();

    public TimetableViewRenderer(AppConfig.TimetableViewConfig viewConfig) {
        this.viewConfig = viewConfig;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates all HTML view files from the given timetable snapshot.
     *
     * @param allEntries    the full timetable snapshot (all weeks, all days)
     * @param allAssignments all assignments; used to annotate lesson cards with due-today counts.
     *                       Pass an empty list to suppress assignment chips.
     */
    public void render(List<TimetableEntry> allEntries, List<Assignment> allAssignments) {
        Path outDir = Path.of(viewConfig.getOutputDirectory());
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create timetable view output directory: " + outDir, e);
        }

        List<LocalDate> dates = upcomingWeekdays(viewConfig.getDaysAhead());
        log.info("Generating timetable views for {} days in {}", dates.size(), outDir);

        for (int i = 0; i < dates.size(); i++) {
            LocalDate date     = dates.get(i);
            LocalDate prevDate = i > 0              ? dates.get(i - 1) : null;
            LocalDate nextDate = i < dates.size()-1 ? dates.get(i + 1) : null;
            List<TimetableEntry> dayEntries = entriesForDate(allEntries, date);
            Map<String, List<Assignment>> assignsBySubject = groupAssignmentsBySubject(allAssignments, date);
            String html = generator.generate(date, prevDate, nextDate, dayEntries, assignsBySubject);
            Path file = outDir.resolve(date + ".html");
            writeFile(file, html);
            log.debug("Written {}", file.getFileName());
        }

        String indexHtml = generateIndex(dates, allEntries, allAssignments);
        writeFile(outDir.resolve("index.html"), indexHtml);

        generateCurrentPage(outDir, dates, allEntries, allAssignments);
        log.info("Timetable views written to {}", outDir);
    }

    // -------------------------------------------------------------------------
    // Index page
    // -------------------------------------------------------------------------

    private String generateIndex(List<LocalDate> dates, List<TimetableEntry> allEntries,
                                  List<Assignment> allAssignments) {
        String generatedAt = capitalize(LocalDateTime.now().format(DATETIME_FMT));

        StringBuilder cards = new StringBuilder();
        for (LocalDate date : dates) {
            List<TimetableEntry> dayEntries = entriesForDate(allEntries, date);
            if (dayEntries.isEmpty()) continue;
            List<Assignment> dayAssigns = allAssignments.stream()
                .filter(a -> date.equals(a.getDueDate()) && !AssignmentHtmlGenerator.isBlankAssignment(a))
                .toList();
            long totalAssignCount = dayAssigns.size();
            long doneAssignCount  = dayAssigns.stream().filter(Assignment::isDone).count();
            cards.append(renderDayCard(date, dayEntries, totalAssignCount, doneAssignCount));
        }

        return "<!DOCTYPE html>\n"
            + "<html lang=\"fr\">\n"
            + "<head>\n"
            + "  <meta charset=\"UTF-8\">\n"
            + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
            + "  <title>Emploi du temps</title>\n"
            + "  <style>" + TimetableHtmlGenerator.CSS + "</style>\n"
            + "</head>\n"
            + "<body>\n"
            + "  <div class=\"page\">\n"
            + "    <nav class=\"nav\"><a class=\"nav__back\" href=\"../index.html\">\u2190\u00a0Pronote</a></nav>\n"
            + "    <header class=\"index-header\">\n"
            + "      <h1 class=\"index-header__title\">Emploi du temps</h1>\n"
            + "      <p class=\"index-header__subtitle\">Mis \u00e0 jour le " + generatedAt + "</p>\n"
            + "    </header>\n"
            + "    <nav class=\"days-grid\" aria-label=\"Jours de la semaine\">\n"
            + cards
            + "    </nav>\n"
            + "  </div>\n"
            + "</body>\n"
            + "</html>\n";
    }

    private String renderDayCard(LocalDate date, List<TimetableEntry> entries,
                                  long totalAssignCount, long doneAssignCount) {
        String dow      = capitalize(date.format(DAY_OF_WEEK_FMT));
        String shortDt  = capitalize(date.format(SHORT_DATE_FMT));
        String href     = date + ".html";
        String datetime = date.toString();

        String metaLine;
        String alertLine = "";

        if (entries.isEmpty()) {
            metaLine = "Pas de cours";
        } else {
            List<TimetableEntry> sorted = TimetableHtmlGenerator.collapseSlots(entries).stream()
                .map(TimetableHtmlGenerator.MergedEntry::entry)
                .sorted(Comparator.comparing(TimetableEntry::getStartTime))
                .toList();

            String firstTime = formatHour(sorted.get(0).getStartTime());
            String lastTime  = formatHour(sorted.get(sorted.size() - 1).getEndTime());
            long count       = sorted.stream()
                .filter(e -> e.getStatus() != EntryStatus.CANCELLED)
                .count();
            long cancelled   = sorted.stream()
                .filter(e -> e.getStatus() == EntryStatus.CANCELLED)
                .count();

            metaLine = count + "\u00a0cours\u00a0\u00b7\u00a0" + firstTime + "\u2013" + lastTime;
            if (cancelled > 0) {
                alertLine = "\n      <div class=\"day-card__alert\">"
                    + "\u26a0\ufe0f\u00a0" + cancelled + "\u00a0annulation" + (cancelled > 1 ? "s" : "")
                    + "</div>";
            }
        }

        if (totalAssignCount > 0) {
            boolean allDone = doneAssignCount == totalAssignCount;
            if (allDone) {
                String label = totalAssignCount + "\u00a0devoir" + (totalAssignCount > 1 ? "s" : "");
                metaLine += "\u00a0\u00b7\u00a0<span class=\"day-card__assign-done\">" + label + "</span>";
            } else {
                long undone = totalAssignCount - doneAssignCount;
                metaLine += "\u00a0\u00b7\u00a0" + undone + "\u00a0devoir" + (undone > 1 ? "s" : "");
            }
        }

        long evalCount = entries.stream().filter(TimetableEntry::isEval).count();
        if (evalCount > 0) {
            metaLine += "\u00a0\u00b7\u00a0<span class=\"day-card__eval-note\">"
                + evalCount + "\u00a0\u00e9val" + (evalCount > 1 ? "s" : "") + "."
                + "</span>";
        }

        String emptyClass = entries.isEmpty() ? " day-card--empty" : "";

        return "      <a class=\"day-card" + emptyClass + "\" href=\"" + href + "\">\n"
             + "        <div class=\"day-card__dow\">" + TimetableHtmlGenerator.esc(dow) + "</div>\n"
             + "        <div class=\"day-card__date\"><time datetime=\"" + datetime + "\">"
             + TimetableHtmlGenerator.esc(shortDt) + "</time></div>\n"
             + "        <div class=\"day-card__meta\">" + metaLine + "</div>"
             + alertLine + "\n"
             + "      </a>\n";
    }

    // -------------------------------------------------------------------------
    // Current page (today / next non-empty day)
    // -------------------------------------------------------------------------

    /**
     * Writes {@code current.html}: the current day's timetable if we are still within 90 minutes
     * of the last class of the day; otherwise the next non-empty weekday in the snapshot.
     */
    private void generateCurrentPage(Path outDir, List<LocalDate> dates,
                                     List<TimetableEntry> allEntries,
                                     List<Assignment> allAssignments) {
        LocalDate target = computeCurrentPageDate(dates, allEntries);
        String html;
        if (target == null) {
            html = generateNoUpcomingPage();
        } else {
            int idx = dates.indexOf(target);
            LocalDate prevDate = idx > 0              ? dates.get(idx - 1) : null;
            LocalDate nextDate = idx >= 0 && idx < dates.size() - 1 ? dates.get(idx + 1) : null;
            List<TimetableEntry> dayEntries = entriesForDate(allEntries, target);
            Map<String, List<Assignment>> assignsBySubject = groupAssignmentsBySubject(allAssignments, target);
            html = generator.generate(target, prevDate, nextDate, dayEntries, assignsBySubject);
        }
        writeFile(outDir.resolve("current.html"), html);
        log.debug("Written current.html (target={})", target);
    }

    /**
     * Returns the date whose timetable {@code current.html} should display.
     *
     * <p>Shows today when today has classes and the current time is no more than 90 minutes
     * past the last class's end time. Otherwise returns the nearest upcoming non-empty weekday
     * in the dates list, or {@code null} if none exists.
     */
    private LocalDate computeCurrentPageDate(List<LocalDate> dates, List<TimetableEntry> allEntries) {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        List<TimetableEntry> todayEntries = entriesForDate(allEntries, today);
        if (!todayEntries.isEmpty()) {
            Optional<LocalDateTime> lastEnd = todayEntries.stream()
                .filter(e -> e.getEndTime() != null)
                .map(TimetableEntry::getEndTime)
                .max(Comparator.naturalOrder());
            if (lastEnd.isPresent() && now.isBefore(lastEnd.get().plusMinutes(90))) {
                return today;
            }
        }

        LocalDate tomorrow = today.plusDays(1);
        return dates.stream()
            .filter(d -> !d.isBefore(tomorrow))
            .filter(d -> !entriesForDate(allEntries, d).isEmpty())
            .findFirst()
            .orElse(null);
    }

    private String generateNoUpcomingPage() {
        return "<!DOCTYPE html>\n"
            + "<html lang=\"fr\">\n"
            + "<head>\n"
            + "  <meta charset=\"UTF-8\">\n"
            + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
            + "  <title>Emploi du temps</title>\n"
            + "  <style>" + TimetableHtmlGenerator.CSS + "</style>\n"
            + "</head>\n"
            + "<body>\n"
            + "  <div class=\"page\">\n"
            + "    <nav class=\"nav\"><a class=\"nav__back\" href=\"index.html\">← Semaine</a></nav>\n"
            + "    <p class=\"empty-day\">Aucun cours à venir dans le calendrier chargé.</p>\n"
            + "  </div>\n"
            + "</body>\n"
            + "</html>\n";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the next {@code n} weekdays (Monday–Friday) starting from today (inclusive).
     * Weekend days are skipped; a Saturday/Sunday "today" advances to Monday.
     */
    private static List<LocalDate> upcomingWeekdays(int n) {
        List<LocalDate> result = new ArrayList<>(n);
        LocalDate d = LocalDate.now();
        while (result.size() < n) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                result.add(d);
            }
            d = d.plusDays(1);
        }
        return result;
    }

    private static List<TimetableEntry> entriesForDate(List<TimetableEntry> all, LocalDate date) {
        return all.stream()
            .filter(e -> e.getStartTime() != null && e.getStartTime().toLocalDate().equals(date))
            .sorted(Comparator.comparing(TimetableEntry::getStartTime))
            .toList();
    }

    private static String formatHour(LocalDateTime dt) {
        if (dt == null) return "?";
        return dt.getHour() + "h" + String.format("%02d", dt.getMinute());
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Groups assignments by display-subject for the given date.
     * All assignments due on {@code date} are included regardless of done status
     * (done ones are rendered greyed in the popup).
     */
    private static Map<String, List<Assignment>> groupAssignmentsBySubject(
            List<Assignment> allAssignments, LocalDate date) {
        Map<String, List<Assignment>> result = new HashMap<>();
        for (Assignment a : allAssignments) {
            if (date.equals(a.getDueDate()) && !AssignmentHtmlGenerator.isBlankAssignment(a)) {
                String subject = displaySubject(a);
                if (subject != null && !subject.isBlank()) {
                    result.computeIfAbsent(subject, k -> new ArrayList<>()).add(a);
                }
            }
        }
        return result;
    }

    private static String displaySubject(Assignment a) {
        String enriched = a.getEnrichedSubject();
        return (enriched != null && !enriched.isBlank()) ? enriched : a.getSubject();
    }

    private static void writeFile(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write timetable view: " + path, e);
        }
    }
}
