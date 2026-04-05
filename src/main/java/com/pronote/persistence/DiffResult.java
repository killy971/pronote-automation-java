package com.pronote.persistence;

import java.util.List;
import java.util.Map;

/**
 * Result of comparing two snapshots.
 *
 * @param <T> the domain type being compared
 */
public record DiffResult<T extends Identifiable>(
        List<T> added,
        List<T> removed,
        Map<T, List<FieldChange>> modified) {

    /** Returns true if there are no differences at all. */
    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && modified.isEmpty();
    }

    @Override
    public String toString() {
        return "DiffResult{added=" + added.size() + ", removed=" + removed.size()
                + ", modified=" + modified.size() + "}";
    }
}
