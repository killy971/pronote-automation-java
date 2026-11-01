package com.pronote.views;

import com.pronote.config.AppConfig;
import com.pronote.domain.Assignment;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writes the static HTML timetable view files. All HTML lives in
 * {@link TimetableHtmlGenerator}; this class only handles date selection and file I/O.
 *
 * <p>On each invocation, {@link #render} produces:
 * <ul>
 *   <li>One {@code YYYY-MM-DD.html} per upcoming weekday configured by {@code timetableView.daysAhead}.</li>
 *   <li>An {@code index.html} overview linking to all day pages.</li>
 *   <li>A {@code current.html} for today (if classes are ongoing) or the next non-empty weekday.</li>
 * </ul>
 * All files are written to {@code timetableView.outputDirectory}. The operation is idempotent —
 * files are overwritten on every run, always reflecting the current snapshot.
 */
public class TimetableViewRenderer {

    private static final Logger log = LoggerFactory.getLogger(TimetableViewRenderer.class);

    private final AppConfig.TimetableViewConfig viewConfig;
    private final TimetableHtmlGenerator generator = new TimetableHtmlGenerator();

    /** Applies the configured subject-colour source to the generator. */
    public void setColorResolver(SubjectColorResolver colors) {
        generator.setColorResolver(colors);
    }

    /** Applies the configured subject icons to the generator. */
    public void setIconResolver(SubjectIconResolver icons) {
        generator.setIconResolver(icons);
    }

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
            writeFile(outDir.resolve(date + ".html"),
                    generateDayPage(date, prevDate, nextDate, allEntries, allAssignments));
            log.debug("Written {}.html", date);
        }

        writeFile(outDir.resolve("index.html"),
                generator.generateIndex(dates, allEntries, allAssignments));

        generateCurrentPage(outDir, dates, allEntries, allAssignments);
        log.info("Timetable views written to {}", outDir);
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
            html = generator.generateNoUpcomingPage();
        } else {
            int idx = dates.indexOf(target);
            LocalDate prevDate = idx > 0              ? dates.get(idx - 1) : null;
            LocalDate nextDate = idx >= 0 && idx < dates.size() - 1 ? dates.get(idx + 1) : null;
            html = generateDayPage(target, prevDate, nextDate, allEntries, allAssignments);
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

        List<TimetableEntry> todayEntries = TimetableHtmlGenerator.entriesForDate(allEntries, today);
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
            .filter(d -> !TimetableHtmlGenerator.entriesForDate(allEntries, d).isEmpty())
            .findFirst()
            .orElse(null);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String generateDayPage(LocalDate date, LocalDate prevDate, LocalDate nextDate,
                                   List<TimetableEntry> allEntries, List<Assignment> allAssignments) {
        List<TimetableEntry> dayEntries = TimetableHtmlGenerator.entriesForDate(allEntries, date);
        Map<String, List<Assignment>> assignsBySubject = groupAssignmentsBySubject(allAssignments, date);
        return generator.generate(date, prevDate, nextDate, dayEntries, assignsBySubject);
    }

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
