package com.pronote.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads and writes snapshot files for assignments and timetable data.
 *
 * <p>Layout under {@code dataDir/snapshots/{type}/}:
 * <pre>
 *   latest.json          — the most recent snapshot
 *   archive/
 *     2025-03-27T14-00-00Z.json  — previous snapshots (timestamped)
 * </pre>
 *
 * <p>On each save, the old {@code latest.json} is moved to the archive before
 * the new one is written, ensuring atomic-ish replacement.
 */
public class SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);
    private static final String LATEST = "latest.json";
    private static final DateTimeFormatter ARCHIVE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    private final Path dataDir;
    private final int archiveRetainDays;
    private final ObjectMapper jackson;

    public SnapshotStore(Path dataDir, int archiveRetainDays) {
        this.dataDir = dataDir;
        this.archiveRetainDays = archiveRetainDays;
        this.jackson = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Loads the latest snapshot for the given type.
     *
     * @param type e.g. "assignments" or "timetable"
     * @param ref  Jackson TypeReference for the list type
     * @return the deserialized list, or empty if no snapshot exists
     */
    public <T> Optional<List<T>> loadLatest(String type, TypeReference<List<T>> ref) {
        Path latest = snapshotDir(type).resolve(LATEST);
        if (!Files.exists(latest)) {
            log.debug("No existing snapshot for type '{}'", type);
            return Optional.empty();
        }
        try {
            List<T> items = jackson.readValue(latest.toFile(), ref);
            log.debug("Loaded {} items from snapshot '{}'", items.size(), type);
            return Optional.of(items);
        } catch (IOException e) {
            log.warn("Could not read snapshot '{}' (will treat as empty): {}", type, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Saves a new snapshot:
     * <ol>
     *   <li>Archives the existing {@code latest.json} (if present)</li>
     *   <li>Writes the new data as {@code latest.json}</li>
     *   <li>Purges archives older than {@code archiveRetainDays}</li>
     * </ol>
     */
    public <T> void saveSnapshot(String type, List<T> data) {
        Path dir = snapshotDir(type);
        Path archiveDir = dir.resolve("archive");
        Path latest = dir.resolve(LATEST);

        try {
            Files.createDirectories(archiveDir);

            // Archive existing latest
            if (Files.exists(latest)) {
                String timestamp = ARCHIVE_FMT.format(Instant.now());
                Path archivePath = archiveDir.resolve(timestamp + ".json");
                Files.move(latest, archivePath, StandardCopyOption.REPLACE_EXISTING);
                log.debug("Archived previous snapshot to {}", archivePath.getFileName());
            }

            // Write new latest
            jackson.writerWithDefaultPrettyPrinter().writeValue(latest.toFile(), data);
            log.info("Snapshot '{}' saved ({} items)", type, data.size());

            // Purge old archives
            purgeOldArchives(archiveDir);

        } catch (IOException e) {
            log.error("Failed to save snapshot '{}': {}", type, e.getMessage());
            throw new SnapshotException("Failed to save snapshot for type '" + type + "'", e);
        }
    }

    private void purgeOldArchives(Path archiveDir) {
        Instant cutoff = Instant.now().minusSeconds(archiveRetainDays * 86400L);
        try (Stream<Path> files = Files.list(archiveDir)) {
            files.filter(p -> p.toString().endsWith(".json"))
                 .filter(p -> {
                     try { return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff); }
                     catch (IOException e) { return false; }
                 })
                 .forEach(p -> {
                     try {
                         Files.delete(p);
                         log.debug("Purged old archive: {}", p.getFileName());
                     } catch (IOException e) {
                         log.warn("Could not purge archive file {}: {}", p, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.warn("Could not list archive directory for purge: {}", e.getMessage());
        }
    }

    /**
     * Loads the most recent archived snapshot for the given type (the one that was
     * {@code latest.json} before the last {@link #saveSnapshot} call).
     *
     * @param type e.g. "assignments" or "timetable"
     * @param ref  Jackson TypeReference for the list type
     * @return the deserialized list, or empty if no archive entry exists
     */
    public <T> Optional<List<T>> loadPrevious(String type, TypeReference<List<T>> ref) {
        Path archiveDir = snapshotDir(type).resolve("archive");
        if (!Files.exists(archiveDir)) {
            log.debug("No archive directory for type '{}'", type);
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(archiveDir)) {
            Optional<Path> mostRecent = files
                    .filter(p -> p.toString().endsWith(".json"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
            if (mostRecent.isEmpty()) {
                log.debug("Archive for type '{}' is empty", type);
                return Optional.empty();
            }
            List<T> items = jackson.readValue(mostRecent.get().toFile(), ref);
            log.debug("Loaded {} items from previous snapshot '{}' ({})",
                    items.size(), type, mostRecent.get().getFileName());
            return Optional.of(items);
        } catch (IOException e) {
            log.warn("Could not read previous snapshot '{}': {}", type, e.getMessage());
            return Optional.empty();
        }
    }

    private Path snapshotDir(String type) {
        return dataDir.resolve("snapshots").resolve(type);
    }

    public static class SnapshotException extends RuntimeException {
        public SnapshotException(String message, Throwable cause) { super(message, cause); }
    }
}
