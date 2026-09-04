package com.pronote.views;

import com.pronote.config.AppConfig;
import com.pronote.domain.TimetableEntry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the accent colour used for a subject's card border, in both themes.
 *
 * <p>Three sources, most specific first:
 * <ol>
 *   <li>a {@code subjectColors.overrides} entry for the raw subject string;</li>
 *   <li>Pronote's own per-lesson colour ({@code CouleurFond}), when
 *       {@code subjectColors.source: official};</li>
 *   <li>the built-in 12-colour palette, indexed by subject-name hash.</li>
 * </ol>
 *
 * <p>The palette is always the final fallback, so a manual entry or a subject Pronote left blank
 * still gets a border rather than none.
 *
 * <p><strong>Why two colours per subject.</strong> Schools pick colours against Pronote's white
 * background, so they are not all usable on both themes. Measured on a real timetable: MUSIQUE is
 * {@code #212853}, which manages 1.26:1 against the dark card background — invisible; LANGUE &amp;
 * LITTÉRATURE is {@code #C0C0C0}, which manages 1.82:1 against the light one. Each colour is
 * therefore stepped towards white (dark theme) or black (light theme) until it clears
 * {@link #MIN_CONTRAST}, which leaves already-usable colours untouched.
 */
public final class SubjectColorResolver {

    /** Fallback palette: subject-coded accents, indexed by {@code abs(hashCode()) % 12}. */
    private static final String[] PALETTE = {
        "#3b82f6", "#10b981", "#f59e0b", "#ef4444", "#8b5cf6",
        "#ec4899", "#14b8a6", "#f97316", "#06b6d4", "#84cc16",
        "#a855f7", "#6366f1"
    };

    /** Card background in each theme — the {@code --surface} custom property of every view. */
    static final String LIGHT_SURFACE = "#ffffff";
    static final String DARK_SURFACE  = "#14151f";

    /**
     * Minimum contrast a border must reach against its card. 3.0 is the WCAG threshold for
     * non-text UI components; a 4px border is exactly that.
     */
    static final double MIN_CONTRAST = 3.0;

    private final boolean useOfficial;
    private final Map<String, String> overrides;
    private final Map<String, String> officialBySubject;

    private SubjectColorResolver(boolean useOfficial, Map<String, String> overrides,
                                 Map<String, String> officialBySubject) {
        this.useOfficial = useOfficial;
        this.overrides = overrides;
        this.officialBySubject = officialBySubject;
    }

    /** A resolver that ignores Pronote's colours and always uses the built-in palette. */
    public static SubjectColorResolver paletteOnly() {
        return new SubjectColorResolver(false, Map.of(), Map.of());
    }

    /**
     * Builds a resolver from config plus the timetable snapshot, which is the only place
     * Pronote's colours are carried. Assignment and evaluation views have no colour of their own,
     * so they look the subject up in this same map and stay consistent with the timetable.
     */
    public static SubjectColorResolver from(AppConfig.SubjectColorsConfig config,
                                            List<TimetableEntry> timetable) {
        Map<String, String> official = new LinkedHashMap<>();
        if (timetable != null) {
            for (TimetableEntry e : timetable) {
                if (e.getSubject() != null && isHex(e.getColor())) {
                    official.putIfAbsent(e.getSubject(), e.getColor());
                }
            }
        }
        Map<String, String> overrides = new LinkedHashMap<>();
        if (config != null) {
            config.getOverrides().forEach((subject, hex) -> {
                if (subject != null && isHex(hex)) overrides.put(subject, hex);
            });
        }
        return new SubjectColorResolver(config != null && config.isOfficial(), overrides, official);
    }

    // -------------------------------------------------------------------------

    /** The subject's base colour, before any per-theme contrast adjustment. */
    String baseColor(String subject) {
        if (subject == null) return PALETTE[0];
        String override = overrides.get(subject);
        if (override != null) return override;
        if (useOfficial) {
            String official = officialBySubject.get(subject);
            if (official != null) return official;
        }
        return PALETTE[Math.abs(subject.hashCode()) % PALETTE.length];
    }

    /** Accent for the light theme: dark enough to be visible on a white card. */
    public String light(String subject) {
        return ensureContrast(baseColor(subject), LIGHT_SURFACE, false);
    }

    /** Accent for the dark theme: light enough to be visible on a dark card. */
    public String dark(String subject) {
        return ensureContrast(baseColor(subject), DARK_SURFACE, true);
    }

    /**
     * The inline style declaring both accents, e.g.
     * {@code --accent:#EC6719;--accent-dark:#EC6719}. Stylesheets read these via
     * {@code border-left-color: var(--accent)} and override them in the dark media query, so a
     * single markup path serves both themes with no JavaScript.
     */
    public String styleAttr(String subject) {
        return "--accent:" + light(subject) + ";--accent-dark:" + dark(subject);
    }

    // -------------------------------------------------------------------------
    // Colour maths — package-private for unit testing.
    // -------------------------------------------------------------------------

    static boolean isHex(String s) {
        return s != null && s.matches("#[0-9a-fA-F]{6}");
    }

    /**
     * Steps {@code hex} towards white or black until it clears {@link #MIN_CONTRAST} against
     * {@code background}. Interpolating in sRGB keeps the hue, so an adjusted colour still reads
     * as the same colour; a colour that already passes is returned unchanged.
     */
    static String ensureContrast(String hex, String background, boolean lighten) {
        if (!isHex(hex)) return hex;
        int[] rgb = toRgb(hex);
        int[] target = lighten ? new int[]{255, 255, 255} : new int[]{0, 0, 0};
        // 20 steps of 5% reaches the target exactly, which always clears the threshold against
        // the opposite-luminance background, so this terminates.
        for (int step = 0; step <= 20; step++) {
            int[] candidate = new int[3];
            for (int i = 0; i < 3; i++) {
                candidate[i] = (int) Math.round(rgb[i] + (target[i] - rgb[i]) * (step * 0.05));
            }
            String candidateHex = toHex(candidate);
            if (contrast(candidateHex, background) >= MIN_CONTRAST) return candidateHex;
        }
        return toHex(target);
    }

    /** WCAG contrast ratio between two colours, from 1.0 (identical) to 21.0 (black on white). */
    static double contrast(String a, String b) {
        double la = relativeLuminance(a);
        double lb = relativeLuminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** WCAG relative luminance. */
    static double relativeLuminance(String hex) {
        int[] rgb = toRgb(hex);
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = rgb[i] / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static int[] toRgb(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        return new int[]{
            Integer.parseInt(h.substring(0, 2), 16),
            Integer.parseInt(h.substring(2, 4), 16),
            Integer.parseInt(h.substring(4, 6), 16)
        };
    }

    private static String toHex(int[] rgb) {
        return String.format("#%02x%02x%02x",
                clamp(rgb[0]), clamp(rgb[1]), clamp(rgb[2]));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
