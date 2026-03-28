package com.pronote.persistence;

/**
 * Marker interface for domain objects that carry a stable Pronote ID.
 * Required by the generic {@link DiffEngine}.
 */
public interface Identifiable {
    String getId();
}
