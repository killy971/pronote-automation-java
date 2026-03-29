package com.pronote.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.pronote.persistence.Identifiable;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A grade (mark) fetched from Pronote via {@code DernieresNotes}.
 *
 * <p>French field mapping:
 * <pre>
 *   service.V.L     → subject
 *   note.V          → value   (string: "15,5", "ABS", "/" etc.)
 *   bareme.V        → outOf   (denominator, e.g. 20.0)
 *   coefficient     → coefficient
 *   date.V          → date    (format: "DD/MM/YYYY HH:MM:SS")
 *   commentaire     → comment (teacher's note / test title)
 *   periode.V.L     → periodName
 * </pre>
 *
 * <p>Stable ID: {@code subject@date@outOf@coefficient}. Pronote's {@code N} field is
 * session-scoped and must NOT be used. The tuple (subject, date, denominator, coefficient)
 * identifies the test event independently of the grade value, so corrections are detected
 * as modifications rather than new entries.
 *
 * <p>Known edge case: two tests in the same subject on the same day with identical
 * coefficient and denominator will share an ID. This is rare in practice.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Grade implements Identifiable {

    private String id;          // stable: subject@date@outOf@coefficient
    private String subject;
    private String value;       // raw grade string (e.g. "15,5", "ABS", "/")
    private double outOf;       // bareme – maximum possible grade
    private double coefficient;
    private LocalDate date;
    private String comment;     // teacher comment / test title
    private String periodName;  // academic period label

    public Grade() {}

    @Override
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public double getOutOf() { return outOf; }
    public void setOutOf(double outOf) { this.outOf = outOf; }

    public double getCoefficient() { return coefficient; }
    public void setCoefficient(double coefficient) { this.coefficient = coefficient; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public String getPeriodName() { return periodName; }
    public void setPeriodName(String periodName) { this.periodName = periodName; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Grade g)) return false;
        return Objects.equals(id, g.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }

    @Override
    public String toString() {
        return "Grade{id='" + id + "', subject='" + subject + "', value='" + value
                + "', date=" + date + ", period='" + periodName + "'}";
    }
}
