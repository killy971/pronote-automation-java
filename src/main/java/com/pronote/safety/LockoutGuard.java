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
 * <p>A lockout expires on its own once {@code cooldownMinutes} have elapsed since the last
 * failure, so a transient outage (Pronote maintenance, a DNS blip) self-heals while a genuinely
 * bad password still backs off to a handful of attempts per day. Deleting or editing
 * lockout.json unlocks immediately.
 *
 * <p>Each lockout episode is announced once: {@link LockoutException#isAlertPending()} is true
 * only until the caller confirms delivery via {@link #markLockoutAlerted()}. Without that, a job
 * running 27x/day would either alert 27 times or — as it did before — stop entirely silently.
 */
public class LockoutGuard {

    private static final Logger log = LoggerFactory.getLogger(LockoutGuard.class);
    private static final String FILENAME = "lockout.json";

    /** Cooldown applied when the caller does not specify one. */
    static final int DEFAULT_COOLDOWN_MINUTES = 360;

    private final Path lockoutFile;
    private final int maxFailures;
    private final int cooldownMinutes;
    private final ObjectMapper mapper;

    public LockoutGuard(Path dataDir, int maxFailures) {
        this(dataDir, maxFailures, DEFAULT_COOLDOWN_MINUTES);
    }

    /**
     * @param cooldownMinutes minutes after the last failure at which the lockout clears itself;
     *                        {@code <= 0} disables auto-clearing (manual reset only)
     */
    public LockoutGuard(Path dataDir, int maxFailures, int cooldownMinutes) {
        this.lockoutFile = dataDir.resolve(FILENAME);
        this.maxFailures = maxFailures;
        this.cooldownMinutes = cooldownMinutes;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Throws {@link LockoutException} if the consecutive failure count meets or exceeds the limit
     * and the cooldown has not yet expired. An expired lockout is cleared here, so the next login
     * proceeds normally.
     */
    public void checkAndThrowIfLocked() {
        LockoutState state = load();
        Instant now = Instant.now();

        if (state.consecutiveFailures >= maxFailures
                && isCooldownElapsed(state.lastFailureTimestamp, now, cooldownMinutes)) {
            log.warn("Lockout cooldown of {} min elapsed since {} — clearing {} recorded failures "
                     + "and allowing a fresh login attempt.",
                    cooldownMinutes, state.lastFailureTimestamp, state.consecutiveFailures);
            clearFailures(state);
            save(state);
        }

        if (state.consecutiveFailures >= maxFailures) {
            throw new LockoutException(lockoutMessage(state, now), state.lockoutAlertedAt == null);
        }
        if (state.consecutiveFailures > 0) {
            log.warn("Previous login failures recorded: {} (limit: {})",
                    state.consecutiveFailures, maxFailures);
        }
    }

    /**
     * Records that the alert for the current lockout episode was delivered, so the remaining runs
     * of the episode stay quiet. Called by the caller <em>after</em> a successful send: a failed
     * delivery leaves the alert pending and is retried on the next run.
     */
    public void markLockoutAlerted() {
        LockoutState state = load();
        state.lockoutAlertedAt = Instant.now();
        save(state);
    }

    private String lockoutMessage(LockoutState state, Instant now) {
        String head = "Locked out after " + state.consecutiveFailures + " consecutive login failures "
                + "(last failure: " + state.lastFailureTimestamp + "). ";
        if (cooldownMinutes > 0 && state.lastFailureTimestamp != null) {
            long remaining = Math.max(0,
                    Duration.between(now, state.lastFailureTimestamp.plus(Duration.ofMinutes(cooldownMinutes)))
                            .toMinutes());
            return head + "Retrying automatically in ~" + remaining + " min, "
                    + "or delete " + lockoutFile + " to unlock now.";
        }
        return head + "Delete or reset " + lockoutFile + " to unlock.";
    }

    /**
     * Pure computation, package-private for unit testing without relying on wall-clock timing.
     * A {@code null} timestamp never ages out: it is not evidence that time has passed (it means
     * the file was hand-edited or written by an older version), so manual reset stays required.
     */
    static boolean isCooldownElapsed(Instant lastFailure, Instant now, int cooldownMinutes) {
        if (cooldownMinutes <= 0 || lastFailure == null) return false;
        return !Duration.between(lastFailure, now).minusMinutes(cooldownMinutes).isNegative();
    }

    /** Records a successful authentication, resetting the failure counter. */
    public void recordSuccess() {
        LockoutState state = load();
        clearFailures(state);
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

    /** Resets the failure counter and the once-per-episode alert marker together. */
    private static void clearFailures(LockoutState state) {
        state.consecutiveFailures = 0;
        state.lastFailureTimestamp = null;
        state.lockoutAlertedAt = null;
    }

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
        /** When the alert for the current lockout episode was delivered; null while still pending. */
        public Instant lockoutAlertedAt = null;
    }

    public static class LockoutException extends RuntimeException {
        private final boolean alertPending;

        public LockoutException(String message) { this(message, false); }

        public LockoutException(String message, boolean alertPending) {
            super(message);
            this.alertPending = alertPending;
        }

        /** True when this lockout episode has not been announced yet. */
        public boolean isAlertPending() { return alertPending; }
    }
}
