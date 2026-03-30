package com.pronote.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.pronote.persistence.Identifiable;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A vie scolaire (school life) event fetched from Pronote via {@code PagePresence} (onglet 19).
 *
 * <p>All event categories are returned by the same endpoint and distinguished by
 * the Pronote {@code G} discriminator:
 * <pre>
 *   G=13 → ABSENCE     (absence)
 *   G=14 → DELAY       (retard)
 *   G=21 → INFIRMARY   (passage infirmerie)
 *   G=41 → PUNISHMENT  (punition, retenue, exclusion)
 *   G=46 → OBSERVATION (observation, travail non fait, oubli de matériel)
 *   other → OTHER
 * </pre>
 *
 * <p>French field mapping (varies by type):
 * <pre>
 *   dateDebut.V          → date     (absences: start; infirmary: visit start)
 *   dateFin.V            → endDate  (absences: end; infirmary: visit end)
 *   date.V               → date     (delays, punishments, observations)
 *   duree                → minutes  (delay duration; or punishment duration)
 *   justifie             → justified
 *   listeMotifs.V[].L    → reasons  (semicolon-joined, for absences/delays/punishments)
 *   nature.V.L           → nature   (punishment type, e.g. "Retenue", "Exclusion")
 *   demandeur.V.L        → giver    (issuing teacher — punishments and observations)
 *   circonstances        → circumstances (free-text context)
 *   L                    → label    (observation category, e.g. "Travail non fait")
 *   matiere.V.L          → subject  (subject — observations only)
 *   time (HH:mm)         → time     (time extracted from date string when present)
 *   actesMedicaux.V[].L  → reasons  (infirmary: medical acts, semicolon-joined)
 *   symptomesMedicaux.V[].L → circumstances (infirmary: symptoms, semicolon-joined)
 * </pre>
 *
 * <p>Stable ID schemes (Pronote's {@code N} is session-scoped and must not be used):
 * <ul>
 *   <li>ABSENCE:     {@code ABSENCE@fromDate@toDate}</li>
 *   <li>DELAY:       {@code DELAY@date@minutes}</li>
 *   <li>INFIRMARY:   {@code INFIRMARY@fromDate@fromTime@toTime}</li>
 *   <li>PUNISHMENT:  {@code PUNISHMENT@date@nature@giver}</li>
 *   <li>OBSERVATION: {@code OBSERVATION@date@time@label@subject}</li>
 *   <li>OTHER:       {@code OTHER@date@G&lt;n&gt;}</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SchoolLifeEvent implements Identifiable {

    public enum EventType { ABSENCE, DELAY, INFIRMARY, PUNISHMENT, OBSERVATION, OTHER }

    private String id;             // stable, see class javadoc
    private EventType type;        // derived from G field
    private LocalDate date;        // start date (or event date for delay/punishment/observation)
    private LocalDate endDate;     // end date (for absences and infirmary visits)
    private String time;           // HH:mm extracted from date string (when present)
    private boolean justified;     // justifie
    private int minutes;           // duree (delay length or punishment duration in minutes)
    private String nature;         // nature.V.L (punishment type label)
    private String giver;          // demandeur.V.L (issuing teacher — punishments and observations)
    private String label;          // L field (observation category, e.g. "Travail non fait")
    private String subject;        // matiere.V.L (subject — observations only)
    private String reasons;        // listeMotifs joined; or actesMedicaux for infirmary
    private String circumstances;  // circonstances; or symptomesMedicaux for infirmary

    public SchoolLifeEvent() {}

    @Override
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public EventType getType() { return type; }
    public void setType(EventType type) { this.type = type; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }

    public boolean isJustified() { return justified; }
    public void setJustified(boolean justified) { this.justified = justified; }

    public int getMinutes() { return minutes; }
    public void setMinutes(int minutes) { this.minutes = minutes; }

    public String getNature() { return nature; }
    public void setNature(String nature) { this.nature = nature; }

    public String getGiver() { return giver; }
    public void setGiver(String giver) { this.giver = giver; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getReasons() { return reasons; }
    public void setReasons(String reasons) { this.reasons = reasons; }

    public String getCircumstances() { return circumstances; }
    public void setCircumstances(String circumstances) { this.circumstances = circumstances; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SchoolLifeEvent e)) return false;
        return Objects.equals(id, e.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }

    @Override
    public String toString() {
        return "SchoolLifeEvent{id='" + id + "', type=" + type + ", date=" + date
                + (time != null ? "@" + time : "")
                + (label != null ? ", label='" + label + "'" : "")
                + (subject != null ? ", subject='" + subject + "'" : "")
                + (giver != null ? ", giver='" + giver + "'" : "")
                + (reasons != null && !reasons.isBlank() ? ", reasons='" + reasons + "'" : "")
                + "}";
    }
}
