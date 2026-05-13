package com.pronote.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pronote.domain.Assignment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotStoreTest {

    private static final TypeReference<List<Assignment>> ASSIGNMENT_LIST = new TypeReference<>() {};

    private static Assignment newAssignment(String id, String subject) {
        Assignment a = new Assignment();
        a.setId(id);
        a.setSubject(subject);
        a.setDueDate(LocalDate.of(2030, 1, 15));
        return a;
    }

    @Test
    void loadLatest_returnsEmpty_whenNoSnapshotExists(@TempDir Path dataDir) {
        SnapshotStore store = new SnapshotStore(dataDir, 30);
        assertTrue(store.loadLatest("assignments", ASSIGNMENT_LIST).isEmpty());
    }

    @Test
    void saveSnapshot_writesLatestJson(@TempDir Path dataDir) {
        SnapshotStore store = new SnapshotStore(dataDir, 30);
        List<Assignment> data = List.of(newAssignment("a-1", "TEST_SUBJECT"));

        store.saveSnapshot("assignments", data);

        Path latest = dataDir.resolve("snapshots/assignments/latest.json");
        assertTrue(Files.exists(latest));
    }

    @Test
    void saveThenLoadLatest_roundTrip_preservesIdAndSubject(@TempDir Path dataDir) {
        SnapshotStore store = new SnapshotStore(dataDir, 30);
        store.saveSnapshot("assignments", List.of(newAssignment("a-1", "TEST_SUBJECT")));

        Optional<List<Assignment>> reloaded = store.loadLatest("assignments", ASSIGNMENT_LIST);
        assertTrue(reloaded.isPresent());
        assertEquals(1, reloaded.get().size());
        assertEquals("a-1", reloaded.get().get(0).getId());
        assertEquals("TEST_SUBJECT", reloaded.get().get(0).getSubject());
    }

    @Test
    void saveSnapshot_archivesPreviousLatest(@TempDir Path dataDir) throws IOException {
        SnapshotStore store = new SnapshotStore(dataDir, 30);
        store.saveSnapshot("assignments", List.of(newAssignment("a-1", "first")));
        store.saveSnapshot("assignments", List.of(newAssignment("a-2", "second")));

        Path archive = dataDir.resolve("snapshots/assignments/archive");
        assertTrue(Files.exists(archive));
        try (var stream = Files.list(archive)) {
            long count = stream.filter(p -> p.toString().endsWith(".json")).count();
            assertEquals(1, count, "exactly one archive entry expected after two saves");
        }

        Optional<List<Assignment>> latest = store.loadLatest("assignments", ASSIGNMENT_LIST);
        assertTrue(latest.isPresent());
        assertEquals("a-2", latest.get().get(0).getId());
    }

    @Test
    void loadPrevious_returnsEmpty_whenNoArchiveExists(@TempDir Path dataDir) {
        SnapshotStore store = new SnapshotStore(dataDir, 30);
        assertTrue(store.loadPrevious("assignments", ASSIGNMENT_LIST).isEmpty());
    }

    @Test
    void loadPrevious_returnsMostRecentArchive(@TempDir Path dataDir) {
        SnapshotStore store = new SnapshotStore(dataDir, 30);
        store.saveSnapshot("assignments", List.of(newAssignment("a-1", "first")));
        store.saveSnapshot("assignments", List.of(newAssignment("a-2", "second")));

        Optional<List<Assignment>> previous = store.loadPrevious("assignments", ASSIGNMENT_LIST);
        assertTrue(previous.isPresent());
        assertEquals("a-1", previous.get().get(0).getId());
    }

    @Test
    void loadPrevious_picksLatestTimestampedFile_whenMultipleArchives(@TempDir Path dataDir) throws IOException {
        Path archive = dataDir.resolve("snapshots/assignments/archive");
        Files.createDirectories(archive);

        // Two synthetic archive files; lexicographic filename order picks the most recent.
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'")
                .withZone(ZoneOffset.UTC);
        String olderName  = fmt.format(Instant.parse("2030-01-01T00:00:00Z")) + ".json";
        String newerName  = fmt.format(Instant.parse("2030-06-01T00:00:00Z")) + ".json";
        Files.writeString(archive.resolve(olderName),
                "[{\"id\":\"older\",\"subject\":\"old\",\"done\":false,\"attachments\":[]}]");
        Files.writeString(archive.resolve(newerName),
                "[{\"id\":\"newer\",\"subject\":\"new\",\"done\":false,\"attachments\":[]}]");

        SnapshotStore store = new SnapshotStore(dataDir, 30);
        Optional<List<Assignment>> previous = store.loadPrevious("assignments", ASSIGNMENT_LIST);
        assertTrue(previous.isPresent());
        assertEquals("newer", previous.get().get(0).getId());
    }

    @Test
    void purgeOldArchives_deletesFilesOlderThanRetention(@TempDir Path dataDir) throws IOException {
        Path archive = dataDir.resolve("snapshots/assignments/archive");
        Files.createDirectories(archive);

        Path stale = archive.resolve("2020-01-01T00-00-00Z.json");
        Files.writeString(stale, "[]");
        // Backdate well beyond the retention window
        Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.from(
                Instant.now().minusSeconds(60L * 86400L)));

        SnapshotStore store = new SnapshotStore(dataDir, 30);
        store.saveSnapshot("assignments", List.of(newAssignment("fresh", "MATH")));

        assertFalse(Files.exists(stale), "stale archive should have been purged");
    }

    @Test
    void purgeOldArchives_keepsRecentFiles(@TempDir Path dataDir) throws IOException {
        Path archive = dataDir.resolve("snapshots/assignments/archive");
        Files.createDirectories(archive);

        Path fresh = archive.resolve("2030-01-01T00-00-00Z.json");
        Files.writeString(fresh, "[]");

        SnapshotStore store = new SnapshotStore(dataDir, 30);
        store.saveSnapshot("assignments", List.of(newAssignment("a-1", "MATH")));

        assertTrue(Files.exists(fresh), "fresh archive should be kept");
    }

    @Test
    void loadLatest_returnsEmpty_whenFileIsCorrupt(@TempDir Path dataDir) throws IOException {
        Path latest = dataDir.resolve("snapshots/assignments");
        Files.createDirectories(latest);
        Files.writeString(latest.resolve("latest.json"), "{not valid json");

        SnapshotStore store = new SnapshotStore(dataDir, 30);
        // The implementation logs and returns empty rather than throwing
        assertTrue(store.loadLatest("assignments", ASSIGNMENT_LIST).isEmpty());
    }
}
