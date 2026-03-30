package com.pronote.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SubjectEnricherTest {

    private static AppConfig.SubjectEnrichmentConfig config(AppConfig.SubjectEnrichmentRule... rules) {
        AppConfig.SubjectEnrichmentConfig cfg = new AppConfig.SubjectEnrichmentConfig();
        cfg.setRules(List.of(rules));
        return cfg;
    }

    private static AppConfig.SubjectEnrichmentRule rule(String subject, String teacher, String enriched) {
        AppConfig.SubjectEnrichmentRule r = new AppConfig.SubjectEnrichmentRule();
        r.setSubject(subject);
        r.setTeacher(teacher);
        r.setEnrichedSubject(enriched);
        return r;
    }

    @Test
    void subjectAndTeacherMatch_returnsEnrichedName() {
        SubjectEnricher enricher = new SubjectEnricher(config(
                rule("HISTOIRE-GEOGRAPHIE", "BUKOWIECKI J.", "Histoire"),
                rule("HISTOIRE-GEOGRAPHIE", "LATZKE W.",    "Géographie")
        ));
        assertEquals("Histoire",   enricher.enrich("HISTOIRE-GEOGRAPHIE", "BUKOWIECKI J."));
        assertEquals("Géographie", enricher.enrich("HISTOIRE-GEOGRAPHIE", "LATZKE W."));
    }

    @Test
    void subjectOnlyRule_appliesWhenTeacherDoesNotMatchAnySpecificRule() {
        SubjectEnricher enricher = new SubjectEnricher(config(
                rule("MATHEMATIQUES", null, "Maths")
        ));
        assertEquals("Maths", enricher.enrich("MATHEMATIQUES", "DUPONT A."));
        assertEquals("Maths", enricher.enrich("MATHEMATIQUES", null));
    }

    @Test
    void specificRuleBeatsSubjectOnlyRule() {
        // Both a subject+teacher rule and a subject-only fallback exist for the same subject.
        // The specific rule must win.
        SubjectEnricher enricher = new SubjectEnricher(config(
                rule("HISTOIRE-GEOGRAPHIE", "BUKOWIECKI J.", "Histoire"),
                rule("HISTOIRE-GEOGRAPHIE", null,            "Histoire-Géographie")
        ));
        assertEquals("Histoire",             enricher.enrich("HISTOIRE-GEOGRAPHIE", "BUKOWIECKI J."));
        assertEquals("Histoire-Géographie",  enricher.enrich("HISTOIRE-GEOGRAPHIE", "OTHER T."));
    }

    @Test
    void noMatchingRule_returnsOriginalSubject() {
        SubjectEnricher enricher = new SubjectEnricher(config(
                rule("MATHEMATIQUES", null, "Maths")
        ));
        assertEquals("PHYSIQUE-CHIMIE", enricher.enrich("PHYSIQUE-CHIMIE", "MARTIN B."));
    }

    @Test
    void nullSubject_returnsNull() {
        SubjectEnricher enricher = new SubjectEnricher(config());
        assertNull(enricher.enrich(null, "DUPONT A."));
    }

    @Test
    void emptyRules_returnsOriginalSubject() {
        SubjectEnricher enricher = new SubjectEnricher(config());
        assertEquals("ANGLAIS", enricher.enrich("ANGLAIS", "SMITH J."));
    }

    @Test
    void nullConfig_returnsOriginalSubject() {
        SubjectEnricher enricher = new SubjectEnricher(null);
        assertEquals("ANGLAIS", enricher.enrich("ANGLAIS", "SMITH J."));
    }

    @Test
    void matchingIsExactCaseSensitive() {
        SubjectEnricher enricher = new SubjectEnricher(config(
                rule("HISTOIRE-GEOGRAPHIE", "BUKOWIECKI J.", "Histoire")
        ));
        // Wrong case for subject → no match
        assertEquals("histoire-geographie", enricher.enrich("histoire-geographie", "BUKOWIECKI J."));
        // Wrong case for teacher → no match
        assertEquals("HISTOIRE-GEOGRAPHIE", enricher.enrich("HISTOIRE-GEOGRAPHIE", "bukowiecki j."));
    }
}
