package com.pronote.scraper;

import com.pronote.config.ManualEntryLoader;
import com.pronote.domain.Assignment;
import com.pronote.domain.AttachmentRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copies attachments declared in {@code manual-entries.yaml} into the assignment attachments
 * directory, so that a locally-held file (a screenshot, a photo of the board) is linked from the
 * generated views exactly like a Pronote-hosted one.
 *
 * <p>This is the offline counterpart of {@link AttachmentDownloader}: same destination tree
 * ({@link AttachmentPaths}), same {@code localPath}/{@code mimeType} contract, but the bytes come
 * from the local filesystem rather than an authenticated Pronote URL. It holds no session and
 * makes no network call, which is what lets {@code --mode views} stage attachments too — manual
 * entries are re-injected from YAML on every run, so their attachments have to be resolvable
 * without a login.
 *
 * <h2>Copy rule</h2>
 * A file is copied when the target is absent, or when the source's size or last-modified time
 * differs from the target's. Pronote-hosted files are immutable, so {@link AttachmentDownloader}
 * can stop at an existence check; a local screenshot can be re-cropped in place under the same
 * name, and a pure existence check would pin the stale copy forever. Re-copying is invisible to
 * change detection: {@code localPath} and {@code mimeType} are excluded from the diff, and
 * {@code stableId} does not depend on the file's contents.
 *
 * <h2>Pruning</h2>
 * Manual attachment directories are owned entirely by the YAML: every manual assignment is rebuilt
 * from it on each run. So a file no longer listed for a manual assignment — and the whole
 * directory of a manual assignment that no longer exists — is deleted. Pruning is confined to
 * directories whose name comes from a {@code manual:}-prefixed ID; Pronote-sourced attachments are
 * never touched.
 *
 * <h2>Error handling</h2>
 * A missing source file or a failed copy is logged at WARN level and does not propagate:
 * {@code localPath} stays null and the assignment view omits that attachment. Use
 * {@code --mode validate} to check the paths in {@code manual-entries.yaml} before a run.
 */
public class ManualAttachmentStager {

    private static final Logger log = LoggerFactory.getLogger(ManualAttachmentStager.class);

    /** Directory-name prefix of a sanitized {@code manual:} assignment ID. */
    private static final String MANUAL_DIR_PREFIX = AttachmentPaths.sanitize(
            ManualEntryLoader.ID_PREFIX);

    private final Path attachmentsDir;

    public ManualAttachmentStager(Path attachmentsDir) {
        this.attachmentsDir = attachmentsDir;
    }

    /**
     * Stages every manual attachment in {@code assignments} and prunes orphaned files.
     *
     * <p>Non-manual assignments are ignored, as are refs without a {@code sourcePath}
     * (G=0 hyperlinks, and every Pronote-sourced attachment).
     *
     * <p>Idempotent: repeated calls within a run or across runs converge on the same tree.
     *
     * <p>Pass the <em>complete</em> assignment list: an assignment absent from it is taken to be
     * deleted, and its staged files are pruned. Calling with an empty list is therefore the
     * correct way to clear out every manual attachment, but calling it with a list that merely
     * failed to load would wrongly discard staged copies (harmlessly — the sources are outside
     * this tree and the next run re-copies them).
     *
     * @param assignments the full assignment list, manual entries already merged in
     */
    public void stage(List<Assignment> assignments) {
        Map<Path, Set<String>> expected = new HashMap<>();

        for (Assignment assignment : assignments) {
            if (!isManual(assignment.getId())) continue;
            Set<String> keep = expected.computeIfAbsent(
                    AttachmentPaths.assignmentDir(attachmentsDir, assignment.getId()),
                    dir -> new HashSet<>());

            for (AttachmentRef ref : assignment.getAttachments()) {
                if (ref.getSourcePath() == null) continue;
                Path target = AttachmentPaths.target(
                        attachmentsDir, assignment.getId(), ref.getFileName());
                if (copyIfStale(assignment, ref, target)) {
                    keep.add(target.getFileName().toString());
                }
            }
        }

        pruneOrphans(expected);
    }

