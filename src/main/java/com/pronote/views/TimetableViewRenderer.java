package com.pronote.views;

import com.pronote.config.AppConfig;
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
import java.util.List;
import java.util.Locale;

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
     * @param allEntries the full timetable snapshot (all weeks, all days)
     */
    public void render(List<TimetableEntry> allEntries) {
        Path outDir = Path.of(viewConfig.getOutputDirectory());
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create timetable view output directory: " + outDir, e);
        }

        List<LocalDate> dates = upcomingWeekdays(viewConfig.getDaysAhead());
        log.info("Generating timetable views for {} days in {}", dates.size(), outDir);

        for (LocalDate date : dates) {
            List<TimetableEntry> dayEntries = entriesForDate(allEntries, date);
            String html = generator.generate(date, dayEntries);
            Path file = outDir.resolve(date + ".html");
            writeFile(file, html);
            log.debug("Written {}", file.getFileName());
        }

        String indexHtml = generateIndex(dates, allEntries);
        writeFile(outDir.resolve("index.html"), indexHtml);
        log.info("Timetable views written to {}", outDir);
    }

    // -------------------------------------------------------------------------
    // Index page
    // -------------------------------------------------------------------------

    private String generateIndex(List<LocalDate> dates, List<TimetableEntry> allEntries) {
        String generatedAt = capitalize(LocalDateTime.now().format(DATETIME_FMT));

        StringBuilder cards = new StringBuilder();
        for (LocalDate date : dates) {
            List<TimetableEntry> dayEntries = entriesForDate(allEntries, date);
            cards.append(renderDayCard(date, dayEntries));
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

    private String renderDayCard(LocalDate date, List<TimetableEntry> entries) {
        String dow      = capitalize(date.format(DAY_OF_WEEK_FMT));
        String shortDt  = capitalize(date.format(SHORT_DATE_FMT));
        String href     = date + ".html";
        String datetime = date.toString();

        String metaLine;
        String alertLine = "";

        if (entries.isEmpty()) {
            metaLine = "Pas de cours";
        } else {
            List<TimetableEntry> sorted = TimetableHtmlGenerator.filterRoomChanges(entries).stream()
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

    private static void writeFile(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write timetable view: " + path, e);
        }
    }
}
