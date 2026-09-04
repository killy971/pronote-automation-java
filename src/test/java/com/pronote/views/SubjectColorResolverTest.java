package com.pronote.views;

import com.pronote.config.AppConfig;
import com.pronote.domain.TimetableEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SubjectColorResolverTest {

    /** Real values observed on a live timetable, kept as the regression fixtures. */
    private static final String ORANGE = "#EC6719";   // fine in both themes
    private static final String NAVY   = "#212853";   // 1.26:1 on dark — invisible
    private static final String SILVER = "#C0C0C0";   // 1.82:1 on light — invisible

    private static TimetableEntry entry(String subject, String color) {
        TimetableEntry e = new TimetableEntry();
        e.setSubject(subject);
        e.setColor(color);
        return e;
    }

    private static AppConfig.SubjectColorsConfig config(String source, Map<String, String> overrides) {
        AppConfig.SubjectColorsConfig c = new AppConfig.SubjectColorsConfig();
        c.setSource(source);
        c.setOverrides(overrides);
        return c;
    }

    // ---- Source selection ---------------------------------------------------

    @Test
    void officialSource_usesPronoteColor() {
        SubjectColorResolver r = SubjectColorResolver.from(
                config("official", Map.of()), List.of(entry("SYN_MATHS", ORANGE)));

        assertEquals(ORANGE.toLowerCase(), r.baseColor("SYN_MATHS").toLowerCase());
    }

    @Test
    void paletteSource_ignoresPronoteColor() {
        SubjectColorResolver r = SubjectColorResolver.from(
                config("palette", Map.of()), List.of(entry("SYN_MATHS", ORANGE)));

        assertNotEquals(ORANGE.toLowerCase(), r.baseColor("SYN_MATHS").toLowerCase());
    }

    @Test
    void officialSource_fallsBackToPalette_whenPronoteHasNoColorForTheSubject() {
        SubjectColorResolver r = SubjectColorResolver.from(
                config("official", Map.of()), List.of(entry("SYN_MATHS", ORANGE)));

        // A manual entry's subject never appears in the timetable colour map — it must still get
        // a border rather than none.
        assertTrue(SubjectColorResolver.isHex(r.baseColor("SYN_UNKNOWN")));
    }

    @Test
    void overrideWins_overBothSources() {
        SubjectColorResolver r = SubjectColorResolver.from(
                config("official", Map.of("SYN_MATHS", "#123456")),
                List.of(entry("SYN_MATHS", ORANGE)));

        assertEquals("#123456", r.baseColor("SYN_MATHS"));
    }

    @Test
    void malformedColors_areIgnored() {
        SubjectColorResolver r = SubjectColorResolver.from(
                config("official", Map.of("SYN_MATHS", "rouge")),
                List.of(entry("SYN_PHYSIQUE", "not-a-hex")));

        assertTrue(SubjectColorResolver.isHex(r.baseColor("SYN_MATHS")));
        assertTrue(SubjectColorResolver.isHex(r.baseColor("SYN_PHYSIQUE")));
    }

    @Test
    void paletteIsStableForTheSameSubject() {
        SubjectColorResolver r = SubjectColorResolver.paletteOnly();
        assertEquals(r.baseColor("SYN_MATHS"), r.baseColor("SYN_MATHS"));
    }

    // ---- Per-theme contrast -------------------------------------------------

    @Test
    void usableColor_isLeftUntouchedInBothThemes() {
        assertEquals(ORANGE.toLowerCase(),
                SubjectColorResolver.ensureContrast(ORANGE, SubjectColorResolver.LIGHT_SURFACE, false).toLowerCase());
        assertEquals(ORANGE.toLowerCase(),
                SubjectColorResolver.ensureContrast(ORANGE, SubjectColorResolver.DARK_SURFACE, true).toLowerCase());
    }

    @Test
    void tooDarkForDarkTheme_isLightenedJustEnough() {
        assertTrue(SubjectColorResolver.contrast(NAVY, SubjectColorResolver.DARK_SURFACE)
                        < SubjectColorResolver.MIN_CONTRAST,
                "fixture must start below the threshold");

        String adjusted = SubjectColorResolver.ensureContrast(NAVY, SubjectColorResolver.DARK_SURFACE, true);

        assertTrue(SubjectColorResolver.contrast(adjusted, SubjectColorResolver.DARK_SURFACE)
                >= SubjectColorResolver.MIN_CONTRAST);
        // Lightened, not replaced.
        assertTrue(SubjectColorResolver.relativeLuminance(adjusted)
                > SubjectColorResolver.relativeLuminance(NAVY));
    }

    @Test
    void tooLightForLightTheme_isDarkenedJustEnough() {
        assertTrue(SubjectColorResolver.contrast(SILVER, SubjectColorResolver.LIGHT_SURFACE)
                        < SubjectColorResolver.MIN_CONTRAST,
                "fixture must start below the threshold");

        String adjusted = SubjectColorResolver.ensureContrast(SILVER, SubjectColorResolver.LIGHT_SURFACE, false);

        assertTrue(SubjectColorResolver.contrast(adjusted, SubjectColorResolver.LIGHT_SURFACE)
                >= SubjectColorResolver.MIN_CONTRAST);
        assertTrue(SubjectColorResolver.relativeLuminance(adjusted)
                < SubjectColorResolver.relativeLuminance(SILVER));
    }

    @Test
    void everyResolvedColorClearsTheThresholdInItsOwnTheme() {
        SubjectColorResolver r = SubjectColorResolver.from(
                config("official", Map.of()),
                List.of(entry("SYN_A", ORANGE), entry("SYN_B", NAVY), entry("SYN_C", SILVER),
                        entry("SYN_D", "#000000"), entry("SYN_E", "#ffffff")));

        for (String subject : List.of("SYN_A", "SYN_B", "SYN_C", "SYN_D", "SYN_E", "SYN_PALETTE")) {
            assertTrue(SubjectColorResolver.contrast(r.light(subject), SubjectColorResolver.LIGHT_SURFACE)
                            >= SubjectColorResolver.MIN_CONTRAST,
                    "light theme too faint for " + subject + ": " + r.light(subject));
            assertTrue(SubjectColorResolver.contrast(r.dark(subject), SubjectColorResolver.DARK_SURFACE)
                            >= SubjectColorResolver.MIN_CONTRAST,
                    "dark theme too faint for " + subject + ": " + r.dark(subject));
        }
    }

    @Test
    void styleAttr_declaresBothCustomProperties() {
        SubjectColorResolver r = SubjectColorResolver.from(
                config("official", Map.of()), List.of(entry("SYN_MATHS", NAVY)));

        String style = r.styleAttr("SYN_MATHS");
        assertEquals("--accent:" + r.light("SYN_MATHS") + ";--accent-dark:" + r.dark("SYN_MATHS"), style);
    }

    @Test
    void contrast_matchesKnownWcagValues() {
        assertEquals(21.0, SubjectColorResolver.contrast("#000000", "#ffffff"), 0.01);
        assertEquals(1.0, SubjectColorResolver.contrast("#abcdef", "#abcdef"), 0.001);
    }
}
