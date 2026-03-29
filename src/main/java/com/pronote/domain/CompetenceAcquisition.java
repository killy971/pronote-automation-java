package com.pronote.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single competence acquisition recorded within a {@link CompetenceEvaluation}.
 *
 * <p>Corresponds to one item in {@code listeNiveauxDAcquisitions.V} from
 * the {@code DernieresEvaluations} API response.
 *
 * <p>French field mapping:
 * <pre>
 *   L            → level        (achievement level label, e.g. "A Acquis", "En cours")
 *   abbreviation → abbreviation (short form, e.g. "A", "CA")
 *   domaine.V.L  → domain       (competence domain label)
 *   item.V.L     → name         (specific competence name)
 *   ordre        → order        (display order)
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CompetenceAcquisition {

    private String level;        // achieved level label (e.g. "A Acquis")
    private String abbreviation; // short label
    private String domain;       // competence domain
    private String name;         // specific competence name
    private int order;           // display order

    public CompetenceAcquisition() {}

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public String getAbbreviation() { return abbreviation; }
    public void setAbbreviation(String abbreviation) { this.abbreviation = abbreviation; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getOrder() { return order; }
    public void setOrder(int order) { this.order = order; }

    @Override
    public String toString() {
        return "CompetenceAcquisition{name='" + name + "', level='" + level + "'}";
    }
}
