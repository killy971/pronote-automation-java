package com.pronote.views;

import com.pronote.config.AppConfig.ViewPublishConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Publishes generated view files (and optionally attachments) to a local GitHub Pages
 * repository clone.
 *
 * <h3>File layout in the repo</h3>
 * <pre>
 *   &lt;repoPath&gt;/
 *     &lt;targetSubdir&gt;/
 *       timetable/          ← flat copy of timetableView.outputDirectory
 *       assignments/        ← flat copy of assignmentView.outputDirectory
 *     &lt;recursiveMirror dest&gt;/
 *       &lt;id&gt;/file.pdf      ← recursive copy preserving the relative-path structure
 * </pre>
 *
 * The recursive mirrors are placed at a path (relative to the repo root) chosen so that
 * the relative hrefs already embedded in the generated HTML remain valid without any
 * link rewriting.  For attachments this means:
 * <pre>
 *   relative href in index.html  = ../../snapshots/assignments/attachments/X/Y
 *   index.html lives in repo at  = &lt;targetSubdir&gt;/assignments/
 *   resolved destination in repo = snapshots/assignments/attachments/X/Y
 * </pre>
 *
 * <h3>Concurrency safety</h3>
 * <ol>
 *   <li><b>git index lock</b> — {@code FileChannel.lock()} on
 *       {@code &lt;repoPath&gt;/.pronote-publish.lock} serialises all git operations on this host.
 *       The OS releases the lock automatically if the process exits for any reason.</li>
 *   <li><b>non-fast-forward push</b> — any non-zero {@code git push} exit triggers
 *       {@code git pull --rebase} and a retry, up to {@link ViewPublishConfig#getPushRetries()}
 *       times.</li>
 * </ol>
 *
 * <h3>Copy strategies</h3>
 * <ul>
 *   <li><b>Flat (view dirs)</b>: stale files in target are deleted before copying, so old
 *       dated HTML pages are removed when they leave the {@code daysAhead} window.</li>
 *   <li><b>Recursive (attachment mirrors)</b>: files are overwritten but never deleted;
 *       accumulated attachments are harmless and avoid breaking links from cached pages.</li>
 * </ul>
 */
public class GitPublisher {

    private static final Logger log = LoggerFactory.getLogger(GitPublisher.class);
    private static final String LOCK_FILE_NAME = ".pronote-publish.lock";

    /**
     * Publishes view files and attachment mirrors to the configured GitHub Pages repo.
     *
     * @param viewDirs        key = subdirectory name under {@code <repoPath>/<targetSubdir>/};
     *                        value = source directory (flat copy, stale files removed)
     * @param recursiveMirrors key = absolute source directory;
     *                         value = destination path <em>relative to the repo root</em>
     *                         (recursive copy, no deletions)
     * @param config          publish configuration
     */
    public void publish(Map<String, Path> viewDirs,
                        Map<Path, Path> recursiveMirrors,
                        ViewPublishConfig config) {
        if (!config.isEnabled()) {
            log.debug("View publishing disabled — skipping.");
            return;
        }
        if (viewDirs.isEmpty() && recursiveMirrors.isEmpty()) {
            log.debug("Nothing to publish — skipping.");
            return;
        }

        Path repoPath = Path.of(config.getRepoPath());
        if (!Files.isDirectory(repoPath.resolve(".git"))) {
            log.warn("viewPublish.repoPath does not contain a .git directory — skipping publish: {}", repoPath);
            return;
        }

        Path lockFile = repoPath.resolve(LOCK_FILE_NAME);
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {

            log.info("Acquired publish lock ({})", lockFile);
            doPublish(viewDirs, recursiveMirrors, config, repoPath);

        } catch (IOException e) {
            throw new GitPublisherException("Failed to acquire publish lock at " + lockFile, e);
        }
    }

    // -------------------------------------------------------------------------

    private void doPublish(Map<String, Path> viewDirs,
                           Map<Path, Path> recursiveMirrors,
                           ViewPublishConfig config,
                           Path repoPath) {

        // 1. Flat-copy each view directory into <targetSubdir>/<name>/
        for (Map.Entry<String, Path> entry : viewDirs.entrySet()) {
            Path targetDir = repoPath.resolve(config.getTargetSubdir()).resolve(entry.getKey());
            copyFlat(entry.getValue(), targetDir, entry.getKey());
        }

        // 2. Recursively mirror attachment directories
        for (Map.Entry<Path, Path> entry : recursiveMirrors.entrySet()) {
            Path destAbsolute = repoPath.resolve(entry.getValue());
            copyRecursive(entry.getKey(), destAbsolute);
        }

        // 3. Stage everything: targetSubdir + each recursive mirror root
        List<String> gitAddArgs = new ArrayList<>();
        gitAddArgs.add("git");
        gitAddArgs.add("add");
        gitAddArgs.add(config.getTargetSubdir());
        for (Map.Entry<Path, Path> entry : recursiveMirrors.entrySet()) {
            gitAddArgs.add(entry.getValue().toString());
        }
        runGit(repoPath, gitAddArgs.toArray(new String[0]));
        log.info("git add: {}", gitAddArgs.subList(2, gitAddArgs.size()));

        // 4. Check whether anything was staged
        List<String> diffArgs = new ArrayList<>();
        diffArgs.add("git");
        diffArgs.add("diff");
        diffArgs.add("--cached");
        diffArgs.add("--name-only");
        diffArgs.add("--");
        diffArgs.add(config.getTargetSubdir());
        for (Map.Entry<Path, Path> entry : recursiveMirrors.entrySet()) {
            diffArgs.add(entry.getValue().toString());
        }
        List<String> staged = runGitLines(repoPath, diffArgs.toArray(new String[0]));
        if (staged.isEmpty()) {
            log.info("No changes staged — skipping commit and push.");
            return;
        }
        log.info("{} file(s) changed", staged.size());

        // 5. Commit
        runGit(repoPath, "git", "commit", "-m", config.getCommitMessage());
        log.info("git commit: {}", config.getCommitMessage());

        // 6. Push with pull-rebase retry on rejection
        pushWithRetry(repoPath, config.getPushRetries());
    }

    // -------------------------------------------------------------------------
    // Copy helpers
    // -------------------------------------------------------------------------

    /**
     * Flat copy: synchronises {@code source} into {@code target}, deleting files in
     * {@code target} that no longer exist in {@code source}.  Sub-directories are ignored.
     */
    private void copyFlat(Path source, Path target, String label) {
        if (!Files.isDirectory(source)) {
            log.warn("View source directory not found for '{}': {} — skipping", label, source);
            return;
        }
        try {
            Files.createDirectories(target);

            // Delete stale files (in target but absent from source)
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
                for (Path existing : stream) {
                    if (!Files.isDirectory(existing)
                            && !Files.exists(source.resolve(existing.getFileName()))) {
                        Files.delete(existing);
                        log.debug("Removed stale file: {}", existing);
                    }
                }
            }

            // Copy all non-directory source files
            int copied = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(source)) {
                for (Path file : stream) {
                    if (!Files.isDirectory(file)) {
                        Files.copy(file, target.resolve(file.getFileName()),
                                StandardCopyOption.REPLACE_EXISTING);
                        copied++;
                    }
                }
            }
            log.info("Flat-copied {} file(s): {} → {}", copied, source, target);

        } catch (IOException e) {
            throw new GitPublisherException(
                    "Failed to flat-copy from " + source + " to " + target, e);
        }
    }

    /**
     * Recursive copy: mirrors the entire {@code source} tree into {@code dest},
     * overwriting existing files.  Directories are created as needed; no deletions.
     */
    private void copyRecursive(Path source, Path dest) {
        if (!Files.isDirectory(source)) {
            log.warn("Attachment source directory not found: {} — skipping", source);
            return;
        }
        try {
            int[] copied = {0};
            try (Stream<Path> walk = Files.walk(source)) {
                walk.forEach(src -> {
                    Path relative = source.relativize(src);
                    Path target = dest.resolve(relative);
                    try {
                        if (Files.isDirectory(src)) {
                            Files.createDirectories(target);
                        } else {
                            Files.createDirectories(target.getParent());
                            Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
                            copied[0]++;
                        }
                    } catch (IOException e) {
                        throw new GitPublisherException(
                                "Failed to copy " + src + " → " + target, e);
                    }
                });
            }
            log.info("Recursively copied {} file(s): {} → {}", copied[0], source, dest);
        } catch (IOException e) {
            throw new GitPublisherException(
                    "Failed to walk source directory: " + source, e);
        }
    }

    // -------------------------------------------------------------------------
    // Push with retry
    // -------------------------------------------------------------------------

    private void pushWithRetry(Path repoPath, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("git push (attempt {}/{})", attempt, maxRetries);
                runGit(repoPath, "git", "push");
                log.info("git push succeeded.");
                return;
            } catch (GitPublisherException e) {
                if (attempt == maxRetries) {
                    throw new GitPublisherException(
                            "git push failed after " + maxRetries + " attempt(s): " + e.getMessage(), e);
                }
                log.warn("git push failed (attempt {}/{}) — pulling with rebase before retry: {}",
                        attempt, maxRetries, e.getMessage());
                runGit(repoPath, "git", "pull", "--rebase");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Process helpers
    // -------------------------------------------------------------------------

    private void runGit(Path workDir, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (!output.isBlank()) {
                log.debug("{} output: {}", command[1], output.trim());
            }
            if (exitCode != 0) {
                throw new GitPublisherException(
                        "'" + String.join(" ", command) + "' exited " + exitCode
                        + (output.isBlank() ? "" : ": " + output.trim()));
            }
        } catch (IOException e) {
            throw new GitPublisherException("Failed to run: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitPublisherException("Interrupted while running: " + String.join(" ", command), e);
        }
    }

    /** Runs a git command and returns its stdout split into non-blank lines. */
    private List<String> runGitLines(Path workDir, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .start();
            String stdout = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            return stdout.lines().filter(l -> !l.isBlank()).toList();
        } catch (IOException e) {
            throw new GitPublisherException("Failed to run: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitPublisherException("Interrupted while running: " + String.join(" ", command), e);
        }
    }
}
