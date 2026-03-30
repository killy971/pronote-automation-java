package com.pronote.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.pronote.persistence.Identifiable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A homework assignment fetched from Pronote.
 *
 * <p>Field names use English equivalents of the French Pronote API fields:
 * N → id, Matiere → subject, descriptif → description,
 * TAFFait → done, PourLe → dueDate, DonneLe → assignedDate.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Assignment implements Identifiable {

    private String id;
    private String subject;
    /** Enriched display name derived from {@code subject} via {@code subjectEnrichment} config
     *  rules. Assignments carry no teacher, so only subject-only rules apply.
     *  Equals {@code subject} when no rule matches. */
    private String enrichedSubject;
    private String description;
    private LocalDate dueDate;
    private LocalDate assignedDate;
    private boolean done;
    /**
     * Attachments parsed from Pronote's {@code ListePieceJointe} array.
     * Each entry holds the original URL, a stable Pronote ID, and (after downloading)
     * the local file path. Hyperlinks (G=0) are included but never downloaded.
     */
    private List<AttachmentRef> attachments = new ArrayList<>();

    public Assignment() {}

    @Override
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getEnrichedSubject() { return enrichedSubject; }
    public void setEnrichedSubject(String enrichedSubject) { this.enrichedSubject = enrichedSubject; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

    public LocalDate getAssignedDate() { return assignedDate; }
    public void setAssignedDate(LocalDate assignedDate) { this.assignedDate = assignedDate; }

    public boolean isDone() { return done; }
    public void setDone(boolean done) { this.done = done; }

    public List<AttachmentRef> getAttachments() { return attachments; }
    public void setAttachments(List<AttachmentRef> attachments) {
        this.attachments = attachments != null ? attachments : new ArrayList<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Assignment a)) return false;
        return Objects.equals(id, a.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }

    @Override
    public String toString() {
        return "Assignment{id='" + id + "', subject='" + subject + "', dueDate=" + dueDate + ", done=" + done + '}';
    }
}
