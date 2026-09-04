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

### ~~7. Stop paying for a session probe that can never succeed~~ ✓ Done

Every run logged `Session probe failed — performing fresh login`, so `session.json` reuse never
worked and each run cost a full login plus one wasted request and one `RateLimiter.await()`.

**This was never a bug in our session handling.** Pronote expires an idle session server-side
after about two minutes: its own `eleve.js` pings every 2 min, and pronotepy mirrors that
(`_KeepAlive` in `pronoteAPI.py` posts `Navigation` with `{"onglet": 7, "ongletPrec": 7}` after
110s idle). At a 30-minute cadence the stored session is always long dead — which also explains
the iPhone app re-authenticating after a few minutes idle.

**Keeping the session alive would have been the wrong trade.** A ping every ~2 min is ~720
requests/day versus 27 logins/day, and it needs a resident daemon; the app is deliberately
short-lived and cron-driven. Revisit only if the cadence ever drops below ~5 minutes.

Fixed by taking the actual win instead:

- `SessionStore.shouldProbe` gates the probe on `PronoteSession.secondsSinceLastUse` against the
  new `safety.sessionProbeMaxAgeSeconds` (default 120, matching Pronote's real idle timeout; `0`
  disables probing entirely). One less request per run, and the misleading log line is gone —
  it now reads `Stored session is 1854s old (probe limit: 120s) — logging in without probing.`
- The probe is kept for the back-to-back case, and **it now actually works.** Investigating this
  turned up a second reason reuse could never succeed: the session was persisted once, right
  after login, so the file kept its post-login order counter while the run went on consuming
  values (observed on a real run: 9 in the file, 29 by the end). Every counter value the file
  offered had already been spent, so the server would reject a restored session regardless of its
  age. `runFetch` now saves again after the last Pronote request (step 7b), which captures the
  advanced counter and stamps the new `lastUsedAt` field that the age check reads.
- `lastUsedAt` measures time since the session was last *used*, not since login. Sessions written
  before the field existed fall back to `createdAt`; a session with neither timestamp reports
  `Long.MAX_VALUE` so it can never look fresh.

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

### 9. `--features timetable` on its own returns zero lessons

`PageEmploiDuTemps` returns no `ListeCours` key at all — for every requested week — unless the
assignments fetch (`PageCahierDeTexte`, onglet 88) has already run in the same session. Confirmed
three times on 2026-09-03, with both a fresh login and a reused session; the same code path in a
full run fetches 132 entries.

Scheduled runs are unaffected because they enable all features, but `--mode fetch --features
timetable` silently writes an **empty** timetable snapshot, which is a data-loss shape: the diff
would report every lesson removed. `--features` exists precisely to make a narrow run cheap, so
this makes the flag unsafe for the type most worth fetching alone.

**Sketch**: find what the assignments call leaves behind — most likely Pronote wants the tab
"opened" first. pronotepy sends `Navigation` (`{"onglet": N, "ongletPrec": N}`) when switching
tabs; issuing that for onglet 16 before `PageEmploiDuTemps` is the first thing to try. Failing
that, refuse the combination rather than writing an empty snapshot.

---

### General docs hygiene

`manual-entries.yaml.example` is committed to a public repo, so its teacher names are now placeholders (`SYN_TEACHER_*`); the real subject/teacher mapping lives in the gitignored `config.yaml`. Both go stale as teachers change — re-verify the raw subject strings and the example `periodName` values each school year, and remember that enrichment matching is exact and case-sensitive, so a near-miss fails silently.
