package com.pronote.config;

import java.util.Collections;
import java.util.List;

/**
 * Resolves the enriched display name for a subject, optionally narrowed by teacher.
 *
 * <p>Rules are evaluated in declaration order with two priority tiers:
 * <ol>
 *   <li>Subject + teacher match (most specific)</li>
 *   <li>Subject-only match (teacher is null in the rule → applies to any teacher)</li>
 * </ol>
 * If no rule matches, the original subject value is returned unchanged.
 *
 * <p>Matching is exact and case-sensitive after prefix normalisation. Some Pronote API
 * endpoints (e.g. {@code DernieresEvaluations}) return teacher names prefixed with a civil
 * title ("M. DUPONT J.") while others (e.g. {@code PageEmploiDuTemps}) return the bare form
 * ("DUPONT J."). When {@code teacherPrefixes} are configured, the prefix is stripped before
 * rule matching so that a single rule covers both forms.
 */
public class SubjectEnricher {

    private final List<AppConfig.SubjectEnrichmentRule> rules;
    private final List<String> teacherPrefixes;

    public SubjectEnricher(AppConfig.SubjectEnrichmentConfig config) {
        this.rules = (config != null && config.getRules() != null)
                ? config.getRules()
                : Collections.emptyList();
        this.teacherPrefixes = (config != null && config.getTeacherPrefixes() != null)
                ? config.getTeacherPrefixes()
                : Collections.emptyList();
    }

    /**
     * Returns the enriched subject name for the given subject and teacher combination.
     *
     * <p>Teacher matching tries the raw value first, then the prefix-stripped value.
     * This means timetable teachers ("DUPONT J.") and evaluation teachers ("M. DUPONT J.")
     * both match the same rule without duplicating entries.
     *
     * @param subject the raw Pronote subject string (may be null)
     * @param teacher the raw Pronote teacher string (may be null)
     * @return enriched subject name, or {@code subject} if no rule matches
     */
    public String enrich(String subject, String teacher) {
        if (subject == null) return null;

        String normalizedTeacher = normalizeTeacher(teacher);

        // Pass 1: subject + teacher rules (most specific)
        // Try exact match first, then prefix-stripped form so a single rule covers both APIs.
        for (AppConfig.SubjectEnrichmentRule rule : rules) {
            if (rule.getTeacher() != null && subject.equals(rule.getSubject())) {
                if (rule.getTeacher().equals(teacher)
                        || rule.getTeacher().equals(normalizedTeacher)) {
                    return rule.getEnrichedSubject();
                }
            }
        }

        // Pass 2: subject-only rules (fallback)
        for (AppConfig.SubjectEnrichmentRule rule : rules) {
            if (rule.getTeacher() == null && subject.equals(rule.getSubject())) {
                return rule.getEnrichedSubject();
            }
        }

        return subject;
    }

    /**
     * Strips a configured civil-title prefix from a teacher name if one is present.
     * "M. DUPONT J." with prefix "M." → "DUPONT J.". Returns the input unchanged if no
     * prefix matches or if {@code teacherPrefixes} is empty.
     */
    private String normalizeTeacher(String teacher) {
        if (teacher == null || teacherPrefixes.isEmpty()) return teacher;
        String t = teacher.trim();
        for (String prefix : teacherPrefixes) {
            String full = prefix.endsWith(" ") ? prefix : prefix + " ";
            if (t.startsWith(full)) {
                return t.substring(full.length()).trim();
            }
        }
        return t;
    }
}
