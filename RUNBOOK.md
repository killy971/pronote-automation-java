# RUNBOOK — pronote-automation-java

Operational guide for running in production on a Raspberry Pi.

---

## Initial Deployment

### 1. Install Java 21

```bash
sudo apt update
sudo apt install -y openjdk-21-jre-headless
java -version   # verify: openjdk 21.x
```

### 2. Deploy the application

```bash
sudo bash deploy/install.sh
```

This script:
- Creates a `pronote` system user (no login shell)
- Copies the JAR to `/opt/pronote/`
- Creates `/opt/pronote/data/` with restricted permissions
- Copies `config.yaml.example` to `/opt/pronote/config.yaml` (if not already present)
- Installs and enables `pronote.service` + `pronote.timer`

### 3. Edit credentials

```bash
sudo nano /opt/pronote/config.yaml
```

Minimum required fields:
```yaml
pronote:
  baseUrl: "https://YOUR-INSTANCE.index-education.net/pronote/"
  username: "your_username"
  password: "your_password"
notifications:
  ntfy:
    enabled: true
    topic: "your-secret-topic"
```

### 4. Test before scheduling

Run once as the `pronote` user (fetches all enabled features from config):
```bash
sudo -u pronote java -Xmx128m -jar /opt/pronote/pronote-automation.jar \
  --config /opt/pronote/config.yaml
```

Or test a specific feature set using `--features`:
```bash
sudo -u pronote java -Xmx128m -jar /opt/pronote/pronote-automation.jar \
  --config /opt/pronote/config.yaml \
  --features assignments,timetable,grades,evaluations
```

Expect: successful login, data fetched, snapshots written.

### 5. Start the timers

```bash
sudo systemctl start pronote-frequent.timer pronote-school.timer
sudo systemctl status pronote-frequent.timer pronote-school.timer
```

---

## Updating the Application

### Build a new JAR (on dev machine)

```bash
./gradlew shadowJar
```

### Deploy to the Pi

```bash
scp build/libs/pronote-automation-1.0.0.jar pi@raspberrypi:/tmp/

ssh pi@raspberrypi
sudo systemctl stop pronote.timer
sudo cp /tmp/pronote-automation-1.0.0.jar /opt/pronote/pronote-automation.jar
sudo chown pronote:pronote /opt/pronote/pronote-automation.jar
sudo systemctl start pronote.timer
```

**Note:** The service file in `/etc/systemd/system/pronote.service` references
`pronote-automation.jar` (without version number). Copy the new JAR with that filename:
```bash
sudo cp /tmp/pronote-automation-1.0.0.jar /opt/pronote/pronote-automation.jar
```

---

## Logs

### systemd journal (primary)

```bash
# Follow live output:
journalctl -u pronote.service -f

# Last 50 lines:
journalctl -u pronote.service -n 50

# Last hour:
journalctl -u pronote.service --since "1 hour ago"

# Filter by severity:
journalctl -u pronote.service -p err
```

### File logs (secondary)

Logback writes rolling daily logs to `/opt/pronote/data/logs/`:
```bash
tail -f /opt/pronote/data/logs/app.log
```

Logs rotate daily, retaining 7 days.

### Typical successful run log

```
INFO  ConfigLoader        - Loading configuration from /opt/pronote/config.yaml
INFO  Main                - Found existing session, probing...
INFO  Main                - Session reuse successful — skipping login.
INFO  AssignmentScraper   - Fetching assignments from 2025-03-24 to 2025-04-13
INFO  AssignmentScraper   - Parsed 14 assignments
INFO  TimetableScraper    - Fetching timetable for week 13 (starting 2025-03-24)
INFO  TimetableScraper    - Fetching timetable for week 14 (starting 2025-03-31)
INFO  TimetableScraper    - Fetching timetable for week 15 (starting 2025-04-07)
INFO  TimetableScraper    - Fetched 52 timetable entries total
INFO  Main                - Assignment diff: DiffResult{added=0, removed=0, modified=0}
INFO  Main                - Timetable diff: DiffResult{added=0, removed=0, modified=0}
INFO  Main                - No changes detected.
INFO  SnapshotStore       - Snapshot 'assignments' saved (14 items)
INFO  SnapshotStore       - Snapshot 'timetable' saved (52 items)
INFO  Main                - Job completed successfully.
```

---

## Incident Procedures

### Login failures

**Symptom:** Log shows `Login failed: ...` or `AuthException`.

**Immediate action:**
```bash
cat /opt/pronote/data/lockout.json
# { "consecutiveFailures": 2, "lastFailureTimestamp": "2025-03-27T..." }
```

**Steps:**
1. Verify credentials are correct:
   ```bash
   sudo -u pronote cat /opt/pronote/config.yaml | grep -E "username|password"
   ```
2. Try logging in manually at the Pronote URL in a browser.
3. If credentials are correct, the session cookie may have an issue — delete it:
   ```bash
   sudo -u pronote rm /opt/pronote/data/session.json
   ```
