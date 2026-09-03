package com.pronote.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {

    private static PronoteSession syntheticSession() {
        PronoteSession session = new PronoteSession();
        session.setSessionHandle(1234567);
        session.setAppId(2);
        session.setBaseUrl("https://example.invalid/pronote/");
        session.setOrderCounter(41);
        return session;
    }

    // ---- Round trip ---------------------------------------------------------

    @Test
    void save_thenLoad_preservesTheAdvancedOrderCounter(@TempDir Path dataDir) {
        SessionStore store = new SessionStore(dataDir);
        store.save(syntheticSession());

        Optional<PronoteSession> loaded = store.load();
        assertTrue(loaded.isPresent());
        // The counter the run advanced to must survive: the server has already consumed every
        // lower value, so a session restored with a stale counter is rejected outright.
        assertEquals(41, loaded.get().getOrderCounter());
    }

    @Test
    void save_stampsLastUsedAt(@TempDir Path dataDir) {
        PronoteSession session = syntheticSession();
        assertNull(session.getLastUsedAt());

        Instant before = Instant.now();
        new SessionStore(dataDir).save(session);

        PronoteSession loaded = new SessionStore(dataDir).load().orElseThrow();
        assertNotNull(loaded.getLastUsedAt());
        assertFalse(loaded.getLastUsedAt().isBefore(before.minusSeconds(1)));
    }

    @Test
    void load_returnsEmpty_whenNoFileExists(@TempDir Path dataDir) {
        assertTrue(new SessionStore(dataDir).load().isEmpty());
    }

    @Test
    void load_returnsEmpty_onCorruptFile(@TempDir Path dataDir) throws Exception {
        Files.writeString(dataDir.resolve("session.json"), "{not valid json");
        assertTrue(new SessionStore(dataDir).load().isEmpty());
    }

    @Test
    void delete_removesTheFile(@TempDir Path dataDir) {
        SessionStore store = new SessionStore(dataDir);
        store.save(syntheticSession());
        store.delete();
        assertFalse(Files.exists(dataDir.resolve("session.json")));
    }

    // ---- Probe age gate (backlog #7) ---------------------------------------

    @Test
    void shouldProbe_isTrue_forAFreshSession() {
        PronoteSession session = syntheticSession();
        Instant now = Instant.parse("2030-05-06T10:00:00Z");
        session.setLastUsedAt(now.minusSeconds(30));

        assertTrue(SessionStore.shouldProbe(session, now, 120));
    }

    @Test
    void shouldProbe_isTrue_exactlyAtTheLimit() {
        PronoteSession session = syntheticSession();
        Instant now = Instant.parse("2030-05-06T10:00:00Z");
        session.setLastUsedAt(now.minusSeconds(120));

        assertTrue(SessionStore.shouldProbe(session, now, 120));
    }

    @Test
    void shouldProbe_isFalse_pastTheLimit() {
        PronoteSession session = syntheticSession();
        Instant now = Instant.parse("2030-05-06T10:00:00Z");
        session.setLastUsedAt(now.minusSeconds(121));

        // This is the every-run case at a 15- or 30-minute cadence: Pronote has already dropped
        // the session, so probing it only costs a request and a rate-limiter wait.
        assertFalse(SessionStore.shouldProbe(session, now, 120));
    }

    @Test
    void shouldProbe_isFalse_whenProbingIsDisabled() {
        PronoteSession session = syntheticSession();
        Instant now = Instant.parse("2030-05-06T10:00:00Z");
        session.setLastUsedAt(now.minusSeconds(1));

        assertFalse(SessionStore.shouldProbe(session, now, 0));
        assertFalse(SessionStore.shouldProbe(session, now, -1));
    }

    @Test
    void secondsSinceLastUse_fallsBackToCreatedAt_forSessionsWrittenBeforeLastUsedAt() {
        PronoteSession session = syntheticSession();
        Instant now = Instant.parse("2030-05-06T10:00:00Z");
        session.setCreatedAt(now.minusSeconds(45));
        session.setLastUsedAt(null);

        assertEquals(45, session.secondsSinceLastUse(now));
        assertTrue(SessionStore.shouldProbe(session, now, 120));
    }

    @Test
    void secondsSinceLastUse_isMaximal_whenBothTimestampsAreMissing() {
        PronoteSession session = syntheticSession();
        session.setCreatedAt(null);
        session.setLastUsedAt(null);

        // A session with no timestamps must never look fresh.
        assertEquals(Long.MAX_VALUE, session.secondsSinceLastUse(Instant.now()));
        assertFalse(SessionStore.shouldProbe(session, Instant.now(), 120));
    }

    @Test
    void secondsSinceLastUse_isZero_forAClockSkewedFutureTimestamp() {
        PronoteSession session = syntheticSession();
        Instant now = Instant.parse("2030-05-06T10:00:00Z");
        session.setLastUsedAt(now.plusSeconds(600));

        assertEquals(0, session.secondsSinceLastUse(now));
    }
}
