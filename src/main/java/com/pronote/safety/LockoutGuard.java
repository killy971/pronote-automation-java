package com.pronote.safety;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Tracks consecutive login failures and halts the job if the threshold is reached.
 * State is persisted to {@code {dataDir}/lockout.json} across runs.
 *
 * <p>A locked-out job must be manually reset by deleting or editing lockout.json.
 */
public class LockoutGuard {

    private static final Logger log = LoggerFactory.getLogger(LockoutGuard.class);
    private static final String FILENAME = "lockout.json";

    private final Path lockoutFile;
    private final int maxFailures;
    private final ObjectMapper mapper;

    public LockoutGuard(Path dataDir, int maxFailures) {
        this.lockoutFile = dataDir.resolve(FILENAME);
        this.maxFailures = maxFailures;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Throws {@link LockoutException} if the consecutive failure count meets or exceeds the limit.
     */
    public void checkAndThrowIfLocked() {
        LockoutState state = load();
        if (state.consecutiveFailures >= maxFailures) {
            throw new LockoutException(
                    "Locked out after " + state.consecutiveFailures + " consecutive login failures "
                    + "(last failure: " + state.lastFailureTimestamp + "). "
                    + "Delete or reset " + lockoutFile + " to unlock.");
        }
        if (state.consecutiveFailures > 0) {
            log.warn("Previous login failures recorded: {} (limit: {})",
                    state.consecutiveFailures, maxFailures);
        }
    }

    /** Records a successful authentication, resetting the failure counter. */
    public void recordSuccess() {
        LockoutState state = load();
        state.consecutiveFailures = 0;
        state.lastFailureTimestamp = null;
        save(state);
        log.debug("Lockout counter reset after successful login.");
    }

    /** Increments the consecutive failure counter and persists the updated state. */
    public void recordFailure() {
        LockoutState state = load();
        state.consecutiveFailures++;
        state.lastFailureTimestamp = Instant.now();
        save(state);
        log.warn("Login failure recorded. Consecutive failures: {}/{}", state.consecutiveFailures, maxFailures);
    }

    /**
     * Records that a full login attempt is about to be made, regardless of outcome.
     * Used by {@link #secondsUntilNextAttemptAllowed} to space out full re-authentications —
     * session-reuse probes don't count, only actual calls to {@code PronoteAuthenticator.login()}.
     */
    public void recordAttempt() {
        LockoutState state = load();
        state.lastAttemptTimestamp = Instant.now();
        save(state);
    }

    /**
     * Returns how many seconds must still elapse before another full login attempt should be
     * made, based on the timestamp of the last recorded attempt. Returns 0 if no prior attempt
     * is recorded or the minimum interval has already elapsed.
     */
    public long secondsUntilNextAttemptAllowed(int minIntervalSeconds) {
        return secondsToWait(load().lastAttemptTimestamp, Instant.now(), minIntervalSeconds);
    }

    /** Pure computation, package-private for unit testing without relying on wall-clock timing. */
    static long secondsToWait(Instant lastAttempt, Instant now, int minIntervalSeconds) {
        if (lastAttempt == null) return 0;
        long elapsed = Duration.between(lastAttempt, now).getSeconds();
        return Math.max(0, minIntervalSeconds - elapsed);
    }

    // -------------------------------------------------------------------------

    private LockoutState load() {
        if (!Files.exists(lockoutFile)) {
            return new LockoutState();
        }
        try {
            return mapper.readValue(lockoutFile.toFile(), LockoutState.class);
        } catch (IOException e) {
            log.warn("Could not read lockout file ({}), treating as clean state: {}", lockoutFile, e.getMessage());
            return new LockoutState();
        }
    }

    private void save(LockoutState state) {
        try {
            Files.createDirectories(lockoutFile.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(lockoutFile.toFile(), state);
        } catch (IOException e) {
            log.error("Failed to write lockout file {}: {}", lockoutFile, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LockoutState {
        public int consecutiveFailures = 0;
        public Instant lastFailureTimestamp = null;
        public Instant lastAttemptTimestamp = null;
    }

    public static class LockoutException extends RuntimeException {
        public LockoutException(String message) { super(message); }
    }
}
