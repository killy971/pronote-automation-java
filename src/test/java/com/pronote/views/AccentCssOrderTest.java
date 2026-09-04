package com.pronote.views;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the source order of the subject-accent rules inside each generator's CSS.
 *
 * <p>The component rules set a card's border with the {@code border-left} shorthand, which also
 * resets {@code border-left-color} to {@code var(--border)} — grey. An accent rule declared
 * <em>before</em> them has equal specificity and loses on source order, so every card silently
 * renders grey: valid CSS, valid HTML, no error anywhere. That shipped once; these tests make it
 * fail loudly instead.
 */
class AccentCssOrderTest {

    private static void assertAccentWinsOverShorthand(String css, String shorthandProperty) {
        int shorthand = css.lastIndexOf(shorthandProperty);
        int accent = css.indexOf("var(--accent)");

        assertTrue(shorthand >= 0, "expected a " + shorthandProperty + " shorthand in this stylesheet");
        assertTrue(accent >= 0, "expected an accent rule in this stylesheet");
        assertTrue(accent > shorthand,
                "accent rule must come after the last " + shorthandProperty
                + " shorthand, otherwise the shorthand resets the colour to grey");
    }

    @Test
    void timetableAccent_comesAfterTheBorderShorthand() {
        assertAccentWinsOverShorthand(TimetableHtmlGenerator.CSS, "border-left:");
    }

    @Test
    void assignmentAccent_comesAfterTheBorderShorthand() {
        assertAccentWinsOverShorthand(AssignmentHtmlGenerator.CSS, "border-left:");
    }

    @Test
    void evaluationAccent_comesAfterTheBorderShorthand() {
        assertAccentWinsOverShorthand(EvaluationHtmlGenerator.CSS, "border-left:");
    }

    @Test
    void evaluationSummaryAccent_comesAfterTheBorderShorthand() {
        assertAccentWinsOverShorthand(EvaluationSummaryHtmlGenerator.SUMMARY_CSS, "border-left:");
    }

    @Test
    void everyStylesheetPairsTheDarkOverrideWithTheLightRule() {
        for (String css : List.of(TimetableHtmlGenerator.CSS, AssignmentHtmlGenerator.CSS,
                                  EvaluationHtmlGenerator.CSS,
                                  EvaluationSummaryHtmlGenerator.SUMMARY_CSS)) {
            assertTrue(css.contains("var(--accent)"), "missing light-theme accent rule");
            assertTrue(css.contains("var(--accent-dark)"), "missing dark-theme accent rule");
            assertTrue(css.indexOf("var(--accent-dark)") > css.indexOf("var(--accent)"),
                    "the dark override must follow the light rule");
        }
    }

    @Test
    void cancelledLessonsKeepTheirGreyBorder() {
        // The cancelled rule uses !important precisely so it still beats the accent rule that now
        // follows it — a cancelled lesson must not be subject-coloured.
        String css = TimetableHtmlGenerator.CSS;
        int cancelled = css.indexOf("border-left-color: var(--border) !important");
        assertTrue(cancelled >= 0, "cancelled lessons must force the neutral border");
        assertTrue(css.indexOf("var(--accent)") > cancelled,
                "fixture assumes the accent rule comes later; !important is what keeps grey winning");
    }
}
