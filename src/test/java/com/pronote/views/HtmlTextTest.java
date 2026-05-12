package com.pronote.views;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the behaviour of {@link HtmlText#escapeAndLinkify(String)} — the entry point teacher-
 * supplied text passes through before insertion into assignment cards, lesson memos, and the
 * timetable assignment popup. The escape contract is XSS-relevant: any regression that lets
 * raw {@code <}, {@code >}, {@code &}, or {@code "} reach the DOM is a bug.
 */
class HtmlTextTest {

    @Test
    void escape_replacesAllReservedChars() {
        assertEquals("a &amp; b &lt;c&gt; &quot;d&quot;",
                HtmlText.escape("a & b <c> \"d\""));
    }

    @Test
    void escape_nullBecomesEmpty() {
        assertEquals("", HtmlText.escape(null));
    }

    @Test
    void linkify_plainTextIsJustEscaped() {
        assertEquals("hello &amp; goodbye",
                HtmlText.escapeAndLinkify("hello & goodbye"));
    }

    @Test
    void linkify_singleHttpsUrlBecomesAnchor() {
        String out = HtmlText.escapeAndLinkify("voir https://example.com pour la suite");
        assertEquals("voir <a href=\"https://example.com\" target=\"_blank\" rel=\"noopener noreferrer\">"
                + "https://example.com</a> pour la suite", out);
    }

    @Test
    void linkify_httpUrlAlsoWorks() {
        String out = HtmlText.escapeAndLinkify("voir http://example.com");
        assertEquals("voir <a href=\"http://example.com\" target=\"_blank\" rel=\"noopener noreferrer\">"
                + "http://example.com</a>", out);
    }

    @Test
    void linkify_trailingSentencePunctuationIsStrippedBackToText() {
        // The period at the end of "https://example.com." is part of the prose, not the URL.
        String out = HtmlText.escapeAndLinkify("voir https://example.com.");
        assertEquals("voir <a href=\"https://example.com\" target=\"_blank\" rel=\"noopener noreferrer\">"
                + "https://example.com</a>.", out);
    }

    @Test
    void linkify_trailingCommaIsStripped() {
        String out = HtmlText.escapeAndLinkify("https://a.example, https://b.example.");
        assertEquals("<a href=\"https://a.example\" target=\"_blank\" rel=\"noopener noreferrer\">"
                + "https://a.example</a>, "
                + "<a href=\"https://b.example\" target=\"_blank\" rel=\"noopener noreferrer\">"
                + "https://b.example</a>.", out);
    }

    @Test
    void linkify_ampersandInQueryStringIsEscaped() {
        // The query-string '&' must become '&amp;' in both the href and the visible text,
        // otherwise the HTML is malformed and browsers will interpret e.g. '&copy' as an entity.
        String out = HtmlText.escapeAndLinkify("https://example.com/search?a=1&b=2");
        assertEquals("<a href=\"https://example.com/search?a=1&amp;b=2\" target=\"_blank\" "
                + "rel=\"noopener noreferrer\">https://example.com/search?a=1&amp;b=2</a>", out);
    }

    @Test
    void linkify_xssAttemptInSurroundingTextIsEscaped() {
        // The injected <script> must be neutralised — only http(s):// URLs should become anchors.
        String out = HtmlText.escapeAndLinkify("<script>alert(1)</script> see https://ok.example");
        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt; see "
                + "<a href=\"https://ok.example\" target=\"_blank\" rel=\"noopener noreferrer\">"
                + "https://ok.example</a>", out);
    }

    @Test
    void linkify_xssAttemptInsideUrlSpanIsTruncatedByPattern() {
        // The URL pattern stops at < — so even if a teacher pastes broken text, the script tag
        // cannot end up inside an href.
        String out = HtmlText.escapeAndLinkify("https://example.com<script>alert(1)</script>");
        assertEquals("<a href=\"https://example.com\" target=\"_blank\" rel=\"noopener noreferrer\">"
                + "https://example.com</a>&lt;script&gt;alert(1)&lt;/script&gt;", out);
    }

    @Test
    void linkify_multipleUrlsInOneString() {
        String out = HtmlText.escapeAndLinkify("a https://x.example b https://y.example c");
        assertEquals("a <a href=\"https://x.example\" target=\"_blank\" rel=\"noopener noreferrer\">"
                + "https://x.example</a> b "
                + "<a href=\"https://y.example\" target=\"_blank\" rel=\"noopener noreferrer\">"
                + "https://y.example</a> c", out);
    }

    @Test
    void linkify_nullBecomesEmpty() {
        assertEquals("", HtmlText.escapeAndLinkify(null));
    }

    @Test
    void linkify_emptyBecomesEmpty() {
        assertEquals("", HtmlText.escapeAndLinkify(""));
    }

    @Test
    void linkify_doesNotMatchNonHttpSchemes() {
        // javascript:, data:, ftp:, mailto: — all left as plain (escaped) text.
        String out = HtmlText.escapeAndLinkify("javascript:alert(1) ftp://a mailto:x@y");
        assertEquals("javascript:alert(1) ftp://a mailto:x@y", out);
    }
}
