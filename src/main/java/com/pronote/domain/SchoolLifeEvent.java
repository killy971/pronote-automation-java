package com.pronote.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.pronote.persistence.Identifiable;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A vie scolaire (school life) event fetched from Pronote via {@code PagePresence} (onglet 19).
 *
 * <p>All three event categories are returned by the same endpoint and distinguished by
 * the Pronote {@code G} discriminator:
 * <pre>
 *   G=13 → ABSENCE
 *   G=14 → DELAY (retard)
 *   G=41 → PUNISHMENT (punition, retenue, exclusion)
 *   other → OTHER
 * </pre>
 *
 * <p>French field mapping (varies by type):
 * <pre>
 *   dateDebut.V          → date     (for absences: start of absence)
 *   dateFin.V            → endDate  (for absences: end of absence)
 *   date.V               → date     (for delays and punishments)
 *   duree                → minutes  (delay duration; or punishment duration)
 *   justifie             → justified
 *   listeMotifs.V[].L    → reasons  (semicolon-joined, e.g. "Oubli de matériel")
 *   nature.V.L           → nature   (punishment type, e.g. "Retenue", "Exclusion")
 *   demandeur.V.L        → giver    (teacher/staff who issued the punishment)
 *   circonstances        → circumstances (free-text context)
 * </pre>
 *
 * <p>Stable ID schemes (Pronote's {@code N} is session-scoped and must not be used):
 * <ul>
 *   <li>ABSENCE:    {@code ABSENCE@fromDate@toDate}</li>
 *   <li>DELAY:      {@code DELAY@date@minutes}</li>
 *   <li>PUNISHMENT: {@code PUNISHMENT@date@nature@giver}</li>
 *   <li>OTHER:      {@code OTHER@date@reasonsPrefix}</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SchoolLifeEvent implements Identifiable {

    public enum EventType { ABSENCE, DELAY, PUNISHMENT, OTHER }

    private String id;             // stable, see class javadoc
    private EventType type;        // derived from G field
    private LocalDate date;        // start date (or event date for delay/punishment)
    private LocalDate endDate;     // end date (for absences only)
    private boolean justified;     // justifie
    private int minutes;           // duree (delay length or punishment duration in minutes)
    private String nature;         // nature.V.L (punishment type label)
    private String giver;          // demandeur.V.L (issuing teacher)
    private String reasons;        // listeMotifs joined (e.g. "Travail non rendu; Oubli de matériel")
    private String circumstances;  // circonstances (free text)

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

    public boolean isJustified() { return justified; }
    public void setJustified(boolean justified) { this.justified = justified; }

    public int getMinutes() { return minutes; }
    public void setMinutes(int minutes) { this.minutes = minutes; }

    public String getNature() { return nature; }
    public void setNature(String nature) { this.nature = nature; }

    public String getGiver() { return giver; }
    public void setGiver(String giver) { this.giver = giver; }

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
                + ", reasons='" + reasons + "'}";
    }
}
