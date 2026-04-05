package com.pronote.persistence;

/**
 * Represents a single field-level change between two snapshots of the same object.
 */
public record FieldChange(String fieldName, Object oldValue, Object newValue) {

    @Override
    public String toString() {
        return fieldName + ": " + oldValue + " → " + newValue;
    }
}
