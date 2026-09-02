# Backlog

Deferred work, ordered roughly by usefulness. These are things the codebase is already shaped to accept — not speculative redesigns.

---

### 1. Mark scraped (Pronote) assignments as `done`

The strikethrough/badge UI in `AssignmentHtmlGenerator` and the day-card "N devoirs" filter in `TimetableViewRenderer` already react to `Assignment.isDone()`. Today only `manual:` entries can set it (via the YAML's `done: true`); Pronote-fetched assignments cannot.

**Sketch**: introduce an `overrides.yaml` (or extend `manual-entries.yaml` with an `overrides:` block) keyed by Pronote assignment ID:
```yaml
overrides:
  - assignmentId: "12345#abcdef"
    done: true
    hidden: false
    note: "fait en avance"
```
Apply overrides after `AssignmentScraper` returns, before snapshot + diff. The override list itself is snapshot-independent and shouldn't affect diff churn (apply post-diff so an override flip doesn't generate a "modified" notification).

---

### 2. Surface `memo` on upcoming-eval cards in the assignment view

`TimetableEntry.memo` is populated by `TimetableScraper` but only the `lessonLabel` reaches the eval banner/cards in `AssignmentHtmlGenerator`. The teacher's free-text memo is usually the substantive part ("apporter la calculatrice", "réviser chap. 4–6"). Trivial: include `memo` (truncated) in `renderEvalBanner` and `renderDateGroup` when present.

---

### ~~3. "Nouveau" tag on assignments newly assigned in the last 2 days~~ ✓ Done

Implemented: `AssignmentHtmlGenerator` emits a `badge--new` ("Nouveau") span when `assignedDate >= today.minusDays(newBadgeDays)` and `!done`. Threshold is configurable via `assignmentView.newBadgeDays` in config.yaml (default: 2, set to 0 to disable).

---

### 4. Cross-link the two "evaluation" worlds

From a timetable day-view eval badge / assignment-view eval card, link to the corresponding `CompetenceEvaluation` once it appears in the `evaluations/` snapshot. Match on same date + same subject + similar `name`/`lessonLabel`. Bidirectional: bilan-view cards can also link back to the timetable date page.

---

### 5. Weekly summary view

`AssignmentHtmlGenerator` recently gained weekly separators. Natural next surface: a `weekly.html` per upcoming week showing totals (devoirs count, eval count, cancellations), per-subject breakdown, all evals — the Sunday-evening overview. Lives in a new `WeeklySummaryHtmlGenerator` + renderer; consumes the existing assignment + timetable snapshots.

---

### 6. Trimester selector on the subject-average panel

`EvaluationSummaryHtmlGenerator` currently shows a subject-average badge that aggregates across all periods. Add a pure-CSS `:target`-based or `<details>`-driven T1/T2/T3 selector that filters the computed average to one period — no JS, no extra dependencies. The `CompetenceEvaluation.periodName` data is already available.

---

### 7. Stop paying for a session probe that can never succeed

Every run logs `Session probe failed — performing fresh login`, so `session.json` reuse never
works and each run costs a full login. Cron runs 27x/day, making login the most-repeated
sensitive operation in the system.

**This is not a bug in our session handling.** Pronote sessions have a very short server-side
idle timeout. Pronote's own `eleve.js` pings every **2 minutes**, and pronotepy mirrors it
(`_KeepAlive` in `pronoteAPI.py` posts `Navigation` with `{"onglet": 7, "ongletPrec": 7}` after
110s of inactivity). At a 30-minute cadence the session is always long dead — which also
explains the iPhone app re-authenticating after a few minutes idle.

**Keeping the session alive is the wrong trade.** A ping every ~2 minutes is ~720 requests/day
versus 27 logins/day, and it would require a resident daemon — the app is deliberately
short-lived and cron-driven. More traffic, more bot-like, worse.

**Sketch (the actual win)**: the probe is a wasted HTTP round trip plus a `RateLimiter.await()`
on every single run, and it always fails. Skip it when `session.json`'s `createdAt` is older
than a short TTL (~3 min) and go straight to login. Saves one request per run and removes the
misleading log line. Keep the probe for the rare back-to-back invocation where reuse can work.

Only revisit keep-alive if the cadence ever drops below ~5 minutes, where the arithmetic flips.

---

### ~~8. A lockout stops the job silently and permanently~~ ✓ Done

Fixed on three fronts:

- **It is announced.** `runFetch` now builds the error notifier *before* the lockout check and
  catches `LockoutException` to `sendErrorAlert(..., "verrouillage", ...)`. Previously the
  exception unwound straight to `Main.main`'s catch, which only logs and exits 1 — no ntfy alert,
  unlike every other pipeline phase — so the sync stopped silently and the first symptom was
  stale views.
- **Announced once per episode, not 27x/day.** `LockoutState.lockoutAlertedAt` gates it.
  `LockoutException.isAlertPending()` stays true until the caller confirms delivery with
  `markLockoutAlerted()`, so a failed send is retried on the next run rather than consuming the
  episode's only alert. `recordSuccess()` and the cooldown clear re-arm it.
- **It expires.** `safety.lockoutCooldownMinutes` (default 360) auto-clears the lockout once that
  long has passed since the last failure, so a Pronote maintenance window or a DNS blip self-heals.
  A genuinely bad password still backs off to ~4 attempts/day instead of 27. `0` restores the old
  manual-reset-only behaviour. A `null` `lastFailureTimestamp` never ages out — it is not evidence
  that time has passed — so a hand-edited file still requires a manual reset.

`safety.maxLoginFailures` in the deployed config was also raised from 2 to 3 (the committed
default was already 3): the threshold of 2 was chosen when runs were manual and rare.

Verified end to end against a stub ntfy server: alert delivered on the first locked run, silent on
the second, retried when delivery fails, and a 7-hour-old lockout clears itself and proceeds to a
login attempt.

---

### General docs hygiene

`manual-entries.yaml.example` is committed to a public repo, so its teacher names are now placeholders (`SYN_TEACHER_*`); the real subject/teacher mapping lives in the gitignored `config.yaml`. Both go stale as teachers change — re-verify the raw subject strings and the example `periodName` values each school year, and remember that enrichment matching is exact and case-sensitive, so a near-miss fails silently.
