package com.pronote.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Persists and restores a {@link PronoteSession} to/from disk.
 *
 * <p>The session file ({@code session.json}) is written with owner-read-write
 * permissions only (600) to protect the AES key material.
 */
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private static final String FILENAME = "session.json";
    private static final Set<PosixFilePermission> OWNER_RW = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final Path sessionFile;
    private final ObjectMapper mapper;

    public SessionStore(Path dataDir) {
        this.sessionFile = dataDir.resolve(FILENAME);
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Loads the persisted session, if it exists and is readable.
     *
     * @return the session, or empty if not found or unreadable
     */
    public Optional<PronoteSession> load() {
        if (!Files.exists(sessionFile)) {
            log.debug("No session file found at {}", sessionFile);
            return Optional.empty();
        }
        try {
            PronoteSession session = mapper.readValue(sessionFile.toFile(), PronoteSession.class);
            log.debug("Session loaded from {}", sessionFile);
            return Optional.of(session);
        } catch (IOException e) {
            log.warn("Could not deserialize session file (will perform full login): {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Persists the session to disk. Sets file permissions to owner-read-write (600).
     */
    public void save(PronoteSession session) {
        try {
            Files.createDirectories(sessionFile.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(sessionFile.toFile(), session);
            try {
                Files.setPosixFilePermissions(sessionFile, OWNER_RW);
            } catch (UnsupportedOperationException e) {
                // Non-POSIX filesystem (e.g. Windows) — skip silently
                log.debug("POSIX permissions not supported on this filesystem; session file left with default permissions.");
            }
            log.debug("Session saved to {}", sessionFile);
        } catch (IOException e) {
            log.error("Failed to save session to {}: {}", sessionFile, e.getMessage());
        }
    }

    /** Deletes the session file, forcing a fresh login on the next run. */
    public void delete() {
        try {
            Files.deleteIfExists(sessionFile);
            log.debug("Session file deleted.");
        } catch (IOException e) {
            log.warn("Could not delete session file {}: {}", sessionFile, e.getMessage());
        }
    }
}
