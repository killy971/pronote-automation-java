package com.pronote.views;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML text helpers shared by the view generators.
 *
 * <p>Centralises escaping for teacher-supplied text (assignment descriptions, lesson memos)
 * so XSS-relevant decisions live in one place and can be audited.
 *
 * <p>{@link #escapeAndLinkify(String)} additionally turns {@code http://} and {@code https://}
 * URLs into anchor tags. The URL detection is deliberately simple — a regex match of
 * {@code https?://[^\s<>"']+}, with trailing sentence punctuation ({@code .,;:!?}) stripped
 * back into the surrounding text. This covers the common cases of teacher-written prose
 * (e.g. "voir https://example.com.") without needing a URL-parsing dependency.
 */
final class HtmlText {

    private HtmlText() {}

    /**
     * Matches {@code http://} or {@code https://} URLs up to the first whitespace, angle
     * bracket, or quote. The excluded characters cannot legally appear inside a URL anyway
     * and short-circuit common cases like {@code <a href="...">} or {@code "..."}.
     */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>\"']+");

    /** Sentence-final punctuation stripped from the trailing edge of a matched URL. */
    private static final String TRAILING_STRIPS = ".,;:!?";

    /** HTML-escape ampersands, angle brackets, and double quotes. Null becomes empty string. */
    static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * HTML-escape {@code s} and convert embedded {@code http(s)://} URLs into anchor tags.
     *
     * <p>Trailing sentence punctuation is emitted as text after the anchor so a sentence like
     * "voir https://example.com." doesn't pull the period into the link target.
     *
     * <p>Generated anchors carry {@code target="_blank"} and {@code rel="noopener noreferrer"}.
     */
    static String escapeAndLinkify(String s) {
        if (s == null) return "";
        Matcher m = URL_PATTERN.matcher(s);
        if (!m.find()) {
            return escape(s);
        }

        StringBuilder out = new StringBuilder(s.length() + 32);
        int cursor = 0;
        do {
            out.append(escape(s.substring(cursor, m.start())));

            String url = m.group();
            int trailLen = 0;
            while (trailLen < url.length()
                    && TRAILING_STRIPS.indexOf(url.charAt(url.length() - 1 - trailLen)) >= 0) {
                trailLen++;
            }
            String linkUrl = url.substring(0, url.length() - trailLen);
            String tail = url.substring(url.length() - trailLen);

            String escapedUrl = escape(linkUrl);
            out.append("<a href=\"").append(escapedUrl)
               .append("\" target=\"_blank\" rel=\"noopener noreferrer\">")
               .append(escapedUrl)
               .append("</a>")
               .append(escape(tail));

            cursor = m.end();
        } while (m.find());

        out.append(escape(s.substring(cursor)));
        return out.toString();
    }
}
