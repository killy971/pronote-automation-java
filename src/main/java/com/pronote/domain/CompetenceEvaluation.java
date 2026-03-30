package com.pronote.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.pronote.persistence.Identifiable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A competence-based evaluation fetched from Pronote via {@code DernieresEvaluations}.
 *
 * <p>French field mapping:
 * <pre>
 *   L                            → name         (evaluation title, set by teacher)
 *   matiere.V.L                  → subject
 *   date.V                       → date         ("DD/MM/YYYY HH:MM:SS")
 *   individu.V.L                 → teacher
 *   descriptif                   → description
 *   periode.V.L                  → periodName
 *   listeNiveauxDAcquisitions.V  → acquisitions (competence levels achieved)
 * </pre>
 *
 * <p>Stable ID: {@code subject@date@name}. Pronote's {@code N} field is session-scoped.
 * The tuple (subject, date, evaluation name) uniquely identifies a competence evaluation
 * across runs and sessions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CompetenceEvaluation implements Identifiable {

    private String id;          // stable: subject@date@name
    private String name;        // evaluation title
    private String subject;
    private String enrichedSubject;
    private LocalDate date;
    private String teacher;
    private String description;
    private String periodName;
    private List<CompetenceAcquisition> acquisitions = new ArrayList<>();

    public CompetenceEvaluation() {}

    @Override
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getEnrichedSubject() { return enrichedSubject; }
    public void setEnrichedSubject(String enrichedSubject) { this.enrichedSubject = enrichedSubject; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public String getTeacher() { return teacher; }
    public void setTeacher(String teacher) { this.teacher = teacher; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getPeriodName() { return periodName; }
    public void setPeriodName(String periodName) { this.periodName = periodName; }

    public List<CompetenceAcquisition> getAcquisitions() { return acquisitions; }
    public void setAcquisitions(List<CompetenceAcquisition> acquisitions) {
        this.acquisitions = acquisitions != null ? acquisitions : new ArrayList<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CompetenceEvaluation e)) return false;
        return Objects.equals(id, e.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }

    @Override
    public String toString() {
        return "CompetenceEvaluation{id='" + id + "', subject='" + subject + "', name='" + name
                + "', date=" + date + ", acquisitions=" + acquisitions.size() + '}';
    }
}
