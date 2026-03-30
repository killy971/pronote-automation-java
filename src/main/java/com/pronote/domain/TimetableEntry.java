package com.pronote.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.pronote.persistence.Identifiable;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A single timetable slot fetched from Pronote.
 *
 * <p>Field mapping from French Pronote API:
 * N → id, LibelleMatiereEnseignee → subject, Professeur → teacher,
 * Salle → room, DateDuCours → startTime, estAnnule → CANCELLED status, etc.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TimetableEntry implements Identifiable {

    private String id;
    private String subject;
    private String teacher;
    private String room;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private EntryStatus status = EntryStatus.NORMAL;
    /** Human-readable status label from Pronote's {@code Statut} field, e.g. "Prof. absent",
     *  "Exceptionnel", "Cours maintenu", "Cours modifié". Null when Pronote provides no label. */
    private String statusLabel;
    private boolean isTest;

    public TimetableEntry() {}

    @Override
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getTeacher() { return teacher; }
    public void setTeacher(String teacher) { this.teacher = teacher; }

    public String getRoom() { return room; }
    public void setRoom(String room) { this.room = room; }

    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

    public EntryStatus getStatus() { return status; }
    public void setStatus(EntryStatus status) { this.status = status; }

    public String getStatusLabel() { return statusLabel; }
    public void setStatusLabel(String statusLabel) { this.statusLabel = statusLabel; }

    public boolean isTest() { return isTest; }
    public void setTest(boolean test) { isTest = test; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TimetableEntry e)) return false;
        return Objects.equals(id, e.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }

    @Override
    public String toString() {
        return "TimetableEntry{id='" + id + "', subject='" + subject + "', start=" + startTime
                + ", status=" + status + '}';
    }
}
