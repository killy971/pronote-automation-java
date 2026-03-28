package com.pronote.domain;

/**
 * Status of a timetable entry.
 * Derived from the boolean flags in the Pronote API response.
 */
public enum EntryStatus {
    /** Regular lesson — no modifications. */
    NORMAL,
    /** Lesson has been cancelled (estAnnule = true). */
    CANCELLED,
    /** Lesson has been modified (estModifie = true). */
    MODIFIED,
    /** Student is exempted from this lesson (estExempte = true). */
    EXEMPTED
}
