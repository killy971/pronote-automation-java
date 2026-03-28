package com.pronote.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pronote.domain.AttachmentRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Computes field-level differences between two snapshots of a list of domain objects.
 *
 * <p>Comparison is ID-keyed: objects with the same {@link Identifiable#getId()} are
 * compared field by field using Jackson's tree model. This avoids implementing
 * per-type comparison logic and naturally handles new/removed fields.
 *
 * <p>Objects with a null or blank ID are logged and skipped.
 */
public class DiffEngine {

    private static final Logger log = LoggerFactory.getLogger(DiffEngine.class);

    private final ObjectMapper jackson;

    public DiffEngine() {
        this.jackson = new ObjectMapper().registerModule(new JavaTimeModule());
        // Exclude runtime-only fields from AttachmentRef so they don't cause spurious diffs.
        // localPath and mimeType are populated after download and vary between runs even when
        // the attachment itself hasn't changed. url is excluded because G=1 attachments carry
        // it in the transient downloadUrl field (never persisted), leaving url=null in snapshots.
        this.jackson.addMixIn(AttachmentRef.class, AttachmentRefDiffMixin.class);
    }

    /** Mixin that suppresses runtime/transient fields from diff comparison. */
    @JsonIgnoreProperties({"localPath", "mimeType", "url"})
    private abstract static class AttachmentRefDiffMixin {}

    /**
     * Computes the diff between two snapshots.
     *
     * @param previous snapshot from the previous run (may be empty)
     * @param current  freshly fetched snapshot
     * @param <T>      domain type
     * @return diff result
     */
    public <T extends Identifiable> DiffResult<T> diff(List<T> previous, List<T> current) {
        Map<String, T> prevMap = index(previous);
        Map<String, T> currMap = index(current);

        List<T> added = new ArrayList<>();
        List<T> removed = new ArrayList<>();
        Map<T, List<FieldChange>> modified = new LinkedHashMap<>();

        // Find added items
        for (Map.Entry<String, T> entry : currMap.entrySet()) {
            if (!prevMap.containsKey(entry.getKey())) {
                added.add(entry.getValue());
            }
        }

        // Find removed items
        for (Map.Entry<String, T> entry : prevMap.entrySet()) {
            if (!currMap.containsKey(entry.getKey())) {
                removed.add(entry.getValue());
            }
        }

        // Find modified items
        for (Map.Entry<String, T> entry : currMap.entrySet()) {
            if (!prevMap.containsKey(entry.getKey())) continue;
            T prev = prevMap.get(entry.getKey());
            T curr = entry.getValue();

            List<FieldChange> changes = compareFields(prev, curr);
            if (!changes.isEmpty()) {
                modified.put(curr, changes);
            }
        }

        DiffResult<T> result = new DiffResult<>(added, removed, modified);
        log.debug("Diff computed: {}", result);
        return result;
    }

    private <T extends Identifiable> List<FieldChange> compareFields(T prev, T curr) {
        ObjectNode prevNode = jackson.valueToTree(prev);
        ObjectNode currNode = jackson.valueToTree(curr);

        List<FieldChange> changes = new ArrayList<>();
        Set<String> allFields = new LinkedHashSet<>();
        prevNode.fieldNames().forEachRemaining(allFields::add);
        currNode.fieldNames().forEachRemaining(allFields::add);

        for (String field : allFields) {
            JsonNode prevVal = prevNode.get(field);
            JsonNode currVal = currNode.get(field);

            // Skip internal / identity fields
            if ("id".equals(field)) continue;

            boolean prevNull = prevVal == null || prevVal.isNull();
            boolean currNull = currVal == null || currVal.isNull();

            if (prevNull && currNull) continue;
            if (!Objects.equals(prevVal, currVal)) {
                changes.add(new FieldChange(field,
                        prevNull ? null : prevVal.asText(),
                        currNull ? null : currVal.asText()));
            }
        }
        return changes;
    }

    private <T extends Identifiable> Map<String, T> index(List<T> items) {
        Map<String, T> map = new LinkedHashMap<>();
        for (T item : items) {
            String id = item.getId();
            if (id == null || id.isBlank()) {
                log.warn("Skipping item with null/blank ID: {}", item);
                continue;
            }
            map.put(id, item);
        }
        return map;
    }
}
