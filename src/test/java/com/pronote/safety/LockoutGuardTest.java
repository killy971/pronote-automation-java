package com.pronote.safety;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class LockoutGuardTest {

    @Test
    void checkAndThrowIfLocked_doesNothing_whenNoStateFile(@TempDir Path dataDir) {
        LockoutGuard guard = new LockoutGuard(dataDir, 3);
        assertDoesNotThrow(guard::checkAndThrowIfLocked);
    }

    @Test
    void recordFailure_persistsCounterAcrossInstances(@TempDir Path dataDir) {
        new LockoutGuard(dataDir, 3).recordFailure();
        new LockoutGuard(dataDir, 3).recordFailure();

        LockoutGuard fresh = new LockoutGuard(dataDir, 3);
        // 2 < 3 -> still allowed
        assertDoesNotThrow(fresh::checkAndThrowIfLocked);

        fresh.recordFailure();
        // 3 >= 3 -> locked
        LockoutGuard.LockoutException ex = assertThrows(
                LockoutGuard.LockoutException.class,
                () -> new LockoutGuard(dataDir, 3).checkAndThrowIfLocked());
        assertTrue(ex.getMessage().contains("3 consecutive"));
    }

    @Test
    void recordSuccess_resetsCounter(@TempDir Path dataDir) {
        LockoutGuard guard = new LockoutGuard(dataDir, 3);
        guard.recordFailure();
        guard.recordFailure();
        guard.recordSuccess();

        // After success, even one more failure shouldn't trigger lockout
        guard.recordFailure();
        assertDoesNotThrow(guard::checkAndThrowIfLocked);
    }

    @Test
    void checkAndThrowIfLocked_throws_whenAtThreshold(@TempDir Path dataDir) {
        LockoutGuard guard = new LockoutGuard(dataDir, 2);
        guard.recordFailure();
        guard.recordFailure();

        assertThrows(LockoutGuard.LockoutException.class, guard::checkAndThrowIfLocked);
    }

    @Test
    void corruptStateFile_isTreatedAsCleanState(@TempDir Path dataDir) throws Exception {
        Path stateFile = dataDir.resolve("lockout.json");
        Files.writeString(stateFile, "{not valid json");

        LockoutGuard guard = new LockoutGuard(dataDir, 3);
        assertDoesNotThrow(guard::checkAndThrowIfLocked);
    }

    @Test
    void recordFailure_createsParentDirectories(@TempDir Path parent) {
        Path dataDir = parent.resolve("nested/data");
        LockoutGuard guard = new LockoutGuard(dataDir, 3);
        // No state file or directory exists yet
        guard.recordFailure();
        assertTrue(Files.exists(dataDir.resolve("lockout.json")));
    }

    @Test
    void secondsToWait_returnsZero_whenNoPriorAttempt() {
        assertEquals(0, LockoutGuard.secondsToWait(null, Instant.now(), 30));
    }

    @Test
    void secondsToWait_returnsRemainder_whenWithinInterval() {
        Instant last = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = last.plusSeconds(10);
        assertEquals(20, LockoutGuard.secondsToWait(last, now, 30));
    }

    @Test
    void secondsToWait_returnsZero_whenIntervalAlreadyElapsed() {
        Instant last = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = last.plusSeconds(45);
        assertEquals(0, LockoutGuard.secondsToWait(last, now, 30));
    }

    @Test
    void secondsUntilNextAttemptAllowed_persistsAcrossInstances(@TempDir Path dataDir) {
        new LockoutGuard(dataDir, 3).recordAttempt();

        LockoutGuard fresh = new LockoutGuard(dataDir, 3);
        // An attempt was just recorded, so a 30s cooldown must still be pending.
        assertTrue(fresh.secondsUntilNextAttemptAllowed(30) > 0);
    }

    @Test
    void recordSuccess_preservesLastAttemptTimestamp(@TempDir Path dataDir) {
        LockoutGuard guard = new LockoutGuard(dataDir, 3);
        guard.recordAttempt();
        guard.recordSuccess();

        // recordSuccess must not wipe the attempt timestamp recordAttempt() just set —
        // otherwise the login cooldown would never persist across a successful run.
        assertTrue(guard.secondsUntilNextAttemptAllowed(30) > 0);
    }

    // ---- Cooldown auto-clear (backlog #8) -----------------------------------

    @Test
    void isCooldownElapsed_isFalse_beforeCooldownExpires() {
        Instant lastFailure = Instant.parse("2026-01-01T00:00:00Z");
        assertFalse(LockoutGuard.isCooldownElapsed(lastFailure, lastFailure.plusSeconds(3599), 60));
    }

    @Test
    void isCooldownElapsed_isTrue_onceCooldownExpires() {
        Instant lastFailure = Instant.parse("2026-01-01T00:00:00Z");
        assertTrue(LockoutGuard.isCooldownElapsed(lastFailure, lastFailure.plusSeconds(3600), 60));
    }

    @Test
    void isCooldownElapsed_isFalse_whenCooldownDisabled() {
        Instant lastFailure = Instant.parse("2026-01-01T00:00:00Z");
        assertFalse(LockoutGuard.isCooldownElapsed(lastFailure, lastFailure.plusSeconds(999999), 0));
    }

    @Test
    void isCooldownElapsed_isFalse_whenTimestampMissing() {
        // A null timestamp is not evidence that time has passed (hand-edited or legacy file),
        // so the lockout must stay in place rather than silently clearing itself.
        assertFalse(LockoutGuard.isCooldownElapsed(null, Instant.now(), 60));
    }

    @Test
    void expiredLockout_clearsItselfAndAllowsRetry(@TempDir Path dataDir) throws Exception {
        writeState(dataDir, 3, Instant.now().minus(Duration.ofHours(7)));

        LockoutGuard guard = new LockoutGuard(dataDir, 3, 360);
        assertDoesNotThrow(guard::checkAndThrowIfLocked);

        // The cleared counter must be persisted, not just skipped in memory.
        assertDoesNotThrow(() -> new LockoutGuard(dataDir, 3, 360).checkAndThrowIfLocked());
    }

    @Test
    void unexpiredLockout_stillThrows(@TempDir Path dataDir) throws Exception {
        writeState(dataDir, 3, Instant.now().minus(Duration.ofMinutes(5)));

        LockoutGuard.LockoutException ex = assertThrows(
                LockoutGuard.LockoutException.class,
                () -> new LockoutGuard(dataDir, 3, 360).checkAndThrowIfLocked());
        assertTrue(ex.getMessage().contains("Retrying automatically"));
    }

    @Test
    void cooldownOfZero_keepsLockoutUntilManualReset(@TempDir Path dataDir) throws Exception {
        writeState(dataDir, 3, Instant.now().minus(Duration.ofDays(30)));

        LockoutGuard.LockoutException ex = assertThrows(
                LockoutGuard.LockoutException.class,
                () -> new LockoutGuard(dataDir, 3, 0).checkAndThrowIfLocked());
        assertTrue(ex.getMessage().contains("to unlock"));
    }

    // ---- Alert once per lockout episode (backlog #8) ------------------------

    @Test
    void firstLockedRun_reportsAlertPending(@TempDir Path dataDir) {
        LockoutGuard guard = new LockoutGuard(dataDir, 2, 360);
        guard.recordFailure();
        guard.recordFailure();

        LockoutGuard.LockoutException ex = assertThrows(
                LockoutGuard.LockoutException.class, guard::checkAndThrowIfLocked);
        assertTrue(ex.isAlertPending());
    }

    @Test
    void alertStaysPending_untilDeliveryIsConfirmed(@TempDir Path dataDir) {
        LockoutGuard guard = new LockoutGuard(dataDir, 2, 360);
        guard.recordFailure();
        guard.recordFailure();

        // A failed delivery must not consume the episode's single alert.
        assertTrue(assertThrows(LockoutGuard.LockoutException.class,
                guard::checkAndThrowIfLocked).isAlertPending());
        assertTrue(assertThrows(LockoutGuard.LockoutException.class,
                guard::checkAndThrowIfLocked).isAlertPending());

        guard.markLockoutAlerted();

        // Cron runs 27x/day: once announced, the remaining runs stay quiet.
        assertFalse(assertThrows(LockoutGuard.LockoutException.class,
                () -> new LockoutGuard(dataDir, 2, 360).checkAndThrowIfLocked()).isAlertPending());
    }

    @Test
    void recordSuccess_rearmsTheAlertForTheNextEpisode(@TempDir Path dataDir) {
        LockoutGuard guard = new LockoutGuard(dataDir, 2, 360);
        guard.recordFailure();
        guard.recordFailure();
        guard.markLockoutAlerted();

        guard.recordSuccess();
        guard.recordFailure();
        guard.recordFailure();

        assertTrue(assertThrows(LockoutGuard.LockoutException.class,
                guard::checkAndThrowIfLocked).isAlertPending());
    }

    @Test
    void expiredLockout_rearmsTheAlertForTheNextEpisode(@TempDir Path dataDir) throws Exception {
        writeState(dataDir, 2, Instant.now().minus(Duration.ofHours(7)), Instant.now().minus(Duration.ofHours(7)));

        LockoutGuard guard = new LockoutGuard(dataDir, 2, 360);
        guard.checkAndThrowIfLocked();   // clears the expired lockout
        guard.recordFailure();
        guard.recordFailure();

        assertTrue(assertThrows(LockoutGuard.LockoutException.class,
                guard::checkAndThrowIfLocked).isAlertPending());
    }

    // -------------------------------------------------------------------------

    private static void writeState(Path dataDir, int failures, Instant lastFailure) throws Exception {
        writeState(dataDir, failures, lastFailure, null);
    }

    /** Writes lockout.json directly so the age of a lockout can be set without waiting for it. */
    private static void writeState(Path dataDir, int failures, Instant lastFailure, Instant alertedAt)
            throws Exception {
        Files.createDirectories(dataDir);
        StringBuilder json = new StringBuilder("{\n  \"consecutiveFailures\": ").append(failures)
                .append(",\n  \"lastFailureTimestamp\": ").append(epoch(lastFailure))
                .append(",\n  \"lockoutAlertedAt\": ").append(epoch(alertedAt))
                .append("\n}");
        Files.writeString(dataDir.resolve("lockout.json"), json.toString());
    }

    private static String epoch(Instant instant) {
        return instant == null ? "null" : String.valueOf(instant.toEpochMilli() / 1000.0);
    }
}
