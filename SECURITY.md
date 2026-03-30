# SECURITY.md

Security practices and credential handling for pronote-automation-java.

---

## Credentials Storage

### config.yaml

The `config.yaml` file contains:
- Pronote username and password
- Email SMTP password
- ntfy bearer token (if used)

**It is excluded from git** via `.gitignore`. Never commit it.

On disk, restrict it to the owning user:
```bash
chmod 600 /opt/pronote/config.yaml
```

### Environment variable alternative

Pass the Pronote password via environment variable instead of the config file:
```bash
export PRONOTE_PASSWORD="your_password"
java -jar pronote-automation.jar --config config.yaml
```

In the systemd service, add to `[Service]`:
```ini
Environment=PRONOTE_PASSWORD=your_password
```
Or use `EnvironmentFile=/opt/pronote/.env` with a separate secrets file (also `chmod 600`).

### session.json

After a successful login, the application writes `data/session.json` containing:
- The AES session key and IV
- Pronote session cookies
- The session handle

This file is equivalent to a login token. The application sets permissions to `600` automatically on POSIX systems. Verify:
```bash
ls -la /opt/pronote/data/session.json
# -rw------- 1 pronote pronote ...
```

If it is readable by other users on the system, restrict it:
```bash
chmod 600 /opt/pronote/data/session.json
```

---

## What NOT to Commit

The `.gitignore` already excludes these — double-check before any `git add`:

```
config.yaml          ← credentials
data/                ← session, snapshots, logs
*.jar                ← built artifacts
```

Run `git status` before committing and verify none of these appear as staged.

---

## Network Security

- All communication with Pronote is over **HTTPS** (TLS 1.2+).
- The AES key exchange uses the server's certificate chain for trust.
- ntfy notifications are sent to `https://ntfy.sh` over HTTPS.
- Email is sent with STARTTLS (`mail.smtp.starttls.required=true`).

The application does **not** skip TLS certificate verification. Do not modify the `OkHttpClient` or Jakarta Mail configuration to disable certificate validation.

---

## Safe Usage to Avoid Account Bans

### Rate limiting

The `safety.minDelayMs` setting (default: 2000ms) adds a minimum delay between all HTTP requests. **Do not set this below 1000ms.** Setting it to 0 risks triggering Pronote's bot detection and getting the account suspended.

```yaml
safety:
  minDelayMs: 2000    # minimum — do not lower
  jitterMs: 500       # adds randomness — keep at 300+ ms
```

### Login attempt limits

The `safety.maxLoginFailures` setting (default: 3) halts the job after repeated login failures. Do not raise this above 5. Repeated failed logins are the fastest way to get an account locked.

### Scheduling frequency

Do not schedule the job more frequently than every **10 minutes**. The default 15-minute interval with 60-second systemd jitter is conservative and appropriate.

### One instance at a time

Do not run multiple instances of the application simultaneously. The systemd `Type=oneshot` configuration ensures this on Raspberry Pi. If running via cron, add a lock:
```bash
flock -n /tmp/pronote.lock java -jar pronote-automation.jar --config config.yaml --features assignments,timetable,grades,evaluations
```

### What the application logs

The application logs at `INFO` level by default. It **never logs**:
- Passwords or passphrases
- AES keys or IVs
- Cookie values or session tokens
- Full HTTP request/response bodies

`DEBUG` level logs request metadata (URL, HTTP status, response size) but not content. Do not share raw `DEBUG` logs publicly.

---

## Threat Model

This application is designed for personal use on a home network device. It is **not** designed to handle:
- Multi-tenant deployments
- Untrusted input (config.yaml must be controlled by the operator)
- Public-facing network exposure (the Pi should not expose this service externally)
