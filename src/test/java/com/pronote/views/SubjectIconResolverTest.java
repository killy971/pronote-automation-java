package com.pronote.views;

import com.pronote.config.AppConfig;
import com.pronote.domain.TimetableEntry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SubjectIconResolverTest {

    private static final String RUNNER     = "🏃";
    private static final String TEST_TUBE  = "🧪";
    private static final String ATOM       = "⚛️";
    private static final String RULER      = "📐";
    private static final String CASTLE     = "🏰";
    private static final String GLOBE      = "🌍";
    private static final String SEEDLING   = "🌱";
    private static final String MICROSCOPE = "🔬";
    private static final String COLUMNS    = "🏛️";

    private static TimetableEntry entry(String subject, String enriched) {
        TimetableEntry e = new TimetableEntry();
        e.setSubject(subject);
        e.setEnrichedSubject(enriched);
        return e;
    }

    private static AppConfig.SubjectIconsConfig config(boolean enabled, Map<String, String> icons) {
        AppConfig.SubjectIconsConfig c = new AppConfig.SubjectIconsConfig();
        c.setEnabled(enabled);
        c.setIcons(icons);
        return c;
    }

    private static SubjectIconResolver enabled() {
        return SubjectIconResolver.from(config(true, Map.of()), List.of());
    }

    // ---- Enablement ---------------------------------------------------------

    @Test
    void disabledByDefault_emitsNothing() {
        assertEquals("", SubjectIconResolver.disabled().icon("MATHEMATIQUES"));
        assertEquals("", SubjectIconResolver.disabled().prefix("MATHEMATIQUES"));
    }

    @Test
    void configOff_emitsNothing_evenWithExplicitIcons() {
        SubjectIconResolver r = SubjectIconResolver.from(
                config(false, Map.of("MATHEMATIQUES", RULER)), List.of());

        assertEquals("", r.icon("MATHEMATIQUES"));
    }

    @Test
    void nullConfig_isTreatedAsOff() {
        assertEquals("", SubjectIconResolver.from(null, List.of()).icon("MATHEMATIQUES"));
    }

    // ---- Rule order — the whole reason RULES is a list ----------------------

    @Test
    void sportWinsOverPhysics_forASubjectContainingBoth() {
        // "ED.PHYSIQUE & SPORT." contains "PHYSIQUE"; the sport rules must be tried first.
        assertEquals(RUNNER, enabled().icon("ED.PHYSIQUE & SPORT."));
        assertEquals(RUNNER, enabled().icon("EPS"));
        assertEquals(ATOM, enabled().icon("PHYSIQUE-CHIMIE"));
        // Chemistry on its own keeps the test tube — the PHYSIQUE rule simply never matches it.
        assertEquals(TEST_TUBE, enabled().icon("CHIMIE"));
    }

    @Test
    void combinedHistoryGeographyWinsOverEitherHalf() {
        assertEquals(GLOBE, enabled().icon("HISTOIRE-GEOGRAPHIE"));
        // Once enrichment splits it by teacher, each half gets its own icon.
        assertEquals(CASTLE, enabled().icon("Histoire"));
        assertEquals(GLOBE, enabled().icon("Géographie"));
    }

    @Test
    void svtWinsOverTheGenericSciencesRule() {
        assertEquals(SEEDLING, enabled().icon("SCIENCES VIE& TERRE"));
        assertEquals(SEEDLING, enabled().icon("SVT"));
        assertEquals(MICROSCOPE, enabled().icon("SCIENCES"));
    }

    // ---- Normalisation ------------------------------------------------------

    @Test
    void matchingIgnoresCaseAndAccents() {
        assertEquals(RULER, enabled().icon("MATHEMATIQUES"));
        assertEquals(RULER, enabled().icon("Mathématiques"));
        assertEquals(RULER, enabled().icon("maths"));
    }

    @Test
    void unknownSubject_getsNoIconRatherThanAWrongOne() {
        assertEquals("", enabled().icon("SYN_UNKNOWN"));
        assertEquals("", enabled().prefix("SYN_UNKNOWN"));
    }

    @Test
    void blankOrNullSubject_isSafe() {
        assertEquals("", enabled().icon(null));
        assertEquals("", enabled().icon("   "));
    }

    // ---- Config overrides ---------------------------------------------------

    @Test
    void configuredIcon_winsOverTheBuiltInTable() {
        SubjectIconResolver r = SubjectIconResolver.from(
                config(true, Map.of("MATHEMATIQUES", "🧮")), List.of());

        assertEquals("🧮", r.icon("MATHEMATIQUES"));
    }

    @Test
    void emptyConfiguredValue_suppressesTheBuiltInIcon() {
        Map<String, String> icons = new LinkedHashMap<>();
        icons.put("MATHEMATIQUES", "");
        SubjectIconResolver r = SubjectIconResolver.from(config(true, icons), List.of());

        assertEquals("", r.icon("MATHEMATIQUES"));
        assertEquals("", r.prefix("MATHEMATIQUES"));
    }

    @Test
    void configKeyedOnTheRawSubject_appliesToTheEnrichedNameToo() {
        // The assignment view only ever sees "Latin"; the key here is the Pronote spelling.
        SubjectIconResolver r = SubjectIconResolver.from(
                config(true, Map.of("LCA LATIN", "🏺")),
                List.of(entry("LCA LATIN", "Latin")));

        assertEquals("🏺", r.icon("Latin"));
        assertEquals("🏺", r.icon("LCA LATIN"));
    }

    @Test
    void builtInIconFallsBackToTheRawSubject_whenTheEnrichedNameMatchesNothing() {
        SubjectIconResolver r = SubjectIconResolver.from(
                config(true, Map.of()), List.of(entry("LCA LATIN", "Antiquité")));

        assertEquals(COLUMNS, r.icon("Antiquité"));
    }

    // ---- Markup -------------------------------------------------------------

    @Test
    void prefix_wrapsTheIconInItsFixedWidthSlot_andHidesItFromScreenReaders() {
        String prefix = enabled().prefix("MATHEMATIQUES");

        assertTrue(prefix.startsWith("<span class=\"subject-icon\" aria-hidden=\"true\">"), prefix);
        assertTrue(prefix.endsWith("</span>"), prefix);
        assertTrue(prefix.contains(RULER), prefix);
    }

    @Test
    void prefix_escapesAConfiguredValue() {
        SubjectIconResolver r = SubjectIconResolver.from(
                config(true, Map.of("MATHEMATIQUES", "<3")), List.of());

        assertTrue(r.prefix("MATHEMATIQUES").contains("&lt;3"));
    }

    @Test
    void everyStylesheetCarriesTheIconSlot() {
        // The slot is what keeps subject names aligned; a generator that renders an icon without
        // it would silently lose the alignment the fixed width buys.
        for (String css : List.of(TimetableHtmlGenerator.CSS, AssignmentHtmlGenerator.CSS,
                                  EvaluationHtmlGenerator.CSS,
                                  EvaluationSummaryHtmlGenerator.SUMMARY_CSS)) {
            assertTrue(css.contains(".subject-icon"), "missing the subject-icon slot rule");
        }
    }
}
