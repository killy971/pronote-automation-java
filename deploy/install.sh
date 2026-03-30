#!/bin/bash
# Install pronote-automation on a Raspberry Pi (Raspbian/systemd)
# Run as root: sudo bash deploy/install.sh

set -euo pipefail

APP_DIR=/opt/pronote
JAR_SRC="$(dirname "$0")/../build/libs/pronote-automation.jar"

# ---- Create system user ---------------------------------------------------
if ! id -u pronote &>/dev/null; then
    useradd --system --no-create-home --shell /usr/sbin/nologin pronote
    echo "Created system user: pronote"
fi

# ---- Create app directory -------------------------------------------------
mkdir -p "$APP_DIR/data"
install -m 644 "$JAR_SRC" "$APP_DIR/pronote-automation.jar"

# ---- Copy config if not already present ----------------------------------
if [ ! -f "$APP_DIR/config.yaml" ]; then
    install -m 600 "$(dirname "$0")/../config.yaml.example" "$APP_DIR/config.yaml"
    echo ""
    echo ">>> config.yaml copied to $APP_DIR/config.yaml"
    echo ">>> Edit it and fill in your credentials before starting the timers."
fi

# ---- Set permissions -------------------------------------------------------
chown -R pronote:pronote "$APP_DIR"
chmod 750 "$APP_DIR"
chmod 700 "$APP_DIR/data"

# ---- Remove old single-timer units if present ------------------------------
for old_unit in pronote.service pronote.timer; do
    if [ -f "/etc/systemd/system/$old_unit" ]; then
        systemctl disable "$old_unit" 2>/dev/null || true
        rm "/etc/systemd/system/$old_unit"
        echo "Removed obsolete unit: $old_unit"
    fi
done

# ---- Install systemd units -------------------------------------------------
install -m 644 "$(dirname "$0")/pronote-frequent.service" /etc/systemd/system/pronote-frequent.service
install -m 644 "$(dirname "$0")/pronote-frequent.timer"   /etc/systemd/system/pronote-frequent.timer
install -m 644 "$(dirname "$0")/pronote-school.service"   /etc/systemd/system/pronote-school.service
install -m 644 "$(dirname "$0")/pronote-school.timer"     /etc/systemd/system/pronote-school.timer

systemctl daemon-reload
systemctl enable pronote-frequent.timer pronote-school.timer

echo ""
echo "Installation complete."
echo ""
echo "Next steps:"
echo "  1. Edit /opt/pronote/config.yaml with your Pronote credentials"
echo "  2. Start the timers: sudo systemctl start pronote-frequent.timer pronote-school.timer"
echo "  3. Check status:     sudo systemctl list-timers 'pronote-*'"
echo "  4. View logs:        sudo journalctl -u pronote-frequent.service -f"
echo "                       sudo journalctl -u pronote-school.service -f"