    // -------------------------------------------------------------------------
    // Copy
    // -------------------------------------------------------------------------

    /**
     * Copies one attachment if the target is missing or out of date, and populates
     * {@code localPath}/{@code mimeType} on the ref.
     *
     * @return true when the target file is present afterwards (whether copied or already current)
     */
    private boolean copyIfStale(Assignment assignment, AttachmentRef ref, Path target) {
        Path source = Path.of(ref.getSourcePath());
        if (!Files.isRegularFile(source)) {
            log.warn("Manual attachment '{}' of assignment '{}' not found at {} — skipping",
                    ref.getFileName(), assignment.getId(), source);
            return false;
        }

        try {
            if (isUpToDate(source, target)) {
                log.debug("Manual attachment already staged: {}/{}",
                        target.getParent().getFileName(), target.getFileName());
            } else {
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                log.info("Staged manual attachment {} → {}/{}", source,
                        target.getParent().getFileName(), target.getFileName());
            }
            ref.setLocalPath(target.toAbsolutePath().toString());
            if (ref.getMimeType() == null) {
                try { ref.setMimeType(Files.probeContentType(target)); }
                catch (Exception ignored) {}
            }
            return true;
        } catch (IOException e) {
            log.warn("Failed to stage manual attachment '{}' of assignment '{}': {}",
                    ref.getFileName(), assignment.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * True when {@code target} already holds this exact revision of {@code source}.
     *
     * <p>Size plus last-modified time, the same test {@code rsync} makes by default. Hashing the
     * file would be stricter, but this runs on a Raspberry Pi every 15 minutes over a directory
     * that can hold photos — and the failure it guards against (an edited screenshot keeping its
     * name) always moves the mtime.
     */
    private static boolean isUpToDate(Path source, Path target) throws IOException {
        if (!Files.exists(target)) return false;
        if (Files.size(source) != Files.size(target)) return false;
        return Files.getLastModifiedTime(source).toMillis()
                == Files.getLastModifiedTime(target).toMillis();
    }

    // -------------------------------------------------------------------------
    // Pruning
    // -------------------------------------------------------------------------

    /**
     * Deletes staged files no longer declared in the YAML, and the directories of manual
     * assignments that no longer exist. Only {@code manual:}-derived directories are considered.
     *
     * @param expected per-manual-assignment directory → filenames that should survive
     */
    private void pruneOrphans(Map<Path, Set<String>> expected) {
        if (!Files.isDirectory(attachmentsDir)) return;

        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(attachmentsDir)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;
                if (!dir.getFileName().toString().startsWith(MANUAL_DIR_PREFIX)) continue;

                Set<String> keep = expected.get(dir);
                if (keep == null) {
                    deleteRecursively(dir);
                    log.info("Removed attachments of deleted manual entry: {}", dir.getFileName());
                    continue;
                }
                pruneFiles(dir, keep);
            }
        } catch (IOException e) {
            log.warn("Could not prune manual attachments under {}: {}", attachmentsDir, e.getMessage());
        }
    }

    /** Deletes every file in {@code dir} whose name is not in {@code keep}. */
    private void pruneFiles(Path dir, Set<String> keep) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                if (Files.isDirectory(file)) continue;
                if (keep.contains(file.getFileName().toString())) continue;
                Files.delete(file);
                log.info("Removed orphaned manual attachment: {}/{}",
                        dir.getFileName(), file.getFileName());
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) deleteRecursively(entry);
                else Files.delete(entry);
            }
        }
        Files.delete(dir);
    }

    private static boolean isManual(String assignmentId) {
        return assignmentId != null
                && assignmentId.startsWith(ManualEntryLoader.ID_PREFIX);
    }
}
