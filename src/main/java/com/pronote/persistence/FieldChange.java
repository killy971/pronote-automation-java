package com.pronote.persistence;

/**
 * Represents a single field-level change between two snapshots of the same object.
 */
public class FieldChange {
    private final String fieldName;
    private final Object oldValue;
    private final Object newValue;

    public FieldChange(String fieldName, Object oldValue, Object newValue) {
        this.fieldName = fieldName;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public String getFieldName() { return fieldName; }
    public Object getOldValue() { return oldValue; }
    public Object getNewValue() { return newValue; }

    @Override
    public String toString() {
        return fieldName + ": " + oldValue + " → " + newValue;
    }
}