4. Run once manually to confirm login works:
   ```bash
   sudo -u pronote java -Xmx128m -jar /opt/pronote/pronote-automation.jar \
     --config /opt/pronote/config.yaml \
     --features assignments,timetable,grades,evaluations,schoolLife
   ```
5. If it succeeds, the timer will auto-resume. No further action needed.

### Account lock risk / lockout guard triggered

**Symptom:** Log shows `Locked out after N consecutive login failures`. Timer stops doing useful work.

**This is a safety feature, not a bug.** Do not bypass it.

**Steps:**
1. Stop the timers to prevent further (failed) attempts:
   ```bash
   sudo systemctl stop pronote-frequent.timer pronote-school.timer
   ```
2. Verify credentials manually (browser login).
3. Reset the lockout counter **only after fixing the underlying issue**:
   ```bash
   echo '{"consecutiveFailures":0,"lastFailureTimestamp":null}' \
     | sudo -u pronote tee /opt/pronote/data/lockout.json
   ```
4. Run once manually to confirm:
   ```bash
   sudo -u pronote java -Xmx128m -jar /opt/pronote/pronote-automation.jar \
     --config /opt/pronote/config.yaml \
     --features assignments,timetable,grades,evaluations,schoolLife
   ```
5. Restart the timers:
   ```bash
   sudo systemctl start pronote-frequent.timer pronote-school.timer
   ```

**If the account appears to be genuinely locked on the Pronote side:**
- Wait at least 30 minutes before attempting any login.
- Contact your school's IT department if the lockout persists.
- Do **not** increase `maxLoginFailures` as a workaround.

### Notification failures

**Symptom:** Log shows `Notification channel NtfyNotifier failed: ...` or email errors.

Notification failures are **non-fatal** — the job continues and snapshots are still saved.

**ntfy troubleshooting:**
```bash
# Test ntfy manually:
curl -d "Test from runbook" https://ntfy.sh/your-topic

# If using a token:
curl -H "Authorization: Bearer your-token" \
     -d "Test" https://ntfy.sh/your-topic
```

Common issues:
- Wrong topic name → change in `config.yaml`
- Private topic with missing/wrong token → add `token:` to ntfy config
- ntfy.sh temporarily unavailable → check https://status.ntfy.sh

**Email troubleshooting:**
```bash
# Test SMTP connectivity:
openssl s_client -starttls smtp -connect smtp.gmail.com:587
```

Common issues:
- Gmail: password is account password, not App Password → create an App Password
- Gmail: "Less secure apps" setting → not needed with App Password
- Port blocked by ISP → try port 465 with SSL (requires `mail.smtp.ssl.enable=true` — code change needed)

### Data corruption / unexpected snapshot content

```bash
# Inspect current snapshots:
sudo -u pronote cat /opt/pronote/data/snapshots/assignments/latest.json | python3 -m json.tool | head -60

# Inspect attachment references in the snapshot:
sudo -u pronote python3 -c "
import json
data = json.load(open('/opt/pronote/data/snapshots/assignments/latest.json'))
for a in data:
    for att in a.get('attachments', []):
        print(a['subject'], '|', att['fileName'], '| local:', att.get('localPath'))
"

# Restore snapshot from archive if needed:
sudo -u pronote cp /opt/pronote/data/snapshots/assignments/archive/2025-03-27T*.json \
  /opt/pronote/data/snapshots/assignments/latest.json
# Note: restoring from archive does not affect downloaded attachment files.
```

### Session file left from a previous crash

```bash
sudo -u pronote rm /opt/pronote/data/session.json
```

The next run will perform a fresh login.

---

## Scheduled Maintenance

### Verify timer health (weekly)

```bash
systemctl list-timers pronote.timer
# Should show a "NEXT" time within the next 15 minutes
```

### Check disk usage (monthly)

```bash
du -sh /opt/pronote/data/
du -sh /opt/pronote/data/snapshots/assignments/attachments/
```

Snapshot archives older than `archiveRetainDays` (default: 30) are purged automatically on each save.

Downloaded attachments are **not** automatically purged — they accumulate as new school files are detected. Each file is stored once and never re-downloaded. Clean up manually if disk space becomes a concern:
```bash
# List by assignment, sorted by size:
du -sh /opt/pronote/data/snapshots/assignments/attachments/*/

# Remove attachments for past assignments (manual, no automation):
rm -rf "/opt/pronote/data/snapshots/assignments/attachments/MATHEMATICS_2025-09-15_2025-09-10/"
```

### Verify Java version after OS updates

```bash
java -version
# Must be 21.x — if updated to a different major version, test the JAR before re-enabling the timer
```

---

## Timer / Service Management Reference

```bash
# Start / stop
sudo systemctl start pronote.timer
sudo systemctl stop pronote.timer

# Enable / disable on boot
sudo systemctl enable pronote.timer
sudo systemctl disable pronote.timer

# Run the job once immediately (outside the timer schedule)
sudo systemctl start pronote.service

# Reload after editing unit files
sudo systemctl daemon-reload

# Check timer schedule
systemctl list-timers pronote.timer

# View service definition
systemctl cat pronote.service
```
