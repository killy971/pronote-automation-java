# CLAUDE.md — Project Guidance for AI Sessions

## Terminology — "Evaluation" means two different things

**Do not confuse these two concepts. They are completely separate in the codebase.**

| Term | Java type | Source | Where it appears |
|---|---|---|---|
| **Upcoming competence evaluation** | `TimetableEntry` (`isEval=true`) | Timetable scraper (`PageEmploiDuTemps`) or `manual-entries.yaml` `evaluations:` block | Timetable day view (badge), timetable summary eval count, assignment view eval banner + cards |
| **Past competence evaluation result** | `CompetenceEvaluation` | `DernieresEvaluations` API | Bilan view (`evaluations/index.html`), evaluation summary view |

Manual entries in `manual-entries.yaml` under `evaluations:` are **upcoming timetable events**, converted to synthetic `TimetableEntry(isEval=true)` by `ManualEntryLoader`. They never produce `CompetenceEvaluation` objects and must never be merged into the evaluations/bilan pipeline.

---

## Project Overview

`pronote-automation-java` is a **short-lived, scheduled Java 21 CLI application** that:

1. Authenticates against a Pronote parent portal (no official API)
2. Fetches assignments and timetable via a custom encrypted JSON-RPC protocol
3. Diffs the results against the previous run
4. Sends push (ntfy) and/or email notifications when changes are detected
5. Persists snapshots to disk for the next comparison
6. Generates static HTML timetable views for the upcoming weekdays (self-contained, no JS)

Target runtime: **Raspberry Pi (Raspbian)**, triggered by two systemd timers — one frequent (every 15 min, all day) and one school-hours only.

**CLI arguments:**

| Argument | Required | Description |
|---|---|---|
| `--config <path>` | No (default: `config.yaml`) | Path to the YAML configuration file |
| `--features <list>` | No (default: config `features.*` flags) | Comma-separated subset of features to run: `assignments`, `timetable`, `grades`, `evaluations`, `schoolLife`. Overrides all `features.*` flags in config for this invocation. Unknown names fail fast. |
| `--mode <mode>` | No (default: `fetch`) | Run mode: `fetch` (full online pipeline), `views` (regenerate HTML from last snapshot, offline), `diff` (re-run diff between last two snapshots + re-notify, offline), `validate` (parse `manual-entries.yaml`, report what would be merged, no network/snapshot/notification side-effects). |
| `--dry-run` | No | In `fetch` and `diff` modes, log the notification payload to stdout instead of delivering it. Has no effect in `views`/`validate` mode. |

**Make targets:**

| Target | Description |
|---|---|
| `make run` | Full run: fetch from Pronote, diff, notify, save snapshots, generate views |
| `make views` | Offline: regenerate HTML views (all enabled types) from the last snapshots |
| `make diff` | Offline: re-run diff between archive[-1] and `latest.json`, re-notify if changes exist |
| `make notify-preview` | Offline: same as `make diff` with `--dry-run` (no notifications actually sent) |
| `make validate` | Offline: parse `manual-entries.yaml` and report what would be merged on next fetch |
| `make run-debug` | Same as `make run` with DEBUG logging |
| `make build` | Compile and package the fat JAR |
| `make test` | Run unit tests |

---

## Architecture Summary

```
Main (runFetch / runViews / runDiff / runValidate)
 ├── ConfigLoader              → loads config.yaml (SnakeYAML)
 ├── ManualEntryLoader         → loads manual-entries.yaml (assignments + upcoming evals)
 ├── SubjectEnricher           → resolves enrichedSubject from subject (+optional teacher)
 ├── LockoutGuard              → halts job after N consecutive login failures
 ├── SessionStore              → load/save session.json (persists AES keys + cookies)
 ├── PronoteAuthenticator      → full 10-step login flow
 ├── PronoteHttpClient         → AES-encrypted JSON-RPC calls + rate limiting + attachment download
 ├── Scrapers                  → AssignmentScraper, TimetableScraper, GradeScraper,
 │                               EvaluationScraper, SchoolLifeScraper
 ├── AttachmentDownloader      → idempotent download of G=1 attachments; respects rate limiting
 ├── SnapshotStore             → read/write latest.json + archive/, per data type
 ├── DiffEngine                → field-level comparison via Jackson tree model
 ├── TimetableDiffFilter       → suppresses past items + bulk normal additions in newly-discovered weeks
 ├── DiffReporter              → writes diff-latest.json + diff-history.log every run
 ├── CompositeNotifier         → fan-out to NtfyNotifier + EmailNotifier (per-channel failure non-fatal)
 ├── View renderers            → TimetableViewRenderer, AssignmentViewRenderer,
 │                               EvaluationViewRenderer, SchoolLifeViewRenderer,
 │                               PortalIndexHtmlGenerator
 └── GitPublisher              → optional: mirror view dirs into a GitHub Pages repo
```

