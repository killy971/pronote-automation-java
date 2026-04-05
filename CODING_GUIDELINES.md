# Coding Guidelines

Pragmatic standards for this codebase. Enforce clarity, minimalism, and Java 21 idioms.

---

## Core Principles

1. **Clarity over cleverness** — the next reader matters more than the author's satisfaction.
2. **Minimum necessary complexity** — do not design for hypothetical futures.
3. **Fail fast, fail loudly** — validate at boundaries; propagate errors without swallowing them.
4. **No frameworks** — dependencies are wired manually in `Main.java`. No Spring, no DI container.

---

## Java 21 Usage

### DO use `record` for pure value types

A type qualifies for `record` when:
- all fields are set at construction time and never mutated, AND
- it is **not** Jackson-deserialized from persisted JSON (records lack the no-arg constructor that Jackson requires for `@JsonIgnoreProperties` deserialization)

```java
// Good — immutable, never deserialized from disk
public record FieldChange(String fieldName, Object oldValue, Object newValue) {
    @Override public String toString() { return fieldName + ": " + oldValue + " → " + newValue; }
}

public record DiffResult<T extends Identifiable>(
        List<T> added, List<T> removed, Map<T, List<FieldChange>> modified) {
    public boolean isEmpty() { return added.isEmpty() && removed.isEmpty() && modified.isEmpty(); }
}
```

**Why domain classes (`Assignment`, `Grade`, etc.) are NOT records:**
Jackson deserializes snapshots from disk using a no-arg constructor + setters. Records provide neither. Do not change domain classes to records without reworking the snapshot deserialization strategy.

### DO use exhaustive `switch` expressions over enums

```java
// Good — compiler will catch unhandled cases when enum grows
return switch (priority) {
    case LOW    -> "low";
    case NORMAL -> "default";
    case HIGH   -> "high";
};

// Bad — default masks future enum additions silently
return switch (priority) {
    case LOW  -> "low";
    case HIGH -> "high";
    default   -> "default";  // hides NORMAL and any future values
};
```

### Use `var` only where the type is obvious from the right-hand side

```java
// Good — type is unambiguous from constructor call
var rateLimiter = new RateLimiter(minDelayMs, jitterMs);

// Bad — reader must look up what encryptedPost() returns
var result = client.encryptedPost(session, func, params);
```

### DO use text blocks for multi-line string literals

```java
// Good
String html = """
        <!DOCTYPE html>
        <html><body>%s</body></html>
        """.formatted(content);
```

### DO use pattern matching `instanceof`

```java
// Good
if (!(o instanceof Assignment a)) return false;
return Objects.equals(id, a.id);

// Bad
if (!(o instanceof Assignment)) return false;
Assignment a = (Assignment) o;
```

### Use `List.of()` / `Map.of()` for literal collections that won't be mutated

```java
return new DiffResult<>(List.of(), List.of(), Map.of());
```

Use `new ArrayList<>()` / `new LinkedHashMap<>()` only when the collection will be mutated after creation.

---

## What NOT to use

| Feature | Reason |
|---|---|
| `Optional` as a field or parameter | Use it only as a method return type to signal "may be absent" |
| `Stream` pipelines longer than ~3 steps | A for-loop is easier to debug and step through |
| Checked exceptions propagated across module boundaries | Each module defines its own `RuntimeException` subclass |
| `javax.crypto` for AES/RSA | Use BouncyCastle — compatibility issues on some JREs |
| Spring / DI frameworks | Overkill for a single-shot CLI tool |
| POJO deserialization for Pronote API responses | Field names change between instances; use `JsonNode` tree model |

---

## Module-Level Conventions

### Exception types

Each package exposes exactly one `RuntimeException` subclass:

```
auth      → AuthException
safety    → LockoutGuard.LockoutException
client    → (propagates as RuntimeException from PronoteHttpClient)
scraper   → ScraperException (if needed)
notification → NotificationService.NotificationException (checked — callers decide)
```

Do not let `IOException`, `MessagingException`, or other library exceptions leak past module boundaries unwrapped.

### Logging

Use SLF4J throughout. Level conventions:

| Level | When |
|---|---|
| `ERROR` | Something failed that the operator must know about |
| `WARN` | Recoverable anomaly or data quality issue |
| `INFO` | Key pipeline milestones (login, fetch counts, diff summary) |
| `DEBUG` | Per-item detail, raw JSON, rate-limiter waits |

**Never log**: passwords, AES keys, session cookies, raw cookie values.

### Jackson tree model

All Pronote API responses are parsed via `JsonNode` / `ObjectNode`. Do not create POJOs for Pronote response structures — field names change between Pronote instances and versions.

Safe null-tolerant access pattern:

```java
// Good
String subject = node.path("Matiere").path("V").path("L").asText("");

// Bad — NPE if any node is missing
String subject = node.get("Matiere").get("V").get("L").asText();
```

### Rate limiter

`RateLimiter.await()` **must** be called before every outbound HTTP request. It is called unconditionally inside `PronoteHttpClient.encryptedPost()` and `download()`. Do not add raw OkHttp calls that bypass it.

### Session safety

- `SessionStore` saves AES key material — ensure file permissions are 600 on POSIX.
- `LockoutGuard` halts the job after 3 consecutive login failures. Never add retry loops around `PronoteAuthenticator.login()`.
- Always attempt session reuse (probe existing session) before performing a full login.

---

## Naming

| Construct | Convention | Example |
|---|---|---|
| Classes | UpperCamelCase | `AssignmentScraper` |
| Methods / fields | lowerCamelCase | `fetchAssignments` |
| Constants | UPPER_SNAKE_CASE | `KNOWN_FEATURES` |
| Packages | lowercase, single word | `com.pronote.scraper` |
| Config POJOs | suffixed `Config` | `SafetyConfig` |
| Scrapers | suffixed `Scraper` | `GradeScraper` |
| Domain objects | noun, no suffix | `Assignment`, `Grade` |
| Exceptions | suffixed `Exception` | `AuthException` |

---

## Adding a New Scraper

1. Read `docs/pronotepy-reference.md` first — it documents API function names, onglet numbers, and param structures.
2. Add the API function to `ApiFunction.java`.
3. Create a domain class implementing `Identifiable` in `com.pronote.domain`.
4. Create `XxxScraper.java` in `com.pronote.scraper`, following the `AssignmentScraper` pattern.
5. Add snapshot load/save + diff wiring in `Main.java`.
6. Add the new type to `buildPayload()` in `Main.java`.

Check the pronotepy implementation of your specific function to determine:
- Whether `ressource` must be added to params (required by some APIs, not others).
- Whether the API accepts `DateDebut`/`DateFin` or `NumeroSemaine` or something else entirely.

---

## File Layout

```
src/main/java/com/pronote/
├── Main.java               orchestration only — no business logic
├── config/                 AppConfig, ConfigLoader, SubjectEnricher
├── safety/                 LockoutGuard, RateLimiter
├── auth/                   CryptoHelper, PronoteSession, PronoteAuthenticator, SessionStore
├── client/                 PronoteHttpClient, ApiFunction
├── scraper/                *Scraper, AttachmentDownloader
├── domain/                 pure data + Identifiable
├── persistence/            SnapshotStore, DiffEngine, DiffResult, FieldChange, DiffReporter, TimetableDiffFilter
└── notification/           NotificationService, NotificationPayload, *Notifier
```
