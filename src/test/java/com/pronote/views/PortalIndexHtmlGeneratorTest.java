package com.pronote.views;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PortalIndexHtmlGeneratorTest {

    @Test
    void generate_emptySections_rendersEmptyState() {
        String html = new PortalIndexHtmlGenerator().generate(List.of());
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("Aucune section configurée"));
        // No section anchors should be emitted when there are no sections
        assertFalse(html.contains("class=\"section-card\""),
                "empty-state path must not emit any rendered section card");
    }

    @Test
    void generate_singleSection_rendersCardWithMetadata() {
        PortalIndexHtmlGenerator.Section section = new PortalIndexHtmlGenerator.Section(
                "📅", "Emploi du temps", "Planning des prochains jours", "timetable/index.html");

        String html = new PortalIndexHtmlGenerator().generate(List.of(section));
        assertTrue(html.contains("Emploi du temps"));
        assertTrue(html.contains("Planning des prochains jours"));
        assertTrue(html.contains("href=\"timetable/index.html\""));
        assertTrue(html.contains("📅"));
    }

    @Test
    void generate_escapesHtmlInUserSuppliedFields() {
        // The section labels come from configurable code paths — defensive escaping is required
        PortalIndexHtmlGenerator.Section section = new PortalIndexHtmlGenerator.Section(
                "📅", "<script>alert(1)</script>", "& description", "x.html?a=1&b=2");

        String html = new PortalIndexHtmlGenerator().generate(List.of(section));
        assertFalse(html.contains("<script>alert(1)</script>"),
                "raw <script> tag should not survive escaping");
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(html.contains("&amp; description"));
        assertTrue(html.contains("x.html?a=1&amp;b=2"));
    }

    @Test
    void generate_includesEmbeddedCss() {
        String html = new PortalIndexHtmlGenerator().generate(List.of());
        // Smoke test: page must be self-contained (no external CSS/JS)
        assertTrue(html.contains("<style>"));
        assertFalse(html.contains("<link "));
        assertFalse(html.contains("<script "));
    }

    @Test
    void generate_isWellFormedHtml5Document() {
        String html = new PortalIndexHtmlGenerator().generate(List.of(
                new PortalIndexHtmlGenerator.Section("📅", "T", "D", "a.html")));
        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("<html lang=\"fr\">"));
        assertTrue(html.trim().endsWith("</html>"));
        assertTrue(html.contains("<meta charset=\"UTF-8\">"));
    }
}