All modules are **stateless except `PronoteSession`** (mutable AES key/IV/counter state).
`Main.java` is the only orchestration point — it wires everything together.

---

## Key Modules and Responsibilities

| Package | Class | Responsibility |
|---|---|---|
| `config` | `AppConfig` | POJO tree for config.yaml |
| `config` | `ConfigLoader` | Load + validate YAML; fail fast |
| `config` | `ManualEntryLoader` | Parse `manual-entries.yaml` into `Assignment` + synthetic `TimetableEntry(isEval=true)` lists. Stable IDs prefixed `manual:`; explicit `id:` field optional. |
| `config` | `SubjectEnricher` | Resolve `enrichedSubject` from raw subject (+optional teacher); two-pass rule eval, strips teacher prefixes ("M.", "Mme") |
| `safety` | `LockoutGuard` | Track login failures in `data/lockout.json` |
| `safety` | `RateLimiter` | Sleep `minDelay + random(jitter)` before each request |
| `auth` | `CryptoHelper` | AES-CBC, RSA-1024, MD5, SHA256, key derivation |
| `auth` | `PronoteSession` | Mutable: AES key/IV, cookies, order counter |
| `auth` | `PronoteAuthenticator` | Full login flow (see Authentication section below) |
| `auth` | `SessionStore` | Serialize/deserialize session to `data/session.json` |
| `client` | `PronoteHttpClient` | Encrypted POST: builds envelope, calls rate limiter, decrypts response; also exposes `download()` for rate-limited binary GETs |
| `client` | `ApiFunction` | Enum of known Pronote API function names (e.g. `PageEmploiDuTemps`) |
| `scraper` | `AssignmentScraper` | `ListeTravailAFaire` → `Assignment`; builds `AttachmentRef` list |
| `scraper` | `AttachmentDownloader` | Idempotent G=1 attachment download; resolves `localPath` from disk; respects rate limiter |
| `scraper` | `TimetableScraper` | `PageEmploiDuTemps` per-week → `TimetableEntry`; reads `estEval`/`estDevoir`/`originesCategorie` from `cahierDeTextes.V` |
| `scraper` | `GradeScraper`, `EvaluationScraper`, `SchoolLifeScraper` | Per-type scrapers, same pattern |
| `domain` | `Assignment`, `TimetableEntry`, `Grade`, `CompetenceEvaluation`, `SchoolLifeEvent` | Pure data, Jackson-serializable, implements `Identifiable` |
| `domain` | `AttachmentRef` | Attachment metadata: `stableId`, `fileName`, `uploadedFile`, `localPath`, `mimeType`; transient `downloadUrl` (never persisted) |
| `domain` | `CompetenceAcquisition` | Per-competence level result inside a `CompetenceEvaluation` (A+/A/C/E etc.) |
| `persistence` | `SnapshotStore` | Write `latest.json`, archive old, purge expired; `loadLatest` / `loadPrevious` |
| `persistence` | `DiffEngine` | Generic field-level diff via `jackson.valueToTree()`; registers `AttachmentRefDiffMixin` to exclude runtime fields from comparison |
| `persistence` | `TimetableDiffFilter` | Post-diff suppression: drop past items, drop bulk normal additions in newly-discovered furthest week. `isEval=true` entries always kept (user needs lead time). |
| `persistence` | `DiffReporter` | Writes `data/diff-latest.json` + appends to `data/diff-history.log` every run, regardless of notifications |
| `notification` | `NtfyNotifier` | HTTP POST to ntfy topic |
| `notification` | `EmailNotifier` | Jakarta Mail SMTP + STARTTLS |
| `notification` | `CompositeNotifier` | Fan-out; per-channel failure is logged, not fatal |
| `views` | `TimetableHtmlGenerator` / `TimetableViewRenderer` | Per-weekday HTML5 day page + `index.html` overview + `current.html` (today or next non-empty weekday) |
| `views` | `AssignmentHtmlGenerator` / `AssignmentViewRenderer` | Single-page assignment list grouped by date, weekly separators, eval cards merged into matching subject group |
| `views` | `EvaluationHtmlGenerator` / `EvaluationSummaryHtmlGenerator` / `EvaluationViewRenderer` | Bilan view (per-eval cards) + summary view (per-subject grouping with subject-average badge) |
| `views` | `SchoolLifeHtmlGenerator` / `SchoolLifeViewRenderer` | Absences / delays / observations list |
| `views` | `PortalIndexHtmlGenerator` | `data/views/index.html` — card grid linking to the per-type views |
| `views` | `GitPublisher` | Optional: copy view dirs into a sibling git repo and push (GitHub Pages) |

