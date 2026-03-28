package com.pronote.scraper;

import com.pronote.auth.PronoteSession;
import com.pronote.client.PronoteHttpClient;
import com.pronote.domain.Assignment;
import com.pronote.domain.AttachmentRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Downloads G=1 (uploaded file) attachments for assignments.
 *
 * <h2>Idempotency</h2>
 * Each attachment is stored at
 * {@code attachmentsDir/<sanitizedAssignmentId>/<sanitizedFileName>}.
 * Before any HTTP request, the downloader checks whether the file already
 * exists on disk. If it does, it sets {@code localPath} on the ref and moves on.
 * This means the method is safe to call on all assignments every run:
 * already-downloaded files are never re-fetched, and previously-failed
 * downloads are retried automatically.
 *
 * <h2>Rate limiting</h2>
 * Downloads go through {@link PronoteHttpClient#download}, which calls
 * {@code rateLimiter.await()} before every request — the same throttle
 * used for all other Pronote calls.
 *
 * <h2>Error handling</h2>
 * A failed download is logged at WARN level and does not propagate.
 * {@code localPath} on the failing ref remains null; the pipeline continues.
 *
 * <h2>Storage layout</h2>
 * <pre>
 *   data/snapshots/assignments/attachments/
 *       &lt;sanitized-assignmentId&gt;/
 *           cours_maths.pdf
 *           exercices.docx
 * </pre>
 *
 * <p>Per-assignment subdirectories prevent cross-assignment filename collisions.
 * The idempotency key is {@code attachmentsDir/<sanitizedAssignmentId>/<sanitizedFileName>}.
 */
public class AttachmentDownloader {

    private static final Logger log = LoggerFactory.getLogger(AttachmentDownloader.class);

    private final PronoteHttpClient httpClient;
    private final PronoteSession session;
    private final Path attachmentsDir;

    public AttachmentDownloader(PronoteHttpClient httpClient, PronoteSession session, Path attachmentsDir) {
        this.httpClient = httpClient;
        this.session = session;
        this.attachmentsDir = attachmentsDir;
    }

    /**
     * For every G=1 (uploaded file) attachment across all assignments:
     * <ul>
     *   <li>If the file already exists on disk: populate {@code localPath} — no HTTP call.</li>
     *   <li>If the file is missing: attempt to download it using the session-scoped
     *       {@code downloadUrl} (transient field, populated by {@code AssignmentScraper}).</li>
     * </ul>
     *
     * <p>G=0 (hyperlink) attachments are skipped — they are externally hosted
     * and not downloaded.
     *
     * <p>This method is idempotent: calling it multiple times per run or across
     * runs produces the same result.
     *
     * @param assignments all current assignments (new, unchanged, and modified)
     */
    public void processAttachments(List<Assignment> assignments) {
        try {
            Files.createDirectories(attachmentsDir);
        } catch (Exception e) {
            log.error("Cannot create attachments directory {}: {}", attachmentsDir, e.getMessage());
            return;
        }

        for (Assignment assignment : assignments) {
            for (AttachmentRef ref : assignment.getAttachments()) {
                if (ref.isUploadedFile()) {
                    downloadIfAbsent(assignment, ref);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves or downloads a single G=1 attachment.
     * Sets {@code localPath} (and {@code mimeType} on first download) on success.
     */
    private void downloadIfAbsent(Assignment assignment, AttachmentRef ref) {
        Path target = resolveTargetPath(assignment, ref);
        if (Files.exists(target)) {
            log.debug("Attachment already on disk, skipping download: {}/{}",
                    target.getParent().getFileName(), target.getFileName());
            ref.setLocalPath(target.toAbsolutePath().toString());
            if (ref.getMimeType() == null) {
                try { ref.setMimeType(Files.probeContentType(target)); }
                catch (Exception ignored) {}
            }
            return;
        }

        String downloadUrl = ref.getDownloadUrl();
        if (downloadUrl == null || downloadUrl.isBlank()) {
            // downloadUrl is transient: missing means the session expired before this run
            // could populate it (e.g. loaded from old snapshot). Nothing we can do.
            log.debug("No download URL for attachment '{}' — cannot fetch", ref.getFileName());
            return;
        }

        log.info("Downloading attachment '{}' for assignment '{}'", ref.getFileName(), assignment.getId());
        try {
            String mimeType = httpClient.download(downloadUrl, session.getCookies(), target);
            ref.setLocalPath(target.toAbsolutePath().toString());
            ref.setMimeType(mimeType);
            log.info("Saved attachment to {}/{}", target.getParent().getFileName(), target.getFileName());
        } catch (Exception e) {
            log.warn("Failed to download attachment '{}' for assignment '{}': {}",
                    ref.getFileName(), assignment.getId(), e.getMessage());
            // localPath remains null — non-fatal
        }
    }

    /**
     * Returns the deterministic, stable file path for an attachment.
     *
     * <p>Format: {@code attachmentsDir/<sanitizedAssignmentId>/<sanitizedFileName>}
     *
     * <p>The assignment ID and filename are both stable across sessions.
     * The Pronote {@code N} field is intentionally not used here — it is session-scoped.
     */
    private Path resolveTargetPath(Assignment assignment, AttachmentRef ref) {
        Path assignmentDir = attachmentsDir.resolve(sanitize(assignment.getId()));
        return assignmentDir.resolve(sanitize(ref.getFileName()));
    }

    /**
     * Sanitizes a string for use as a filename or directory name component.
     * Replaces any character that is not alphanumeric, dot, hyphen, or underscore with {@code _}.
     * Truncates to 120 characters to stay within filesystem limits.
     */
    private static String sanitize(String input) {
        if (input == null || input.isBlank()) return "_";
        String safe = input.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        return safe.length() > 120 ? safe.substring(0, 120) : safe;
    }
}
