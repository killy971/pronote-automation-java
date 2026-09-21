package com.pronote.safety;

import com.pronote.config.ManualEntryLoader;
import com.pronote.persistence.Identifiable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Refuses to let a run whose every Pronote request came back empty be mistaken for a run where
 * the school deleted everything.
 *
 * <p>A broken session does not fail loudly: the server keeps answering 200 and the response
 * decrypts to {@code {}}, so each scraper logs one WARN and returns an empty list. Downstream
 * nothing can tell that apart from a real emptying — the diff reports the whole snapshot as
 * removed, the notification announces a hundred deletions, and the empty snapshot is persisted,
 * so the next healthy run announces them all back as additions.
 *
 * <p>The rule is deliberately narrow, because a data type genuinely emptying is not impossible:
 * during the holidays the timetable and the assignments really do return nothing for the whole
 * fetch window. It fires only when <em>no</em> enabled type returned anything at all while at
 * least one of them held Pronote data a moment ago — the signature of a dead session, not of a
 * quiet fortnight. Grades, evaluations and school-life events stay non-empty over the holidays
 * and keep the run healthy.
 *
 * <p>Items whose ID carries {@link ManualEntryLoader#ID_PREFIX} are ignored on both sides: they
 * come from {@code manual-entries.yaml}, not from Pronote, so they say nothing about whether the
 * session is alive. Feed this the lists as the scrapers returned them, before the manual merge.
 */
public final class EmptyFetchGuard {

    private final List<String> emptied = new ArrayList<>();
    private boolean anyFetched;

    /**
     * Records one enabled data type. Call it only for types this run actually fetched — a
     * disabled feature says nothing about the session.
     *
     * @param label    human-readable type name, used in the failure message
     * @param fetched  what the scraper returned this run, before manual entries are merged in
     * @param previous the last snapshot of this type, or empty on a first run
     */
    public EmptyFetchGuard observe(String label,
                                   Collection<? extends Identifiable> fetched,
                                   Optional<? extends Collection<? extends Identifiable>> previous) {
        int fetchedCount = countPronote(fetched);
        if (fetchedCount > 0) {
            anyFetched = true;
            return this;
        }
        int previousCount = previous.isPresent() ? countPronote(previous.get()) : 0;
        if (previousCount > 0) {
            emptied.add(label + " (" + previousCount + " → 0)");
        }
        return this;
    }

    /**
     * @throws EmptyFetchException when every observed type came back empty and at least one of
     *                             them held Pronote data before
     */
    public void verify() {
        if (anyFetched || emptied.isEmpty()) {
            return;
        }
        throw new EmptyFetchException(
                "Every Pronote request returned no data while the last snapshot still had some: "
                        + String.join(", ", emptied)
                        + ". Treating this as a failed session rather than a mass deletion — "
                        + "snapshots and notifications are left untouched.");
    }

    private static int countPronote(Collection<? extends Identifiable> items) {
        if (items == null) {
            return 0;
        }
        return (int) items.stream()
                .filter(i -> i.getId() == null || !i.getId().startsWith(ManualEntryLoader.ID_PREFIX))
                .count();
    }

    /** Names the types that emptied, for logging. Package-private for tests. */
    List<String> emptiedTypes() {
        return List.copyOf(emptied);
    }

    /** Raised when a whole run came back empty. */
    public static class EmptyFetchException extends RuntimeException {
        public EmptyFetchException(String message) {
            super(message);
        }
    }
}
