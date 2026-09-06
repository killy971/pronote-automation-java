package com.pronote.views;

import com.pronote.config.AppConfig;
import com.pronote.domain.TimetableEntry;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the emoji shown before a subject name in the generated views.
 *
 * <p>Disabled unless {@code subjectIcons.enabled} is true. Three sources, most specific first:
 * <ol>
 *   <li>a {@code subjectIcons.icons} entry for the subject — raw Pronote string or enriched
 *       name, either spelling works; an empty value means "no icon for this subject";</li>
 *   <li>the built-in {@link #RULES} table, matched on a normalised (accent-free, upper-case)
 *       form of the subject;</li>
 *   <li>nothing — an unrecognised subject renders with no icon rather than a wrong one.</li>
 * </ol>
 *
 * <p><strong>Why keyword matching rather than exact names.</strong> Pronote subject strings vary
 * per establishment ({@code ED.PHYSIQUE & SPORT.}, {@code EPS}, {@code EDUCATION PHYSIQUE}), so an
 * exact-match table would only ever fit one school. Each rule is a substring probe against the
 * normalised name, and <strong>order decides</strong>: {@code ED.PHYSIQUE & SPORT.} contains
 * "PHYSIQUE", so the sport rules must be tried before the physics one, and
 * {@code HISTOIRE-GEOGRAPHIE} contains both halves, so the combined rule comes before either.
 * {@code SubjectIconResolverTest} pins those orderings.
 */
public final class SubjectIconResolver {

    /**
     * Built-in subject icons, tried in order — first substring hit wins.
     *
     * <p>Read this as a list, not a map: the ordering is load-bearing (see the class javadoc), so
     * a new rule goes next to the subjects it must not be shadowed by, never appended blindly.
     */
    static final String[][] RULES = {
        // Sport before PHYSIQUE — "ED.PHYSIQUE & SPORT." contains both. PHYSIQUE before CHIMIE:
        // the combined "PHYSIQUE-CHIMIE" takes the atom, a chemistry-only subject the test tube.
        {"SPORT",             "🏃"},
        {"EPS",               "🏃"},
        {"PHYSIQUE",          "⚛️"},
        {"CHIMIE",            "🧪"},
        {"MATH",              "📐"},
        // Combined history-geography before either half.
        {"HISTOIRE-GEO",      "🌍"},
        {"HISTOIRE GEO",      "🌍"},
        {"HIST-GEO",          "🌍"},
        {"HISTOIRE",          "🏰"},
        {"GEOGRAPHIE",        "🌍"},
        // SVT before the generic SCIENCES rule at the end.
        {"SVT",               "🌱"},
        {"SCIENCES VIE",      "🌱"},
        {"VIE& TERRE",        "🌱"},
        {"BIOLOGIE",          "🌱"},
        {"TECHNO",            "⚙️"},
        {"ART",               "🎨"},
        {"MUSIQUE",           "🎵"},
        // 🇺🇸 rather than 🇬🇧: this establishment's English is a section internationale
        // américaine (SIA). Override in `subjectIcons.icons` for a British-flavoured course.
        {"ANGLAIS",           "🇺🇸"},
        {"ESPAGNOL",          "🇪🇸"},
        {"ALLEMAND",          "🇩🇪"},
        {"ITALIEN",           "🇮🇹"},
        {"LATIN",             "🏛️"},
        {"GREC",              "🏛️"},
        {"LCA",               "🏛️"},
        {"FRANCAIS",          "📖"},
        {"LITTERATURE",       "📚"},
        {"VIE DE CLASSE",     "🗣️"},
        {"ACCOMPAGNEMENT",    "🧭"},
        {"ORIENTATION",       "🧭"},
        {"PHILO",             "🤔"},
        {"SES",               "📈"},
        {"ECONOMI",           "📈"},
        {"NSI",               "💻"},
        {"SNT",               "💻"},
        {"INFORMATIQUE",      "💻"},
        {"DEVOIRS FAITS",     "✏️"},
        // Generic catch-alls, last: every more specific science rule has already been tried.
        {"SCIENCES",          "🔬"},
        {"LANGUE",            "💬"},
    };

    /**
     * The icon slot, shared verbatim by every generator's stylesheet.
     *
     * <p>Fixed width: emoji advance widths differ (a flag is roughly twice a compass), so a raw
     * inline emoji would leave every subject name starting at a different x. A fixed-width,
     * centred slot keeps the names in a column, which is the whole point of scanning a timetable.
     */
    static final String CSS = """

        /* ----- Subject icon (subjectIcons.enabled) ----- */
        .subject-icon {
          display: inline-block;
          width: 1.35em;
          margin-right: 0.15em;
          text-align: center;
          font-size: 1.05em;
          font-style: normal;
          line-height: 1;
        }
        """;

    private final boolean enabled;
    private final Map<String, String> configured;
    private final Map<String, String> rawByEnriched;

    private SubjectIconResolver(boolean enabled, Map<String, String> configured,
                                Map<String, String> rawByEnriched) {
        this.enabled = enabled;
        this.configured = configured;
        this.rawByEnriched = rawByEnriched;
    }

    /** A resolver that never emits an icon — the default for every generator. */
    public static SubjectIconResolver disabled() {
        return new SubjectIconResolver(false, Map.of(), Map.of());
    }

    /**
     * Builds a resolver from config plus the timetable snapshot. The timetable is read only for
     * its raw-to-enriched pairs, so a config key written either way matches both the timetable
     * (which knows the raw subject) and the assignment/evaluation views (which know the enriched
     * one).
     */
    public static SubjectIconResolver from(AppConfig.SubjectIconsConfig config,
                                           List<TimetableEntry> timetable) {
        if (config == null || !config.isEnabled()) return disabled();

        Map<String, String> configured = new LinkedHashMap<>();
        config.getIcons().forEach((subject, icon) -> {
            if (subject != null) configured.put(subject, icon == null ? "" : icon.strip());
        });

        Map<String, String> rawByEnriched = new LinkedHashMap<>();
        if (timetable != null) {
            for (TimetableEntry e : timetable) {
                String enriched = e.getEnrichedSubject();
                if (e.getSubject() != null && enriched != null && !enriched.isBlank()
                        && !enriched.equals(e.getSubject())) {
                    rawByEnriched.putIfAbsent(enriched, e.getSubject());
                }
            }
        }
        return new SubjectIconResolver(true, configured, rawByEnriched);
    }

    // -------------------------------------------------------------------------

    /** The subject's emoji, or {@code ""} when disabled or unrecognised. */
    public String icon(String subject) {
        if (!enabled || subject == null || subject.isBlank()) return "";

        String raw = rawByEnriched.get(subject);
        String override = configured.get(subject);
        if (override == null && raw != null) override = configured.get(raw);
        if (override != null) return override;

        String match = builtIn(subject);
        if (match.isEmpty() && raw != null) match = builtIn(raw);
        return match;
    }

    /**
     * The icon wrapped in its slot, ready to prepend to an escaped subject name; {@code ""} when
     * there is no icon, so the markup collapses to exactly what it was before the feature.
     *
     * <p>{@code aria-hidden}: the subject name follows in full, and a screen reader announcing
     * "compass Accompagnement Perso." only adds noise.
     */
    public String prefix(String subject) {
        String icon = icon(subject);
        if (icon.isEmpty()) return "";
        // Escaped like any other config-sourced string: nothing stops an override holding "<3".
        String safe = icon.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<span class=\"subject-icon\" aria-hidden=\"true\">" + safe + "</span>";
    }

    // -------------------------------------------------------------------------

    private static String builtIn(String subject) {
        String normalised = normalise(subject);
        for (String[] rule : RULES) {
            if (normalised.contains(rule[0])) return rule[1];
        }
        return "";
    }

    /**
     * Upper-cases and strips diacritics, so one rule covers {@code MATHEMATIQUES},
     * {@code Mathématiques} and {@code Maths}.
     */
    static String normalise(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT);
    }
}
