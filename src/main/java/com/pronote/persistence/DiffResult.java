package com.pronote.persistence;

import java.util.List;
import java.util.Map;

/**
 * Result of comparing two snapshots.
 *
 * @param <T> the domain type being compared
 */
public class DiffResult<T extends Identifiable> {

    private final List<T> added;
    private final List<T> removed;
    private final Map<T, List<FieldChange>> modified;

    public DiffResult(List<T> added, List<T> removed, Map<T, List<FieldChange>> modified) {
        this.added = added;
        this.removed = removed;
        this.modified = modified;
    }

    public List<T> getAdded()                        { return added; }
    public List<T> getRemoved()                      { return removed; }
    public Map<T, List<FieldChange>> getModified()   { return modified; }

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
