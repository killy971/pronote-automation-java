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
| `--mode <mode>` | No (default: `fetch`) | Run mode: `fetch` (full online pipeline), `views` (regenerate HTML from last snapshot, offline), `diff` (re-run diff between last two snapshots + re-notify, offline). |

**Make targets:**

| Target | Description |
|---|---|
| `make run` | Full run: fetch from Pronote, diff, notify, save snapshots, generate views |
| `make views` | Offline: regenerate HTML timetable views from `data/snapshots/timetable/latest.json` |
| `make diff` | Offline: re-run diff between archive[-1] and `latest.json`, re-notify if changes exist |
| `make run-debug` | Same as `make run` with DEBUG logging |
| `make build` | Compile and package the fat JAR |
| `make test` | Run unit tests |

---

## Architecture Summary

```
Main
 ├── ConfigLoader          → loads config.yaml (SnakeYAML)
 ├── LockoutGuard          → halts job after N consecutive login failures
 ├── SessionStore          → load/save session.json (persists AES keys + cookies)
 ├── PronoteAuthenticator  → full 10-step login flow
 ├── PronoteHttpClient     → AES-encrypted JSON-RPC calls + rate limiting + attachment download
 ├── AssignmentScraper     → calls ListeTravailAFaire API function; builds AttachmentRef list
 ├── AttachmentDownloader  → idempotent download of G=1 attachments; respects rate limiting
 ├── TimetableScraper      → calls PageEmploiDuTemps API function
 ├── SnapshotStore         → read/write latest.json + archive/
 ├── DiffEngine            → field-level comparison via Jackson tree model
 ├── CompositeNotifier     → fan-out to NtfyNotifier + EmailNotifier
 └── TimetableViewRenderer → generates static HTML pages from timetable snapshot
```

All modules are **stateless except `PronoteSession`** (mutable AES key/IV/counter state).
`Main.java` is the only orchestration point — it wires everything together.

---

## Key Modules and Responsibilities

| Package | Class | Responsibility |
|---|---|---|
| `config` | `AppConfig` | POJO tree for config.yaml |
| `config` | `ConfigLoader` | Load + validate YAML; fail fast |
| `safety` | `LockoutGuard` | Track login failures in `data/lockout.json` |
| `safety` | `RateLimiter` | Sleep `minDelay + random(jitter)` before each request |
| `auth` | `CryptoHelper` | AES-CBC, RSA-1024, MD5, SHA256, key derivation |
| `auth` | `PronoteSession` | Mutable: AES key/IV, cookies, order counter |
| `auth` | `PronoteAuthenticator` | Full login flow (see Authentication section below) |
| `auth` | `SessionStore` | Serialize/deserialize session to `data/session.json` |
| `client` | `PronoteHttpClient` | Encrypted POST: builds envelope, calls rate limiter, decrypts response; also exposes `download()` for rate-limited binary GETs |
| `scraper` | `AssignmentScraper` | Fetch + map French JSON fields to `Assignment`; builds `AttachmentRef` list |
| `scraper` | `AttachmentDownloader` | Idempotent G=1 attachment download; resolves `localPath` from disk; respects rate limiter |
| `scraper` | `TimetableScraper` | Fetch per-week; map to `TimetableEntry` |
| `domain` | `Assignment`, `TimetableEntry` | Pure data, Jackson-serializable, implements `Identifiable` |
| `domain` | `AttachmentRef` | Attachment metadata: `stableId`, `fileName`, `uploadedFile`, `localPath`, `mimeType`; transient `downloadUrl` (never persisted) |
| `persistence` | `SnapshotStore` | Write `latest.json`, archive old, purge expired |
| `persistence` | `DiffEngine` | Generic field-level diff via `jackson.valueToTree()`; registers `AttachmentRefDiffMixin` to exclude runtime fields from comparison |
| `notification` | `NtfyNotifier` | HTTP POST to ntfy topic |
| `notification` | `EmailNotifier` | Jakarta Mail SMTP + STARTTLS |
| `notification` | `CompositeNotifier` | Fan-out; per-channel failure is logged, not fatal |
| `views` | `TimetableHtmlGenerator` | Builds one self-contained HTML5 day page from a `List<TimetableEntry>` |
| `views` | `TimetableViewRenderer` | Computes target dates, calls generator per day, writes files + `index.html` |

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

## Timetable HTML Views

After each successful timetable fetch, `Main.java` calls `TimetableViewRenderer.render(timetable)` (step 11), which is **unconditional** — it regenerates all pages on every run regardless of whether a diff was detected. This keeps views in sync with the latest snapshot.

### Config (`timetableView` block)

| Key | Default | Description |
|---|---|---|
| `enabled` | `true` | Set to `false` to skip all view generation |
| `outputDirectory` | `./data/views/timetable` | Where HTML files are written. Use `./docs/timetable` for GitHub Pages. |
| `daysAhead` | `5` | Number of upcoming **weekdays** (Mon–Fri) to generate, starting from today |

### Output

One file per weekday: `YYYY-MM-DD.html` + an `index.html` overview card grid. All files are self-contained (CSS embedded inline — no external resources).

### CSS / styling

All CSS lives in the `TimetableHtmlGenerator.CSS` static text-block constant. It is embedded verbatim into every generated file.
- Light/dark mode: `prefers-color-scheme` media query with CSS custom properties — **no JavaScript**.
- Responsive: `max-width: 480px` centred container; same layout works on mobile and desktop.
- Subject accent colours: 12-colour palette; colour index = `abs(subject.hashCode()) % 12` — deterministic across runs and days.

### Modifying the views

To change layout or styling, edit `TimetableHtmlGenerator.CSS` and/or the HTML-building methods in `TimetableHtmlGenerator`. The renderer (`TimetableViewRenderer`) only handles file I/O and date selection — it does not contain any HTML.

### GitHub Pages deployment

Point `outputDirectory` to `docs/timetable` in `config.yaml`, commit the `docs/` folder, and enable GitHub Pages from the `docs/` root in the repository settings.

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

## File Layout Reference

```
src/main/java/com/pronote/
├── Main.java
├── config/       AppConfig, ConfigLoader
├── safety/       LockoutGuard, RateLimiter
├── auth/         CryptoHelper, PronoteSession, PronoteAuthenticator, SessionStore
├── client/       PronoteHttpClient, ApiFunction
├── scraper/      AssignmentScraper, TimetableScraper, AttachmentDownloader
├── domain/       Assignment, TimetableEntry, EntryStatus, AttachmentRef
├── persistence/  SnapshotStore, DiffEngine, DiffResult, FieldChange, Identifiable
├── notification/ NotificationService, NotificationPayload,
│                 NtfyNotifier, EmailNotifier, CompositeNotifier
└── views/        TimetableHtmlGenerator, TimetableViewRenderer
```

Runtime data directory (default: `./data/`):
```
data/
├── session.json          ← AES keys + cookies (permissions: 600)
├── lockout.json          ← consecutive failure counter
├── snapshots/
│   ├── assignments/latest.json
│   ├── assignments/archive/*.json
│   ├── assignments/attachments/
│   │   └── <sanitized-assignmentId>/
│   │       └── <sanitizedFileName>   ← downloaded G=1 files; idempotent across runs
│   ├── timetable/latest.json
│   └── timetable/archive/*.json
├── views/
│   └── timetable/        ← default timetableView.outputDirectory
│       ├── index.html    ← day-card overview, regenerated every run
│       └── YYYY-MM-DD.html  ← one self-contained page per upcoming weekday
└── logs/app.log
```
