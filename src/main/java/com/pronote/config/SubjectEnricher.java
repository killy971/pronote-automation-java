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
 * <p>Matching is exact and case-sensitive, because Pronote returns consistent strings.
 */
public class SubjectEnricher {

    private final List<AppConfig.SubjectEnrichmentRule> rules;

    public SubjectEnricher(AppConfig.SubjectEnrichmentConfig config) {
        this.rules = (config != null && config.getRules() != null)
                ? config.getRules()
                : Collections.emptyList();
    }

    /**
     * Returns the enriched subject name for the given subject and teacher combination.
     *
     * @param subject the raw Pronote subject string (may be null)
     * @param teacher the raw Pronote teacher string (may be null)
     * @return enriched subject name, or {@code subject} if no rule matches
     */
    public String enrich(String subject, String teacher) {
        if (subject == null) return null;

        // Pass 1: subject + teacher rules (most specific)
        for (AppConfig.SubjectEnrichmentRule rule : rules) {
            if (rule.getTeacher() != null
                    && subject.equals(rule.getSubject())
                    && rule.getTeacher().equals(teacher)) {
                return rule.getEnrichedSubject();
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
}
