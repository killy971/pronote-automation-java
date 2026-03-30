# pronote-automation-java

A lightweight Java application that monitors a [Pronote](https://www.index-education.com/fr/logiciel-gestion-vie-scolaire.php) parent portal for changes in assignments and timetable, and sends push/email notifications when anything changes.

Designed to run **unattended** on a Raspberry Pi as a systemd timer job.

---

## What it does

Every run (typically every 15–20 minutes):

1. Logs into the Pronote parent portal using the encrypted JSON-RPC protocol
2. Fetches the student's assignments and timetable
3. Compares the results with the previous run
4. Downloads any new assignment attachments (uploaded files) to disk
5. Sends a notification (ntfy push + optional email) if anything changed
6. Saves the new snapshot for the next comparison

**No official Pronote API is used.** The protocol is reverse-engineered from [pronotepy](https://github.com/bain3/pronotepy).

---

## What it does NOT support

- ENT/SSO portals (EduConnect, etc.) — direct Pronote credentials only
- Student login via external identity providers
- Multiple students/accounts in one run

---

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Java | 21 | `sudo apt install openjdk-21-jre-headless` on Raspberry Pi OS |
| OS | Any | Tested on macOS (dev) and Raspberry Pi OS (Bookworm/Bullseye) |
| Pronote account | — | Parent or student login |
| ntfy app (iOS/Android) | — | Free; subscribe to a topic to receive push notifications |

### Raspberry Pi notes

- Minimum: Raspberry Pi 2 or newer, 512 MB RAM
- Java 21 is available via `apt` on Raspberry Pi OS Bookworm:
  ```bash
  sudo apt update && sudo apt install openjdk-21-jre-headless
  java -version  # should show 21.x
  ```
- If on Bullseye (older), use the [Adoptium](https://adoptium.net) tarball for `aarch64`.

---

## Installation

### 1. Get the JAR

**Build from source:**
```bash
git clone https://github.com/youruser/pronote-automation-java
cd pronote-automation-java
./gradlew shadowJar
# JAR: build/libs/pronote-automation-1.0.0.jar
```

**Copy to the Pi:**
```bash
scp build/libs/pronote-automation-1.0.0.jar pi@raspberrypi:/opt/pronote/
```

### 2. Configure

```bash
cp config.yaml.example config.yaml
nano config.yaml   # fill in credentials and notification settings
```

See [Configuration](#configuration) for all options.

### 3. Run manually (first test)

```bash
java -jar pronote-automation-1.0.0.jar --config config.yaml
```

To run only specific data sets, use `--features` (comma-separated, no spaces):

```bash
java -jar pronote-automation-1.0.0.jar --config config.yaml \
  --features assignments,timetable,grades,evaluations

# Valid feature names: assignments, timetable, grades, evaluations, schoolLife
# When omitted, the features.* flags in config.yaml determine what is fetched.
```

Expected output on first run:
```
INFO  ConfigLoader      - Loading configuration from config.yaml
INFO  Main              - Performing full login...
INFO  Authenticator     - Authentication successful (session h=..., a=...)
INFO  AssignmentScraper - Fetched 12 assignments
INFO  TimetableScraper  - Fetched 47 timetable entries
INFO  Main              - No changes detected.
INFO  SnapshotStore     - Snapshot 'assignments' saved (12 items)
INFO  SnapshotStore     - Snapshot 'timetable' saved (47 items)
```

On a subsequent run with changes:
```
INFO  Main         - Changes detected, sending notifications...
INFO  NtfyNotifier - ntfy notification sent (title: 'Pronote update: 2 change(s)')
```

### 4. Deploy with systemd (Raspberry Pi)

```bash
sudo bash deploy/install.sh
sudo nano /opt/pronote/config.yaml   # add your credentials
sudo systemctl start pronote.timer
sudo systemctl status pronote.timer
```

---

## Configuration

Copy `config.yaml.example` to `config.yaml` and fill in:

```yaml
pronote:
  baseUrl: "https://YOUR-INSTANCE.index-education.net/pronote/"
  loginMode: PARENT          # PARENT or STUDENT
  username: "your_username"
  password: "your_password"  # or use PRONOTE_PASSWORD env var
  weeksBefore: 0             # weeks to look back
  weeksAhead: 2              # weeks to look ahead

data:
  directory: "./data"
  archiveRetainDays: 30

safety:
  minDelayMs: 2000           # DO NOT lower below 1000
  jitterMs: 500
  maxLoginFailures: 3

notifications:
  ntfy:
    enabled: true
    serverUrl: "https://ntfy.sh"
    topic: "your-secret-topic"
  email:
    enabled: false
    smtpHost: "smtp.gmail.com"
    smtpPort: 587
    username: "you@gmail.com"
    password: "app_password"
    from: "you@gmail.com"
    to: "recipient@example.com"
```

### Passing the password via environment variable

```bash
export PRONOTE_PASSWORD="your_password"
java -jar pronote-automation-1.0.0.jar --config config.yaml
```

### ntfy setup

1. Install the [ntfy app](https://ntfy.sh) on iOS/Android
2. Subscribe to a topic — use a long, random string (e.g., `pronote-abc123xyz`)
3. Set that topic in `config.yaml`
4. No account required for the public `ntfy.sh` server

### Gmail App Password

1. Enable 2-Step Verification on your Google account
2. Go to [myaccount.google.com/apppasswords](https://myaccount.google.com/apppasswords) → create a Mail app password
3. Use that 16-character password in `email.password`

---

## Scheduling

### systemd timers (recommended)

Two timer units cover the two common schedules:

| Timer | Features | Cadence |
|---|---|---|
| `pronote-frequent.timer` | assignments, timetable, grades, evaluations | Every 15 min, all day |
| `pronote-school.timer` | schoolLife | Every 30 min, Mon–Fri 08:00–18:00 |

```bash
sudo bash deploy/install.sh
sudo systemctl start pronote-frequent.timer pronote-school.timer
sudo systemctl enable pronote-frequent.timer pronote-school.timer

# Check next triggers:
systemctl list-timers 'pronote-*'

# View logs:
journalctl -u pronote-frequent.service -f
journalctl -u pronote-school.service -f
```

Each service unit passes a different `--features` flag to the same JAR:

```ini
# pronote-frequent.service
ExecStart=/path/to/java -Xmx128m -jar /opt/pronote/pronote-automation.jar \
    --config /opt/pronote/config.yaml \
    --features assignments,timetable,grades,evaluations

# pronote-school.service
ExecStart=/path/to/java -Xmx128m -jar /opt/pronote/pronote-automation.jar \
    --config /opt/pronote/config.yaml \
    --features schoolLife
```

### cron (alternative)

```cron
# Frequent — all day
*/15 * * * * /path/to/java -jar /opt/pronote/pronote-automation.jar \
  --config /opt/pronote/config.yaml \
  --features assignments,timetable,grades,evaluations \
  >> /opt/pronote/data/logs/cron.log 2>&1

# School hours — Mon–Fri 08:00–18:00
*/30 8-18 * * 1-5 /path/to/java -jar /opt/pronote/pronote-automation.jar \
  --config /opt/pronote/config.yaml \
  --features schoolLife \
  >> /opt/pronote/data/logs/cron.log 2>&1
```

---

## Data Files

After a run, the `data/` directory contains:

```
data/
├── session.json                        ← reused session (do not share)
├── lockout.json                        ← login failure counter
├── diff-latest.json                    ← full structured diff from the most recent run
├── diff-history.log                    ← one line per run, append-only audit trail
├── snapshots/
│   ├── assignments/latest.json
│   ├── assignments/archive/2025-03-27T14-00-00Z.json
│   ├── assignments/attachments/        ← downloaded uploaded-file attachments
│   │   └── <subject@dueDate@...>/     ← one directory per assignment
│   │       └── filename.pdf
│   ├── timetable/latest.json
│   └── timetable/archive/
└── logs/app.log
```

### Diff files

These two files are written on **every run**, whether or not notifications are enabled.
They are the primary way to observe what the application found while notifications are off.

**`data/diff-history.log`** — one line per run, never deleted:
```
2025-03-27T14:00:05Z | FIRST_RUN  | baseline established
2025-03-27T14:15:08Z | NO_CHANGE  | assignments: +0 -0 ~0 | timetable: +0 -0 ~0
2025-03-27T14:30:11Z | CHANGES(2) | assignments: +1 -0 ~0 | timetable: +0 -0 ~1
```

**`data/diff-latest.json`** — full structured diff from the most recent run:
```json
{
  "runAt": "2025-03-27T14:30:11Z",
  "firstRun": false,
  "hasChanges": true,
  "assignments": {
    "added": 1,
    "removed": 0,
    "modified": 0,
    "addedItems": [ { "id": "...", "subject": "Mathematics", ... } ],
    "removedItems": [],
    "modifiedItems": []
  },
  "timetable": { ... }
}
```

**`data/snapshots/assignments/latest.json`** — the full current assignment list:
```json
[
  {
    "id": "Mathematics@2025-04-02@2025-03-27",
    "subject": "Mathematics",
    "description": "Exercises p.45 #1-10",
    "dueDate": "2025-04-02",
    "assignedDate": "2025-03-27",
    "done": false,
    "attachments": [
      {
        "stableId": "Mathematics@2025-04-02@2025-03-27|exercices.pdf",
        "fileName": "exercices.pdf",
        "uploadedFile": true,
        "url": null,
        "localPath": "/opt/pronote/data/snapshots/assignments/attachments/Mathematics_2025-04-02_2025-03-27/exercices.pdf",
        "mimeType": "application/pdf"
      }
    ]
  }
]
```

- `uploadedFile: true` — G=1 Pronote file, downloaded to `localPath`
- `uploadedFile: false` — G=0 external hyperlink; `url` holds the link, `localPath` is always null
- `localPath` is null if the download failed (non-fatal; retried on next run)

---

## Makefile

```bash
make build    # compile and package the fat JAR
make test     # run unit tests
make run      # run with ./config.yaml
make clean    # remove build artifacts
```

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Configuration file not found` | `cp config.yaml.example config.yaml` and fill in credentials |
| `Could not extract session handle (h)` | Run with `-DLOG_LEVEL=DEBUG` and inspect the fetched HTML |
| `Login page redirected to…` | ENT/SSO login — not supported. Requires direct Pronote credentials |
| `Locked out after N consecutive failures` | Reset: `echo '{"consecutiveFailures":0}' > data/lockout.json`, verify credentials |
| `0 assignments / 0 timetable entries` | Enable DEBUG logging, check raw API response for field structure |
| Notifications not arriving | ntfy: test with `curl -d "test" ntfy.sh/your-topic`; email: check spam, verify App Password |
| High memory on Pi | Lower heap in service file: `-Xmx96m` |