---

## Authentication Flow (High-Level)

The Pronote protocol is a **custom AES-encrypted JSON-RPC** over HTTPS.
Reference implementation: [pronotepy](https://github.com/bain3/pronotepy).

```
1. GET parent.html          → parse session handle (h), app id (a) from embedded JS
2. Generate random ivTemp   → RSA-1024 encrypt it → base64 "Uuid"
3. POST FonctionParametres  → send Uuid; receive AES-encrypted server challenge
   (at this point: key = MD5(""), iv = 16 null bytes)
4. Derive session IV        → MD5(ivTemp)
5. Decrypt challenge        → extract "alea" (server random padding)
6. Derive post-login key    → MD5(username + SHA256(alea + password))
7. POST Authentification    → send credentials hash; receive session confirmation
8. All subsequent calls     → POST /appelfonction/{a}/{h}/{order}
                               order counter starts at 1, +2 per request
                               body: { session, no: AES(order), id: funcName, dataSec: AES(params) }
```

**Session reuse**: On startup, the app tries to reload `session.json` and probe with a
cheap call. If the probe succeeds, no login is performed.

---

## Important Constraints

### Rate Limiting (DO NOT REMOVE)
- `RateLimiter.await()` is called before **every** outbound HTTP request in `PronoteHttpClient`.
- Default: 2000ms minimum + up to 500ms random jitter.
- Do **not** bypass this in new scraper calls.

### Login Safety
- `LockoutGuard` halts the job after 3 consecutive login failures.
- Login is retried **zero times** — one attempt per job run.
- Never add retry loops around `PronoteAuthenticator.login()`.

### Session Reuse
- Always attempt session reuse before login.
- `SessionStore` saves AES key material — set file permissions to 600 on POSIX systems.

### ENT/SSO Detection
- `PronoteAuthenticator` detects HTTP 302 redirects to a non-Pronote domain.
- These indicate ENT/SSO authentication, which is **not supported** in this codebase.

---

## Coding Conventions

- **No Spring, no DI framework** — dependencies are wired manually in `Main.java`.
- **No checked exceptions propagated publicly** — each module defines its own `RuntimeException` subclass.
- **Jackson tree model** (`JsonNode`) is used for all Pronote JSON parsing — do not add POJO deserialization for Pronote responses (field names change between instances).
- **SnakeYAML** for config only — not for Pronote data.
- **Bouncy Castle** for all crypto — do not use `javax.crypto` for AES/RSA (compatibility issues with non-standard key parameters on some JREs).
- Logging: use **SLF4J** everywhere; never log passwords, AES keys, session tokens, or raw cookie values.
- Java records are **not used** for domain objects because Jackson requires mutable POJOs for SnapshotStore deserialization.

---

## Extending the Scraper

**Before implementing any new scraper, read [`docs/pronotepy-reference.md`](docs/pronotepy-reference.md) first.**
It contains the exact API function names, onglet numbers, param structures, and response field mappings for every known Pronote API.
For edge cases or newly discovered functions, verify against `vendor/pronotepy/pronotepy/clients.py` and `dataClasses.py` (local clone, gitignored).

### Adding a new data type (e.g., grades)

1. Find the Pronote API function name in pronotepy source (e.g., `"ListeNotesEleve"`).
2. Add it to `ApiFunction.java` enum.
3. Create a new domain class implementing `Identifiable` in `com.pronote.domain`.
4. Create a `XxxScraper.java` in `com.pronote.scraper` following the pattern of `AssignmentScraper`:
   - Build `ObjectNode params` and call `client.encryptedPost(session, ApiFunction.XXX, params)`
   - Map French JSON fields to the domain object
   - Log unmapped/unexpected structures at `WARN` level with the raw node
5. Add snapshot load/save + diff in `Main.java` following the existing assignment/timetable pattern.
6. Add the new type to `buildPayload()` in `Main.java`.

### Parent account scoping — two independent mechanisms

For parent accounts (`childId` is set on the session), Pronote has two separate scoping mechanisms and different API functions require different combinations:

| Mechanism | Where | Who handles it | When needed |
|---|---|---|---|
| `Signature.membre` | Inside `dataSec` | `PronoteHttpClient` automatically (any onglet call) | Always — all post-auth data calls |
| `ressource` param | Inside `data` params | The scraper explicitly | API-specific — check pronotepy |

**`ressource` in params** is needed by some APIs (confirmed: `PageEmploiDuTemps`) but not all (e.g. `PageCahierDeTexte` does not use it). When adding a new scraper, look at the pronotepy implementation of that specific function to determine whether to add:
```java
if (session.getChildId() != null) {
    params.set("ressource", jackson.createObjectNode()
            .put("N", session.getChildId())
            .put("G", 4));
}
```

### Request parameter format is API-specific — always verify from pronotepy

Do not assume a new API accepts `DateDebut`/`DateFin` or `NumeroSemaine` because another one does. Examples:
- `PageEmploiDuTemps` (onglet 16): `NumeroSemaine` + `DateDebut` + `DateFin` + `ressource`
- `PageCahierDeTexte` (onglet 88): `domaine` as `{"_T": 8, "V": "[weekFrom..weekTo]"}` using school-year week numbers — sending date fields silently returns `{}`

Always read the relevant function in pronotepy's `clients.py` or `pronoteAPI.py` before building params.

### File attachments in responses

Pronote responses can contain `ListePieceJointe.V[]` arrays where each item's `G` field indicates type:
- `G=0` (hyperlink): `url` field is the destination URL, stable across sessions. Stored in `AttachmentRef.url`; not downloaded locally.
- `G=1` (uploaded file): must construct an authenticated URL by AES-encrypting `{"N":"<id>","Actif":true}` with the session key/IV and appending to `{baseUrl}FichiersExternes/{hex}/{encoded-filename}?Session={h}`. This URL is **session-scoped** (changes every login). See `AssignmentScraper.buildFileUrl()`.

See `AssignmentScraper.buildFileUrl()` for the URL construction. Apply the same pattern to any scraper that returns file attachments.

#### Critical: the Pronote `N` field is NOT a stable attachment ID

The `N` field in `ListePieceJointe` items looks like `37#<token>` where the token after `#` is regenerated on every session. **Do not use `N` as a stable identifier or as a filename.** Use `assignmentId + "|" + fileName` for G=1 files instead (both are session-independent). The `AssignmentScraper` follows this convention in `AttachmentRef.stableId`.

#### Attachment pipeline design

`Assignment.attachments` is a `List<AttachmentRef>`. Each `AttachmentRef` carries:
- `stableId` — session-independent key (`assignmentId|fileName` for G=1, URL for G=0)
- `fileName` — original filename from the `L` field
- `uploadedFile` — true for G=1 (downloadable), false for G=0 (hyperlink only)
- `url` — for G=0 only; always null for G=1 in persisted snapshots
- `localPath` — absolute path after download; null if not yet downloaded (excluded from diff)
- `mimeType` — from Content-Type or `Files.probeContentType`; null if unknown (excluded from diff)
- `downloadUrl` — **transient, `@JsonIgnore`** — the session-scoped G=1 download URL; never persisted, never compared

`DiffEngine` registers `AttachmentRefDiffMixin` which excludes `localPath`, `mimeType`, and `url` from field comparison, so only semantic changes (`stableId`, `fileName`, `uploadedFile`) trigger diff events.

`AttachmentDownloader` is idempotent: it checks `Files.exists()` before every download. Files live at:
```
data/snapshots/assignments/attachments/<sanitized-assignmentId>/<sanitizedFileName>
```

### DO
- Always call through `PronoteHttpClient.encryptedPost()` — never bypass it.
- Log the raw JSON response at `DEBUG` level before mapping (helps diagnose Pronote field changes).
- Use `getString(node, "field", defaultValue)` pattern — never call `node.get(field).asText()` directly (NPE on missing fields).

### DON'T
- Don't add new direct HTTP calls that bypass `RateLimiter`.
- Don't hardcode Pronote field names as constants — they're documentation, not contracts.
- Don't add retry loops on scraper calls — one failure should propagate.

---

## Static HTML Views

After each successful fetch, `Main.java` regenerates the view files **unconditionally** (steps 10–14) regardless of whether a diff was detected — keeping the rendered output in sync with the latest snapshot.

### View types and config blocks

| Block | Renderer | Output (default) | Notes |
|---|---|---|---|
| `timetableView` | `TimetableViewRenderer` | `./data/views/timetable/` | One `YYYY-MM-DD.html` per weekday + `index.html` + `current.html` (today if classes are still ongoing within 90 min of last lesson, else next non-empty weekday) |
| `assignmentView` | `AssignmentViewRenderer` | `./data/views/assignments/` | Single page grouped by date with weekly separators; eval cards merge into matching subject group |
| `evaluationView` | `EvaluationViewRenderer` | `./data/views/evaluations/` | Bilan view (`index.html`) + per-subject summary (`summary.html`) with subject-average badge |
| `schoolLifeView` | `SchoolLifeViewRenderer` | `./data/views/school-life/` | Absences / delays / observations |
| (portal) | `PortalIndexHtmlGenerator` | `./data/views/index.html` | Card grid linking to whatever per-type views are enabled |

Common keys per block:

| Key | Default | Description |
|---|---|---|
| `enabled` | `true` | Set to `false` to skip generation of that view |
| `outputDirectory` | see above | Where HTML files are written |
| `daysAhead` (timetable only) | `5` | Number of upcoming **weekdays** to generate, starting from today |

### Output conventions

All files are self-contained (CSS embedded inline; minimal JS only for the eval-detail dialog). The timetable views are annotated with assignment counts and eval markers loaded from the assignments snapshot.

### CSS / styling

CSS lives inside each generator class as a static text-block `CSS` constant, embedded verbatim into every generated file.
- Light/dark mode: `prefers-color-scheme` media query with CSS custom properties — **no JS for theming**.
- Responsive: `max-width: 480px` centred container; same layout works on mobile and desktop.
- Subject accent colours: 12-colour palette; colour index = `abs(subject.hashCode()) % 12` — deterministic across runs and days.

### Modifying the views

To change layout or styling, edit the relevant generator's `CSS` constant or HTML-building methods. The `*ViewRenderer` classes only handle file I/O and date selection — they do not contain any HTML.

### GitHub Pages deployment

Two options:
1. Point each view's `outputDirectory` to a path under `docs/` and commit, then enable Pages from the `docs/` root in the repo settings.
2. Set `viewPublish.enabled: true` to mirror the configured view dirs into a sibling git repo and push — see `GitPublisher`. The portal `index.html` is published alongside.

---

## Manual entries (`manual-entries.yaml`)

Optional companion file for work and upcoming tests you know about but that teachers haven't published yet. Path configurable via `manualEntries.file` (default `./manual-entries.yaml`). Silently ignored when absent. See `manual-entries.yaml.example` for the full schema with the project's subject/teacher reference table.

Two blocks:
- `assignments:` → produces `Assignment` objects merged into the assignment snapshot.
- `evaluations:` → produces **synthetic `TimetableEntry(isEval=true)`** objects merged into the **timetable** snapshot. (See the terminology section at the top of this file — these are upcoming events, not past `CompetenceEvaluation` results.)

Key behaviours:
- **All manual IDs are prefixed `manual:`**. `runViews` strips entries whose ID starts with `manual:` from the snapshot before re-injecting fresh from YAML — so edits take effect without a full fetch. `ManualEntryLoaderTest` pins this invariant.
- **Stable IDs**: an optional `id:` field on either block produces `manual:<id>` directly; otherwise the fallback scheme is `manual:<subject>@<date>@<description-or-name>`. Use `id:` whenever you anticipate editing the description/name — without it, fixing a typo creates a "removed + new" notification pair.
- **Manual evals get their times resolved against the timetable** (`resolveManualEvalTimes` in `Main`): subject + teacher match (most specific), then subject-only fallback, then 08:00–09:00 placeholder.
- **Manual assignments with `teacher:` set skip the timetable-based teacher lookup** in `reEnrichAssignmentsWithTeacher`, so the user's explicit choice always wins.

To verify a YAML edit without doing a full run, use `make validate` (`--mode validate`). It parses, enriches, prints what would be merged, and warns when a subject string doesn't appear in the latest timetable snapshot (the most common typo).

---

## Notifications — what gets surfaced and how

| Source | Title token | Body marker | Notes |
|---|---|---|---|
| Cancelled timetable entry (`status=CANCELLED`, modified or added) | `✗ Subject reason · date` | `✗ ` prefix | Bumps priority to HIGH |
| Removed timetable entry | counted in `📅 N modif. EDT` | `- ` prefix | Bumps priority to HIGH |
| **Added upcoming eval** (`isEval=true`, added) | **`📝 Subject éval · date`** | **`📝 ` prefix + lesson label** | Always surfaced even when the furthest week is newly discovered (see `TimetableDiffFilter.isNoteworthyTimetableEntry`) |
| Other timetable add/modify | `📅 N modif. EDT` | `+ ` or `~ ` prefix | Excludes cancelled and eval entries (counted separately) |
| New grade | `📊 Subject: x/y` | `+ ` prefix | |
| New assignment | `📚 Subject · date` | `+ ` prefix | |
| New school-life event | `🏫 Type · date` | `+ ` prefix | Bumps priority to HIGH |
| New past competence eval result | `📋 Subject éval.` | `+ ` prefix | Distinct from upcoming evals — different domain type |

Cancelled, removed-timetable, and school-life additions promote the notification to `HIGH` priority. The title is hard-capped at 72 chars and joins at most the first three change categories with ` · `.

---

## Known Risks and Fragile Areas

| Risk | Fragility | Notes |
|---|---|---|
| Pronote field name changes | High | Scrapers use string literals from pronotepy. Monitor pronotepy releases for changes. |
| Session HTML parsing | Medium | `PronoteAuthenticator` uses regex on embedded JS. Changes to page structure will break login. |
| AES key derivation | Medium | Exact derivation formula must match pronotepy exactly. Bugs here produce silent garbage data. |
| timetable `duree` field | Medium | Assumed to be in 30-minute slots. Some Pronote instances may differ. |
| ENT/SSO portals | N/A | Explicitly unsupported. The app aborts cleanly with a clear error message. |
| Pronote instance version | Low | The auth protocol is version-dependent. The target URL `2170001x` may differ from others. |
| Raspberry Pi clock skew | Low | Pronote sessions may reject requests with wrong timestamps. Keep Pi clock synced (`timedatectl`). |

---

## Potential Future Improvements

Deferred work, ordered roughly by usefulness. These are things the codebase is already shaped to accept — not speculative redesigns.

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

### 3. Surface `memo` on upcoming-eval cards in the assignment view

`TimetableEntry.memo` is populated by `TimetableScraper` but only the `lessonLabel` reaches the eval banner/cards in `AssignmentHtmlGenerator`. The teacher's free-text memo is usually the substantive part ("apporter la calculatrice", "réviser chap. 4–6"). Trivial: include `memo` (truncated) in `renderEvalBanner` and `renderDateGroup` when present.

### 4. "Nouveau" tag on assignments newly assigned in the last 2 days

`Assignment.assignedDate` is collected but never rendered. Adding a small tag to assignments whose `assignedDate >= today.minusDays(2)` makes the page useful even on runs where nothing was *added* (catches the case where the user missed a few cron firings). Lives in `AssignmentHtmlGenerator.renderAssignmentCard` — needs one extra field on `Assignment` (or just compute at render time) and a CSS rule.

### 5. Cross-link the two "evaluation" worlds

From a timetable day-view eval badge / assignment-view eval card, link to the corresponding `CompetenceEvaluation` once it appears in the `evaluations/` snapshot. Match on same date + same subject + similar `name`/`lessonLabel`. Bidirectional: bilan-view cards can also link back to the timetable date page. Unifies the two domains that the CLAUDE.md terminology section keeps conceptually separate.

### 6. Weekly summary view

`AssignmentHtmlGenerator` recently gained weekly separators. Natural next surface: a `weekly.html` per upcoming week showing totals (devoirs count, eval count, cancellations), per-subject breakdown, all evals — the Sunday-evening overview. Lives in a new `WeeklySummaryHtmlGenerator` + renderer; consumes the existing assignment + timetable snapshots.

### 7. Trimester selector on the subject-average panel

`EvaluationSummaryHtmlGenerator` currently shows a subject-average badge that aggregates across all periods. Add a pure-CSS `:target`-based or `<details>`-driven T1/T2/T3 selector that filters the computed average to one period — no JS, no extra dependencies. The `CompetenceEvaluation.periodName` data is already available.

### General docs hygiene

The subject/teacher reference table baked into `manual-entries.yaml.example` is the user's personal cheat sheet and will go stale as teachers change. Worth treating it as live documentation — re-verify each school year. Same for the example evaluation `periodName` values.

---

## File Layout Reference

```
src/main/java/com/pronote/
├── Main.java                       ← four run modes: fetch / views / diff / validate
├── config/       AppConfig, ConfigLoader, ManualEntryLoader, SubjectEnricher
├── safety/       LockoutGuard, RateLimiter
├── auth/         CryptoHelper, PronoteSession, PronoteAuthenticator, SessionStore
├── client/       PronoteHttpClient, ApiFunction
├── scraper/      AssignmentScraper, AttachmentDownloader, TimetableScraper,
│                 GradeScraper, EvaluationScraper, SchoolLifeScraper
├── domain/       Assignment, TimetableEntry, EntryStatus, AttachmentRef,
│                 Grade, CompetenceEvaluation, CompetenceAcquisition, SchoolLifeEvent
├── persistence/  SnapshotStore, DiffEngine, DiffResult, FieldChange,
│                 TimetableDiffFilter, DiffReporter, Identifiable
├── notification/ NotificationService, NotificationPayload,
│                 NtfyNotifier, EmailNotifier, CompositeNotifier
└── views/        TimetableHtmlGenerator, TimetableViewRenderer,
                  AssignmentHtmlGenerator, AssignmentViewRenderer,
                  EvaluationHtmlGenerator, EvaluationSummaryHtmlGenerator,
                  EvaluationViewRenderer,
                  SchoolLifeHtmlGenerator, SchoolLifeViewRenderer,
                  PortalIndexHtmlGenerator,
                  GitPublisher, GitPublisherException
```

Runtime data directory (default: `./data/`):
```
data/
├── session.json                ← AES keys + cookies (permissions: 600)
├── lockout.json                ← consecutive failure counter
├── diff-latest.json            ← structured diff from the most recent run
├── diff-history.log            ← append-only one-line-per-run audit trail
├── snapshots/
│   ├── assignments/latest.json
│   ├── assignments/archive/*.json
│   ├── assignments/attachments/
│   │   └── <sanitized-assignmentId>/
│   │       └── <sanitizedFileName>   ← downloaded G=1 files; idempotent across runs
│   ├── timetable/latest.json
│   ├── timetable/archive/*.json
│   ├── grades/latest.json
│   ├── grades/archive/*.json
│   ├── evaluations/latest.json
│   ├── evaluations/archive/*.json
│   ├── school-life/latest.json
│   └── school-life/archive/*.json
├── views/
│   ├── index.html              ← portal: cards linking to each enabled view type
│   ├── timetable/
│   │   ├── index.html          ← week overview (day cards)
│   │   ├── current.html        ← today (if ongoing) or next non-empty weekday
│   │   └── YYYY-MM-DD.html     ← one self-contained page per upcoming weekday
│   ├── assignments/index.html
│   ├── evaluations/index.html  ← bilan (all evals)
│   ├── evaluations/summary.html
│   └── school-life/index.html
└── logs/app.log
```
