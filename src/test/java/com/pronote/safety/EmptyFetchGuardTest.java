package com.pronote.safety;

import com.pronote.config.ManualEntryLoader;
import com.pronote.persistence.Identifiable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins when a run is refused as a dead session versus accepted as a genuinely quiet week.
 *
 * <p>Synthetic items only — the guard reads nothing but IDs and list sizes.
 */
class EmptyFetchGuardTest {

    /** Minimal {@link Identifiable} stand-in; the guard never looks at anything else. */
    private record Item(String id) implements Identifiable {
        @Override
        public String getId() {
            return id;
        }
    }

    private static List<Identifiable> items(int count, String prefix) {
        return java.util.stream.IntStream.range(0, count)
                .<Identifiable>mapToObj(i -> new Item(prefix + i))
                .toList();
    }

    private static List<Identifiable> pronote(int count) {
        return items(count, "SYN_");
    }

    private static List<Identifiable> manual(int count) {
        return items(count, ManualEntryLoader.ID_PREFIX + "SYN_");
    }

    @Test
    void everythingEmptyAfterAFullSnapshot_isRefused() {
        // The 2026-09-17 and 2026-09-19 runs: login "succeeded" without a session key, so every
        // response decrypted to {} and every scraper returned nothing.
        EmptyFetchGuard guard = new EmptyFetchGuard()
                .observe("devoirs", List.of(), Optional.of(pronote(7)))
                .observe("emploi du temps", List.of(), Optional.of(pronote(102)))
                .observe("notes", List.of(), Optional.of(pronote(3)));

        EmptyFetchGuard.EmptyFetchException e =
                assertThrows(EmptyFetchGuard.EmptyFetchException.class, guard::verify);
        assertTrue(e.getMessage().contains("emploi du temps (102 → 0)"), e.getMessage());
        assertEquals(3, guard.emptiedTypes().size());
    }

    @Test
    void oneTypeStillReturningData_keepsTheRun() {
        // The holidays: the timetable and the homework really are empty for the whole fetch
        // window, but grades and evaluations still come back, so the session is plainly alive.
        EmptyFetchGuard guard = new EmptyFetchGuard()
                .observe("devoirs", List.of(), Optional.of(pronote(7)))
                .observe("emploi du temps", List.of(), Optional.of(pronote(102)))
                .observe("notes", pronote(12), Optional.of(pronote(12)));

        assertDoesNotThrow(guard::verify);
    }

    @Test
    void firstRun_hasNothingToLose() {
        EmptyFetchGuard guard = new EmptyFetchGuard()
                .observe("devoirs", List.of(), Optional.empty())
                .observe("emploi du temps", List.of(), Optional.empty());

        assertDoesNotThrow(guard::verify);
        assertTrue(guard.emptiedTypes().isEmpty());
    }

    @Test
    void typeThatWasAlreadyEmpty_isNotSuspicious() {
        // No school-life events all term is normal, and says nothing about the session.
        EmptyFetchGuard guard = new EmptyFetchGuard()
                .observe("vie scolaire", List.of(), Optional.of(List.of()));

        assertDoesNotThrow(guard::verify);
    }

    @Test
    void manualEntriesCountOnNeitherSide() {
        // Manual entries come from the YAML, not from Pronote: they neither prove the session is
        // alive when fetched back, nor count as data lost when the snapshot still holds them.
        EmptyFetchGuard alive = new EmptyFetchGuard()
                .observe("devoirs", manual(10), Optional.of(pronote(7)))
                .observe("emploi du temps", List.of(), Optional.of(pronote(102)));
        assertThrows(EmptyFetchGuard.EmptyFetchException.class, alive::verify);

        EmptyFetchGuard nothingLost = new EmptyFetchGuard()
                .observe("devoirs", List.of(), Optional.of(manual(10)));
        assertDoesNotThrow(nothingLost::verify);
    }

    @Test
    void disabledTypesAreSimplyNotObserved() {
        // Main only calls observe() for enabled features; a guard that saw nothing passes.
        assertDoesNotThrow(new EmptyFetchGuard()::verify);
    }
}
