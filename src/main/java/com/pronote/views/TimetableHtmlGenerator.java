package com.pronote.views;

import com.pronote.domain.Assignment;
import com.pronote.domain.EntryStatus;
import com.pronote.domain.TimetableEntry;
import com.pronote.domain.TimetableSlots;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Generates a self-contained HTML5 page for a single day's timetable.
 *
 * <p>Each generated document:
 * <ul>
 *   <li>Embeds all CSS — no external resources needed.</li>
 *   <li>Adapts to system light/dark preference via {@code prefers-color-scheme}.</li>
 *   <li>Is responsive: single-column layout fits both mobile (360 px) and desktop.</li>
 *   <li>Requires no JavaScript.</li>
 * </ul>
 */
public class TimetableHtmlGenerator {

    /** Resolves each subject's accent colour; see {@link SubjectColorResolver}. */
    private SubjectColorResolver colors = SubjectColorResolver.paletteOnly();

    /** Resolves each subject's emoji; see {@link SubjectIconResolver}. Off unless configured. */
    private SubjectIconResolver icons = SubjectIconResolver.disabled();

    private static final DateTimeFormatter FULL_DATE_FMT =
        DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH);
    private static final DateTimeFormatter SHORT_DATE_FMT =
        DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH);

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates the full HTML document for the given day.
     *
     * @param date                  the date to render
     * @param entries               all timetable entries for this specific day (may be empty)
     * @param assignmentsBySubject  assignments due this day, keyed by display-subject name;
     *                              empty map means no assignment chips will be rendered
     * @return a complete, self-contained HTML5 document
     */
    public String generate(LocalDate date, LocalDate prevDate, LocalDate nextDate,
                           List<TimetableEntry> entries,
                           Map<String, List<Assignment>> assignmentsBySubject) {
        String fullDate  = capitalize(date.format(FULL_DATE_FMT));
        String titleDate = capitalize(date.format(SHORT_DATE_FMT));
        String schedule  = entries.isEmpty()
            ? "<p class=\"empty-day\">Pas de cours ce jour.</p>\n"
            : renderSchedule(entries, assignmentsBySubject);

        boolean hasChips = !assignmentsBySubject.isEmpty();

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n")
          .append("<html lang=\"fr\">\n")
          .append("<head>\n")
          .append("  <meta charset=\"UTF-8\">\n")
          .append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
          .append("  <title>Emploi du temps \u2014 ").append(titleDate).append("</title>\n")
          .append("  <style>").append(CSS).append("</style>\n")
          .append("</head>\n")
          .append("<body>\n")
          .append("  <div class=\"page\">\n")
          .append("    <nav class=\"nav\">\n")
          .append("      <a class=\"nav__back\" href=\"index.html\">\u2190\u00a0Semaine</a>\n")
          .append("      <div class=\"nav__pager\">\n");

        if (prevDate != null) {
            sb.append("        <a class=\"nav__page-link\" href=\"").append(prevDate).append(".html\">Pr\u00e9c\u00e9dent</a>\n");
        } else {
            sb.append("        <span class=\"nav__page-link nav__page-link--disabled\">Pr\u00e9c\u00e9dent</span>\n");
        }
        if (nextDate != null) {
            sb.append("        <a class=\"nav__page-link\" href=\"").append(nextDate).append(".html\">Suivant</a>\n");
        } else {
            sb.append("        <span class=\"nav__page-link nav__page-link--disabled\">Suivant</span>\n");
        }

        sb.append("      </div>\n")
          .append("    </nav>\n")
          .append("    <header class=\"day-header\">\n")
          .append("      <h1 class=\"day-header__date\"><time datetime=\"").append(date).append("\">").append(fullDate).append("</time></h1>\n")
          .append("    </header>\n")
          .append("    ").append(schedule)
          .append("  </div>\n");

        if (hasChips) {
            sb.append("\n")
              .append("  <dialog id=\"assign-dialog\" aria-modal=\"true\" aria-label=\"Devoirs\">\n")
              .append("    <div class=\"dialog-inner\">\n")
              .append("      <button class=\"dialog__close\" id=\"assign-dialog-close\" aria-label=\"Fermer\">\u00d7</button>\n")
              .append("      <div id=\"assign-dialog-body\"></div>\n")
              .append("    </div>\n")
              .append("  </dialog>\n")
              .append("\n")
              .append("  <script>").append(ASSIGN_JS).append("</script>\n");
        }

        sb.append("</body>\n")
          .append("</html>\n");

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Week index page and "no upcoming day" page
    // -------------------------------------------------------------------------

    private static final DateTimeFormatter DAY_OF_WEEK_FMT =
        DateTimeFormatter.ofPattern("EEEE", Locale.FRENCH);
    private static final DateTimeFormatter CARD_DATE_FMT =
        DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH);

    /**
     * Generates the week overview page: one card per date linking to its day page,
     * annotated with lesson count, time range, assignment and eval counts.
     */
    public String generateIndex(List<LocalDate> dates, List<TimetableEntry> allEntries,
                                List<Assignment> allAssignments) {
        String generatedAt = HtmlText.generatedAt();

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
            + "  <style>" + CSS + "</style>\n"
            + "</head>\n"
            + "<body>\n"
            + "  <div class=\"page\">\n"
            + "    <nav class=\"nav\"><a class=\"nav__back\" href=\"../index.html\">←\u00a0Pronote</a></nav>\n"
            + "    <header class=\"index-header\">\n"
            + "      <h1 class=\"index-header__title\">Emploi du temps</h1>\n"
            + "      <p class=\"index-header__subtitle\">Mis à jour le " + generatedAt + "</p>\n"
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
        String shortDt  = capitalize(date.format(CARD_DATE_FMT));
        String href     = date + ".html";
        String datetime = date.toString();

        String metaLine;
        String alertLine = "";

        if (entries.isEmpty()) {
            metaLine = "Pas de cours";
        } else {
            List<TimetableEntry> sorted = collapseSlots(entries).stream()
                .map(MergedEntry::entry)
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

            metaLine = count + "\u00a0cours\u00a0·\u00a0" + firstTime + "–" + lastTime;
            if (cancelled > 0) {
                alertLine = "\n      <div class=\"day-card__alert\">"
                    + "⚠️\u00a0" + cancelled + "\u00a0annulation" + (cancelled > 1 ? "s" : "")
                    + "</div>";
            }
        }

        if (totalAssignCount > 0) {
            boolean allDone = doneAssignCount == totalAssignCount;
            if (allDone) {
                String label = totalAssignCount + "\u00a0devoir" + (totalAssignCount > 1 ? "s" : "");
                metaLine += "\u00a0·\u00a0<span class=\"day-card__assign-done\">" + label + "</span>";
            } else {
                long undone = totalAssignCount - doneAssignCount;
                metaLine += "\u00a0·\u00a0" + undone + "\u00a0devoir" + (undone > 1 ? "s" : "");
            }
        }

        long evalCount = entries.stream().filter(TimetableEntry::isEval).count();
        if (evalCount > 0) {
            metaLine += "\u00a0·\u00a0<span class=\"day-card__eval-note\">"
                + evalCount + "\u00a0éval" + (evalCount > 1 ? "s" : "") + "."
                + "</span>";
        }

        String emptyClass = entries.isEmpty() ? " day-card--empty" : "";

        return "      <a class=\"day-card" + emptyClass + "\" href=\"" + href + "\">\n"
             + "        <div class=\"day-card__dow\">" + esc(dow) + "</div>\n"
             + "        <div class=\"day-card__date\"><time datetime=\"" + datetime + "\">"
             + esc(shortDt) + "</time></div>\n"
             + "        <div class=\"day-card__meta\">" + metaLine + "</div>"
             + alertLine + "\n"
             + "      </a>\n";
    }

    /** Generates the fallback {@code current.html} page when the snapshot has no upcoming day. */
    public String generateNoUpcomingPage() {
        return "<!DOCTYPE html>\n"
            + "<html lang=\"fr\">\n"
            + "<head>\n"
            + "  <meta charset=\"UTF-8\">\n"
            + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
            + "  <title>Emploi du temps</title>\n"
            + "  <style>" + CSS + "</style>\n"
            + "</head>\n"
            + "<body>\n"
            + "  <div class=\"page\">\n"
            + "    <nav class=\"nav\"><a class=\"nav__back\" href=\"index.html\">←\u00a0Semaine</a></nav>\n"
            + "    <p class=\"empty-day\">Aucun cours à venir dans le calendrier chargé.</p>\n"
            + "  </div>\n"
            + "</body>\n"
            + "</html>\n";
    }

    /** Returns the entries starting on the given date, sorted by start time. */
    public static List<TimetableEntry> entriesForDate(List<TimetableEntry> all, LocalDate date) {
        return all.stream()
            .filter(e -> e.getStartTime() != null && e.getStartTime().toLocalDate().equals(date))
            .sorted(Comparator.comparing(TimetableEntry::getStartTime))
            .toList();
    }

    private static String formatHour(LocalDateTime dt) {
        if (dt == null) return "?";
        return dt.getHour() + "h" + String.format("%02d", dt.getMinute());
    }

    // -------------------------------------------------------------------------
    // Schedule rendering
    // -------------------------------------------------------------------------

    /**
     * Pairs an entry to render with the cancelled lessons it stands in for.
     * {@code cancelledSiblings} holds only the ones with a <em>different</em> subject — a true
     * class replacement. Same-subject pairs (e.g. "cours maintenu" resolutions) are left out,
     * since no annotation is needed when the lesson still happens. The list has more than one
     * element when a single long entry — an outing across the lunch break — takes over several
     * consecutive lessons.
     */
    record MergedEntry(TimetableEntry entry, List<TimetableEntry> cancelledSiblings,
                       String extraEvalLabel) {
        MergedEntry(TimetableEntry entry, List<TimetableEntry> cancelledSiblings) {
            this(entry, cancelledSiblings, null);
        }

        static MergedEntry alone(TimetableEntry entry) {
            return new MergedEntry(entry, List.of(), null);
        }
    }

    /**
     * Collapses slot conflicts into single {@link MergedEntry} objects.
     *
     * <p>When an entry is cancelled and another takes its place, Pronote sends both:
     * <ul>
     *   <li>a CANCELLED entry (the original/old slot), and</li>
     *   <li>an active entry (the replacement or the maintained class).</li>
     * </ul>
     * The cancelled one is hidden and, when the subjects differ, carried on the surviving card as
     * a {@code cancelledSibling} for the "Remplace&nbsp;:" note.
     *
     * <p>Which entry takes a slot over is {@link TimetableSlots}' call, not this method's, and it
     * is not decided by a shared start time: a school outing running 11:00–13:00 takes over
     * every lesson it runs across, which is why the pairing is resolved for the whole day up front
     * rather than within one start-time group. Cancellations nothing covers are kept as-is.
     */
    static List<MergedEntry> collapseSlots(List<TimetableEntry> entries) {
        TimetableSlots slots = TimetableSlots.index(entries);

        // Resolve the day's supersedings first: the entry taking a cancelled lesson over may sit
        // in an earlier start-time group than the lesson itself.
        Map<TimetableEntry, List<TimetableEntry>> takenOver = new LinkedHashMap<>();
        Set<TimetableEntry> hidden = new HashSet<>();
        for (TimetableEntry e : entries) {
            if (!TimetableSlots.isInactive(e)) continue;
            TimetableEntry taker = slots.supersederOf(e);
            if (taker == null) continue;
            takenOver.computeIfAbsent(taker, k -> new ArrayList<>()).add(e);
            hidden.add(e);
        }

        List<MergedEntry> result = new ArrayList<>();
        for (List<TimetableEntry> group : slots.slots()) {
            List<TimetableEntry> active = group.stream().filter(TimetableSlots::isActive).toList();

            // Standalone cancellations — nothing occupies the slot, so show them as-is.
            for (TimetableEntry c : group) {
                if (TimetableSlots.isInactive(c) && !hidden.contains(c)) {
                    result.add(MergedEntry.alone(c));
                }
            }
            if (active.isEmpty()) continue;

            // Separate manual eval entries (synthetic, id starts with "manual:") from real ones.
            // Manual evals at the same slot as a real same-subject entry are absorbed into
            // that entry's extraEvalLabel rather than emitted as a second card.
            Map<String, String> manualEvalBySubject = new LinkedHashMap<>();
            List<TimetableEntry> realActive = new ArrayList<>();
            for (TimetableEntry a : active) {
                if (TimetableSlots.isManual(a)) {
                    manualEvalBySubject.put(a.getSubject(), a.getLessonLabel());
                } else {
                    realActive.add(a);
                }
            }

            if (realActive.isEmpty()) {
                // Only manual eval entries at this slot — emit each as its own card
                for (TimetableEntry a : active) {
                    result.add(MergedEntry.alone(a));
                }
                continue;
            }

            for (TimetableEntry a : realActive) {
                // A same-subject pair is one lesson with changed details, which needs no
                // "Remplace :" note; only a different subject is a replacement worth naming.
                List<TimetableEntry> siblings = takenOver.getOrDefault(a, List.of()).stream()
                    .filter(c -> !Objects.equals(c.getSubject(), a.getSubject()))
                    .toList();
                // Absorb same-subject manual eval; remaining unmatched ones stay in the map
                String extraEvalLabel = manualEvalBySubject.remove(a.getSubject());
                result.add(new MergedEntry(a, siblings, extraEvalLabel));
            }
            // Emit manual evals whose subject had no matching real entry at this slot
            for (TimetableEntry a : active) {
                if (TimetableSlots.isManual(a)
                        && manualEvalBySubject.containsKey(a.getSubject())) {
                    result.add(MergedEntry.alone(a));
                }
            }
        }
        return result;
    }

    private String renderSchedule(List<TimetableEntry> entries,
                                   Map<String, List<Assignment>> assignmentsBySubject) {
        List<MergedEntry> sorted = collapseSlots(entries).stream()
            .sorted(Comparator.comparing(me -> me.entry().getStartTime()))
            .toList();

        StringBuilder sb = new StringBuilder("<main class=\"schedule\">\n");

        // Cursor tracks the next hour slot we should render.
        // Initialised to the first lesson's start hour.
        int cursor = sorted.get(0).entry().getStartTime().getHour();

        // Track which subjects have already received an assignment chip so we only
        // show the chip on the first occurrence of each subject within the day.
        Set<String> seenSubjects = new HashSet<>();

        for (MergedEntry me : sorted) {
            int lessonHour = me.entry().getStartTime().getHour();

            // Fill any gap hours between cursor and this lesson
            while (cursor < lessonHour) {
                sb.append(renderGapSlot(cursor));
                cursor++;
            }

            // First occurrence of this subject gets the assignment chip; subsequent ones don't.
            String subject = displaySubject(me.entry());
            List<Assignment> subjectAssignments = seenSubjects.add(subject)
                ? assignmentsBySubject.getOrDefault(subject, List.of())
                : null;

            sb.append(renderLessonSlot(me, subjectAssignments));

            // Advance cursor to the hour when this lesson ends (ceiling to whole hour).
            // Use max(cursor, endHour) — not cursor+1 — so that same-start-hour lessons
            // (e.g. two entries at 10:00) do not incorrectly skip the next gap hour.
            // The fallback to lessonHour+1 only triggers for zero-duration entries.
            int endHour = me.entry().getEndTime().getHour();
            if (me.entry().getEndTime().getMinute() > 0) endHour++;
            cursor = Math.max(cursor, endHour);
            if (cursor <= lessonHour) cursor = lessonHour + 1;
        }

        sb.append("    </main>\n");
        return sb.toString();
    }

    /**
     * @param subjectAssignments assignments due today for this subject — non-null only for the
     *                           first occurrence of the subject within the day; null means no chip.
     *                           An empty (non-null) list means first occurrence but no assignments.
     */
    private String renderLessonSlot(MergedEntry me, List<Assignment> subjectAssignments) {
        TimetableEntry e = me.entry();
        String accentStyle = colors.styleAttr(e.getSubject());
        boolean cancelled = e.getStatus() == EntryStatus.CANCELLED
                         || e.getStatus() == EntryStatus.EXEMPTED;

        String cardClass   = cancelled ? "lesson lesson--cancelled" : "lesson";
        String borderStyle = cancelled ? "" : " style=\"" + accentStyle + "\"";

        boolean showChip = subjectAssignments != null && !subjectAssignments.isEmpty();
        String subjectLabel = displaySubject(e);

        StringBuilder card = new StringBuilder();
        card.append("      <div class=\"").append(cardClass).append("\"").append(borderStyle).append(">\n");

        // Subject — wrapped with assignment chip when present
        if (showChip) {
            card.append("        <div class=\"lesson__head\">\n");
            card.append("          <div class=\"lesson__subject\">")
                .append(icons.prefix(subjectLabel)).append(esc(subjectLabel)).append("</div>\n");
            card.append(renderAssignChip(subjectLabel, subjectAssignments));
            card.append("        </div>\n");
        } else {
            card.append("        <div class=\"lesson__subject\">")
                .append(icons.prefix(subjectLabel)).append(esc(subjectLabel)).append("</div>\n");
        }

        // Teacher
        if (e.getTeacher() != null && !e.getTeacher().isBlank()) {
            card.append("        <div class=\"lesson__teacher\">").append(esc(e.getTeacher())).append("</div>\n");
        }

        // Room
        if (e.getRoom() != null && !e.getRoom().isBlank()) {
            card.append("        <div class=\"lesson__room\">").append(esc(e.getRoom())).append("</div>\n");
        }

        // Memo
        if (e.getMemo() != null && !e.getMemo().isBlank()) {
            card.append("        <div class=\"lesson__memo\">").append(HtmlText.escapeAndLinkify(e.getMemo())).append("</div>\n");
        }

        // Status / test badges (+ absorbed manual eval label when present)
        List<String> badges = buildBadges(e, subjectLabel);
        if (me.extraEvalLabel() != null) {
            badges.add(badge("eval", me.extraEvalLabel()));
        }
        if (!badges.isEmpty()) {
            card.append("        <div class=\"lesson__footer\">\n");
            for (String b : badges) card.append("          ").append(b).append("\n");
            card.append("        </div>\n");
        }

        // "Replaces" note — one line per different-subject cancelled entry this card took over.
        // A long entry (an outing across the lunch break) displaces several lessons at once.
        for (TimetableEntry sibling : me.cancelledSiblings()) {
            StringBuilder detail = new StringBuilder(esc(displaySubject(sibling)));
            if (sibling.getTeacher() != null && !sibling.getTeacher().isBlank()) {
                detail.append(" \u00b7 ").append(esc(sibling.getTeacher()));
            }
            if (sibling.getRoom() != null && !sibling.getRoom().isBlank()) {
                detail.append(" \u00b7 ").append(esc(sibling.getRoom()));
            }
            card.append("        <div class=\"lesson__replaced\">\n");
            card.append("          <span class=\"lesson__replaced-label\">Remplace\u00a0:</span>\n");
            card.append("          <span class=\"lesson__replaced-detail\">").append(detail).append("</span>\n");
            card.append("        </div>\n");
        }

        card.append("      </div>\n");

        return "    <div class=\"slot\">\n"
             + "      <span class=\"slot__time\">" + slotTime(e) + "</span>\n"
             + card
             + "    </div>\n";
    }

    /**
     * The time shown beside a card: the start alone for an ordinary period, the full span for an
     * entry that outlasts it. A "sortie pédagogique" running 11:00–13:00 occupies two rows of
     * the grid and swallows the lunch marker, so "11h00" on its own would read as a one-hour
     * lesson and leave the rest of the morning unaccounted for.
     */
    private static String slotTime(TimetableEntry e) {
        LocalTime from = e.getStartTime().toLocalTime();
        String head = timeTag(from);
        if (e.getEndTime() == null) return head;
        LocalTime to = e.getEndTime().toLocalTime();
        boolean sameDay = e.getEndTime().toLocalDate().equals(e.getStartTime().toLocalDate());
        if (!sameDay || !to.isAfter(from.plusHours(1))) return head;
        return head + "\n      <span class=\"slot__time-end\">" + timeTag(to) + "</span>";
    }

    // -------------------------------------------------------------------------
    // Assignment chip
    // -------------------------------------------------------------------------

    /** Renders the chip button and its hidden detail panel (cloned into dialog on click). */
    private String renderAssignChip(String displaySubject, List<Assignment> assignments) {
        int count = assignments.size();
        boolean allDone = assignments.stream().allMatch(Assignment::isDone);
        String label = count + "\u00a0devoir" + (count > 1 ? "s" : "");
        String ariaLabel = label + "\u00a0\u2014\u00a0" + esc(displaySubject);
        String chipClass = "lesson__assign-chip" + (allDone ? " lesson__assign-chip--done" : "");
        return "          <div class=\"lesson__assign-wrap\">\n"
             + "            <button class=\"" + chipClass + "\" aria-label=\"" + ariaLabel + "\">"
             + label + "</button>\n"
             + renderAssignDetail(displaySubject, assignments)
             + "          </div>\n";
    }

    /** Hidden panel whose content is cloned into the dialog when the chip is clicked. */
    private String renderAssignDetail(String displaySubject, List<Assignment> assignments) {
        int count = assignments.size();
        String title = count + "\u00a0devoir" + (count > 1 ? "s" : "")
                     + "\u00a0\u00b7\u00a0" + esc(displaySubject);
        StringBuilder sb = new StringBuilder();
        sb.append("            <div class=\"lesson__assign-detail\" hidden>\n");
        sb.append("              <div class=\"assign-popup__title\">").append(title).append("</div>\n");
        for (Assignment a : assignments) {
            boolean done = a.isDone();
            sb.append("              <div class=\"assign-popup__item")
              .append(done ? " assign-popup__item--done" : "")
              .append("\">\n");
            String desc = a.getDescription();
            if (desc != null && !desc.isBlank()) {
                sb.append("                <p class=\"assign-popup__desc\">").append(HtmlText.escapeAndLinkify(desc)).append("</p>\n");
            } else {
                sb.append("                <p class=\"assign-popup__desc assign-popup__desc--empty\">(aucune description)</p>\n");
            }
            sb.append("              </div>\n");
        }
        sb.append("            </div>\n");
        return sb.toString();
    }

    private String renderGapSlot(int hour) {
        String timeHtml = timeTag(LocalTime.of(hour, 0));
        if (hour == 12) {
            return "    <div class=\"slot slot--lunch\">\n"
                 + "      <span class=\"slot__time\">" + timeHtml + "</span>\n"
                 + "      <div class=\"slot__content\">"
                 + "<span class=\"lunch-icon\" aria-hidden=\"true\">\uD83C\uDF7D</span>"
                 + "\u00a0Pause d\u00e9jeuner"
                 + "</div>\n"
                 + "    </div>\n";
        }
        return "    <div class=\"slot slot--empty\">\n"
             + "      <span class=\"slot__time\">" + timeHtml + "</span>\n"
             + "      <div class=\"slot__content\">Pas de cours</div>\n"
             + "    </div>\n";
    }

    // -------------------------------------------------------------------------
    // Badge helpers
    // -------------------------------------------------------------------------

    /**
     * @param subjectLabel the name already printed on the card, so a badge does not repeat it.
     *                     An outing usually arrives titled "Sortie pédagogique" and would
     *                     otherwise show that twice; one named "Sortie au musée" still earns the
     *                     badge, which is what marks the slot as something other than a lesson.
     */
    private static List<String> buildBadges(TimetableEntry e, String subjectLabel) {
        List<String> badges = new ArrayList<>();
        switch (e.getStatus()) {
            case CANCELLED -> {
                String label = e.getStatusLabel() != null ? e.getStatusLabel() : "Cours annul\u00e9";
                badges.add(badge("cancel", label));
            }
            case MODIFIED -> {
                String label = e.getStatusLabel() != null ? e.getStatusLabel() : "Cours modifi\u00e9";
                badges.add(badge("modify", label));
            }
            case EXEMPTED -> badges.add(badge("exempt", "Dispens\u00e9"));
            case NORMAL -> {
                if (e.getStatusLabel() != null && !e.getStatusLabel().isBlank()) {
                    badges.add(badge("modify", e.getStatusLabel()));
                }
            }
        }
        if (e.isOuting())    addUnlessRedundant(badges, subjectLabel, "Sortie pédagogique");
        if (e.isDetention()) addUnlessRedundant(badges, subjectLabel, "Retenue");
        if (e.isEval()) {
            String label = e.getLessonLabel() != null ? e.getLessonLabel() : "\u00c9val. de comp\u00e9tences";
            badges.add(badge("eval", label));
        } else if (e.isTest()) {
            String label = e.getLessonLabel() != null ? e.getLessonLabel() : "\u00c9valuation";
            badges.add(badge("test", label));
        }
        return badges;
    }

    /** Adds an {@code outing} badge unless the card's subject line already says the same thing. */
    private static void addUnlessRedundant(List<String> badges, String subjectLabel, String label) {
        if (subjectLabel != null && subjectLabel.equalsIgnoreCase(label)) return;
        badges.add(badge("outing", label));
    }

    private static String badge(String type, String text) {
        return "<span class=\"badge badge--" + type + "\">" + esc(text) + "</span>";
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /** Wraps a local time in a semantic {@code <time>} element with a French display label. */
    private static String timeTag(LocalTime t) {
        String datetime = String.format("%02d:%02d", t.getHour(), t.getMinute());
        String display  = t.getHour() + "h" + String.format("%02d", t.getMinute());
        return "<time datetime=\"" + datetime + "\">" + display + "</time>";
    }

    /** Overrides the default palette-only resolver. */
    public void setColorResolver(SubjectColorResolver colors) {
        if (colors != null) this.colors = colors;
    }

    /** Overrides the default no-icon resolver. */
    public void setIconResolver(SubjectIconResolver icons) {
        if (icons != null) this.icons = icons;
    }

    /** Returns the display name for a timetable entry: enrichedSubject if set, else subject. */
    private static String displaySubject(TimetableEntry e) {
        String enriched = e.getEnrichedSubject();
        return (enriched != null && !enriched.isBlank()) ? enriched : e.getSubject();
    }

    private static String capitalize(String s) {
        return HtmlText.capitalize(s);
    }

    static String esc(String s) {
        return HtmlText.escape(s);
    }

    // -------------------------------------------------------------------------
    // Assignment dialog JS
    // -------------------------------------------------------------------------

    static final String ASSIGN_JS = """
        (function () {
          var dialog   = document.getElementById('assign-dialog');
          var body     = document.getElementById('assign-dialog-body');
          var closeBtn = document.getElementById('assign-dialog-close');

          document.querySelectorAll('.lesson__assign-chip').forEach(function (chip) {
            chip.addEventListener('click', function (e) {
              e.stopPropagation();
              var detail = chip.parentElement.querySelector('.lesson__assign-detail');
              if (!detail) return;
              body.innerHTML = '';
              var clone = detail.cloneNode(true);
              clone.removeAttribute('hidden');
              body.appendChild(clone);

              // Align dialog top with the lesson card's top
              var card = chip.closest('.lesson');
              var refTop = (card || chip).getBoundingClientRect().top;
              dialog.style.top = Math.max(8, refTop) + 'px';
              dialog.showModal();
              // Clamp so the dialog doesn't overflow the bottom of the viewport
              var overflow = dialog.offsetTop + dialog.offsetHeight - window.innerHeight + 8;
              if (overflow > 0) {
                dialog.style.top = Math.max(8, refTop - overflow) + 'px';
              }
            });
            chip.addEventListener('keydown', function (e) {
              if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); chip.click(); }
            });
          });

          closeBtn.addEventListener('click', function () { dialog.close(); });

          dialog.addEventListener('click', function (e) {
            if (e.target === dialog) dialog.close();
          });
        })();
        """;

    // -------------------------------------------------------------------------
    // Embedded CSS (shared by day pages and the index)
    // -------------------------------------------------------------------------

    static final String CSS = """

        /* ================================================================
           Emploi du temps — embedded stylesheet
           Light/dark via prefers-color-scheme · No JavaScript · Responsive
           ================================================================ */

        /* ----- Custom properties ----- */
        :root {
          --bg:          #f1f3f6;
          --surface:     #ffffff;
          --text-1:      #0f172a;
          --text-2:      #64748b;
          --text-3:      #94a3b8;
          --border:      #e2e8f0;

          --bdg-cancel-bg: #fee2e2; --bdg-cancel-fg: #b91c1c;
          --bdg-modify-bg: #fef9c3; --bdg-modify-fg: #854d0e;
          --bdg-test-bg:   #dbeafe; --bdg-test-fg:   #1e40af;
          --bdg-eval-bg:   #fef3c7; --bdg-eval-fg:   #92400e;
          --bdg-exempt-bg: #f3e8ff; --bdg-exempt-fg: #6b21a8;
          --bdg-outing-bg: #dcfce7; --bdg-outing-fg: #166534;

          --link-fg: #1e40af;
        }

        @media (prefers-color-scheme: dark) {
          :root {
            --bg:      #0c0d13;
            --surface: #14151f;
            --text-1:  #e2e8f0;
            --text-2:  #7c84a0;
            --text-3:  #4a5068;
            --border:  #1e2030;

            --bdg-cancel-bg: #3f0a0a; --bdg-cancel-fg: #fca5a5;
            --bdg-modify-bg: #3d2509; --bdg-modify-fg: #fcd34d;
            --bdg-test-bg:   #0c1f40; --bdg-test-fg:   #93c5fd;
            --bdg-eval-bg:   #3d2800; --bdg-eval-fg:   #fcd34d;
            --bdg-exempt-bg: #2d0a5e; --bdg-exempt-fg: #d8b4fe;
            --bdg-outing-bg: #08301b; --bdg-outing-fg: #86efac;

            --link-fg: #93c5fd;
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
          max-width: 480px;
          margin: 0 auto;
        }

        /* ----- Navigation bar ----- */
        .nav {
          display: flex;
          align-items: center;
          justify-content: space-between;
          margin-bottom: 0.5rem;
        }

        .nav__back {
          display: inline-flex;
          align-items: center;
          font-size: 0.875rem;
          color: var(--text-2);
          text-decoration: none;
        }
        .nav__back:hover { color: var(--text-1); }

        .nav__pager {
          display: flex;
          align-items: center;
          gap: 0.875rem;
        }

        .nav__page-link {
          font-size: 0.875rem;
          color: var(--text-2);
          text-decoration: none;
        }
        .nav__page-link:hover { color: var(--text-1); }

        .nav__page-link--disabled {
          opacity: 0.35;
          pointer-events: none;
          cursor: default;
        }

        /* ----- Day header ----- */
        .day-header { padding: 0.5rem 0 1.25rem; }

        .day-header__date {
          font-size: 1.5rem;
          font-weight: 700;
          letter-spacing: -0.025em;
          line-height: 1.2;
        }

        /* ----- Timeline ----- */
        .schedule { display: flex; flex-direction: column; }

        .slot {
          display: grid;
          grid-template-columns: 2.75rem 1fr;
          column-gap: 0.625rem;
          align-items: start;
        }

        .slot__time {
          padding-top: 1rem;
          font-size: 0.6875rem;
          font-weight: 600;
          color: var(--text-3);
          letter-spacing: 0.04em;
          text-align: right;
          white-space: nowrap;
        }

        /* End of a span that outlasts its own hour, stacked under the start time. */
        .slot__time-end {
          display: block;
          margin-top: 0.2rem;
          font-weight: 500;
          opacity: 0.75;
        }

        .slot__time-end::before {
          content: "\u2193\u00a0";
        }

        /* ----- Lesson card ----- */
        .lesson {
          margin: 0.3125rem 0;
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

        .lesson--cancelled {
          background: var(--bg);
          box-shadow: none;
          border-left-color: var(--border) !important;
          opacity: .65;
        }

        .lesson__subject {
          font-size: 0.9375rem;
          font-weight: 700;
          letter-spacing: -0.015em;
          line-height: 1.3;
        }

        .lesson--cancelled .lesson__subject {
          text-decoration: line-through;
          color: var(--text-3);
        }

        .lesson__teacher,
        .lesson__room {
          font-size: 0.8125rem;
          color: var(--text-2);
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }

        .lesson__memo {
          font-size: 0.75rem;
          color: var(--text-2);
          font-style: italic;
          margin-top: 0.125rem;
        }

        .lesson__memo a {
          color: var(--link-fg);
          word-break: break-all;
        }

        .lesson__footer {
          display: flex;
          flex-direction: column;
          align-items: flex-start;
          gap: 0.3rem;
          margin-top: 0.25rem;
        }

        /* ----- Badges ----- */
        .badge {
          display: inline-flex;
          align-items: center;
          padding: 0.125rem 0.4375rem;
          border-radius: 999px;
          font-size: 0.6875rem;
          font-weight: 600;
          letter-spacing: 0.025em;
          white-space: nowrap;
        }

        .badge--cancel { background: var(--bdg-cancel-bg); color: var(--bdg-cancel-fg); }
        .badge--modify { background: var(--bdg-modify-bg); color: var(--bdg-modify-fg); }
        .badge--test   { background: var(--bdg-test-bg);   color: var(--bdg-test-fg); }
        .badge--eval   { background: var(--bdg-eval-bg);   color: var(--bdg-eval-fg); }
        .badge--exempt { background: var(--bdg-exempt-bg); color: var(--bdg-exempt-fg); }
        .badge--outing { background: var(--bdg-outing-bg); color: var(--bdg-outing-fg); }

        /* ----- Gap and lunch slots ----- */
        .slot__content {
          display: flex;
          align-items: center;
          gap: 0.4rem;
          min-height: 2.5rem;
          padding: 0 0.25rem;
          font-size: 0.8125rem;
          color: var(--text-3);
          font-style: italic;
        }

        .lunch-icon { font-size: 0.9375rem; line-height: 1; }

        /* ----- Empty day ----- */
        .empty-day {
          text-align: center;
          padding: 4rem 1rem;
          color: var(--text-3);
          font-size: 0.9375rem;
        }

        /* ----- Index: header ----- */
        .index-header { padding: 1.5rem 0 1rem; }

        .index-header__title {
          font-size: 1.5rem;
          font-weight: 700;
          letter-spacing: -0.025em;
        }

        .index-header__subtitle {
          font-size: 0.875rem;
          color: var(--text-2);
          margin-top: 0.25rem;
        }

        /* ----- Index: day cards grid ----- */
        .days-grid {
          display: grid;
          grid-template-columns: repeat(auto-fill, minmax(13rem, 1fr));
          gap: 0.625rem;
        }

        .day-card {
          display: block;
          padding: 1rem 1.125rem;
          background: var(--surface);
          border-radius: 12px;
          border: 1px solid var(--border);
          text-decoration: none;
          color: inherit;
          box-shadow: 0 1px 2px rgba(0, 0, 0, .05);
          transition: box-shadow .15s ease, border-color .15s ease;
        }

        .day-card:hover {
          box-shadow: 0 4px 14px rgba(0, 0, 0, .12);
          border-color: transparent;
        }

        .day-card--empty { opacity: .5; }

        .day-card__dow {
          font-size: 0.6875rem;
          font-weight: 700;
          letter-spacing: .08em;
          text-transform: uppercase;
          color: var(--text-2);
          margin-bottom: 0.3rem;
        }

        .day-card__date {
          font-size: 1.0625rem;
          font-weight: 700;
          letter-spacing: -0.02em;
          margin-bottom: 0.4rem;
        }

        .day-card__meta {
          font-size: 0.8125rem;
          color: var(--text-2);
          line-height: 1.5;
        }

        .day-card__eval-note {
          font-weight: 600;
          color: var(--bdg-eval-fg);
        }

        .day-card__alert {
          display: inline-flex;
          align-items: center;
          gap: 0.2rem;
          margin-top: 0.4rem;
          font-size: 0.75rem;
          font-weight: 600;
          color: var(--bdg-cancel-fg);
        }

        .day-card__assign-done {
          text-decoration: line-through;
          color: var(--text-3);
        }

        /* ----- Replaced-class note (class replacement inside a lesson card) ----- */
        .lesson__replaced {
          display: flex;
          align-items: baseline;
          flex-wrap: wrap;
          gap: 0.25rem;
          margin-top: 0.375rem;
          padding-top: 0.375rem;
          border-top: 1px solid var(--border);
          font-size: 0.725rem;
          color: var(--text-3);
        }

        .lesson__replaced-label {
          font-weight: 600;
          flex-shrink: 0;
        }

        .lesson__replaced-detail {
          text-decoration: line-through;
          text-decoration-color: var(--text-3);
        }

        /* ----- Footer ----- */
        .page-footer {
          margin-top: 2.5rem;
          font-size: 0.75rem;
          color: var(--text-3);
          text-align: center;
        }

        /* ----- Lesson head (subject + assignment chip row) ----- */
        .lesson__head {
          display: flex;
          align-items: flex-start;
          justify-content: space-between;
          gap: 0.5rem;
          min-width: 0;
        }

        .lesson__head .lesson__subject {
          flex: 1;
          min-width: 0;
        }

        .lesson__assign-wrap {
          flex-shrink: 0;
        }

        .lesson__assign-chip {
          display: inline-flex;
          align-items: center;
          padding: 0.175rem 0.5rem;
          border-radius: 999px;
          font-size: 0.75rem;
          font-weight: 700;
          letter-spacing: 0.03em;
          line-height: 1.4;
          white-space: nowrap;
          border: 1px solid var(--border);
          background: var(--bg);
          color: var(--text-2);
          cursor: pointer;
        }

        .lesson__assign-chip:hover {
          color: var(--text-1);
        }

        .lesson__assign-chip--done {
          text-decoration: line-through;
          opacity: 0.5;
        }

        /* ----- Assignment popup content ----- */
        .assign-popup__title {
          font-size: 0.75rem;
          font-weight: 700;
          color: var(--text-2);
          letter-spacing: 0.05em;
          text-transform: uppercase;
          padding: 1rem 1rem 0.625rem;
          border-bottom: 1px solid var(--border);
        }

        .assign-popup__item {
          padding: 0.625rem 1rem;
          border-bottom: 1px solid var(--border);
        }

        .assign-popup__item:last-child {
          border-bottom: none;
          padding-bottom: 0.75rem;
        }

        .assign-popup__desc {
          font-size: 0.875rem;
          color: var(--text-1);
          line-height: 1.5;
          white-space: pre-wrap;
          word-break: break-word;
        }

        .assign-popup__desc a {
          color: var(--link-fg);
          word-break: break-all;
        }

        .assign-popup__item--done .assign-popup__desc {
          text-decoration: line-through;
          color: var(--text-3);
          opacity: 0.7;
        }

        .assign-popup__desc--empty {
          font-style: italic;
          color: var(--text-3);
        }

        /* ----- Dialog / overlay ----- */
        dialog {
          border: none;
          padding: 0;
          background: var(--surface);
          border-radius: 16px;
          width: min(400px, 92vw);
          max-height: 80vh;
          overflow: hidden;
          box-shadow: 0 8px 40px rgba(0, 0, 0, .25);
          animation: dialog-in 0.2s ease;
          /* JS-controlled vertical positioning — override browser default centering */
          position: fixed;
          inset: auto;
          margin: 0;
          left: 50%;
          transform: translateX(-50%);
          top: 10vh; /* fallback; overridden by JS */
        }

        dialog::backdrop {
          background: rgba(0, 0, 0, .45);
          animation: backdrop-in 0.2s ease;
        }

        @keyframes dialog-in {
          from { opacity: 0; transform: translateX(-50%) translateY(12px) scale(0.97); }
          to   { opacity: 1; transform: translateX(-50%) translateY(0)    scale(1); }
        }

        @keyframes backdrop-in {
          from { opacity: 0; }
          to   { opacity: 1; }
        }

        .dialog-inner {
          position: relative;
          overflow-y: auto;
          max-height: 80vh;
          padding-bottom: 0.5rem;
        }

        .dialog__close {
          position: sticky;
          top: 0.75rem;
          float: right;
          margin: 0.75rem 0.75rem 0 0;
          width: 1.75rem;
          height: 1.75rem;
          border-radius: 50%;
          border: none;
          background: var(--border);
          color: var(--text-2);
          font-size: 1rem;
          line-height: 1;
          cursor: pointer;
          display: inline-flex;
          align-items: center;
          justify-content: center;
          flex-shrink: 0;
        }

        .dialog__close:hover {
          background: var(--text-3);
          color: var(--text-1);
        }
        /* ----- Subject accent -----
           Each card carries --accent (light theme) and --accent-dark (dark theme) inline; the
           rules below pick the right one, so one markup path serves both themes with no JS.
           Colours come from SubjectColorResolver, which nudges any value that would be
           invisible against that theme's card background.

           These rules MUST stay last: the component rules above set the border with the
           `border-left` shorthand, which resets border-left-color to var(--border) — grey — and
           would win on source order otherwise. */
        .lesson { border-left-color: var(--accent); }
        @media (prefers-color-scheme: dark) {
          .lesson { border-left-color: var(--accent-dark); }
        }
        """ + SubjectIconResolver.CSS;
}
