package com.pronote.safety;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
}
