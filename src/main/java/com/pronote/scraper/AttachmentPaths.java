package com.pronote.scraper;

import java.nio.file.Path;

/**
 * Shared on-disk layout for assignment attachments.
 *
 * <p>Both {@link AttachmentDownloader} (Pronote-hosted G=1 files) and
 * {@link ManualAttachmentStager} (files declared in {@code manual-entries.yaml}) write into the
 * same tree, and the assignment view builds its {@code href} by relativizing the resulting
 * {@code localPath}. The layout therefore lives in one place: a mismatch between the two writers
 * would produce links that silently 404.
 *
 * <pre>
 *   data/snapshots/assignments/attachments/
 *       &lt;sanitized-assignmentId&gt;/
 *           &lt;sanitized-fileName&gt;
 * </pre>
 *
 * <p>Per-assignment subdirectories prevent cross-assignment filename collisions. Both components
 * of the path are session-independent, which is what makes the existence check a valid
 * idempotency key.
 */
final class AttachmentPaths {

    private AttachmentPaths() {}

    /** Returns the deterministic target path for one attachment. */
    static Path target(Path attachmentsDir, String assignmentId, String fileName) {
        return attachmentsDir.resolve(sanitize(assignmentId)).resolve(sanitize(fileName));
    }

    /** Returns the directory holding every attachment of one assignment. */
    static Path assignmentDir(Path attachmentsDir, String assignmentId) {
        return attachmentsDir.resolve(sanitize(assignmentId));
    }

    /**
     * Sanitizes a string for use as a filename or directory name component.
     * Replaces any character that is not alphanumeric, dot, hyphen, or underscore with {@code _}.
     * Truncates to 120 characters to stay within filesystem limits.
     */
    static String sanitize(String input) {
        if (input == null || input.isBlank()) return "_";
        String safe = input.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        return safe.length() > 120 ? safe.substring(0, 120) : safe;
    }
}
