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
    echo ">>> Edit it and fill in your credentials before starting the timer."
fi

# ---- Set permissions -------------------------------------------------------
chown -R pronote:pronote "$APP_DIR"
chmod 750 "$APP_DIR"
chmod 700 "$APP_DIR/data"

# ---- Install systemd units -------------------------------------------------
install -m 644 "$(dirname "$0")/pronote.service" /etc/systemd/system/pronote.service
install -m 644 "$(dirname "$0")/pronote.timer"   /etc/systemd/system/pronote.timer

systemctl daemon-reload
systemctl enable pronote.timer

echo ""
echo "Installation complete."
echo ""
echo "Next steps:"
echo "  1. Edit /opt/pronote/config.yaml with your Pronote credentials"
echo "  2. Start the timer: sudo systemctl start pronote.timer"
echo "  3. Check status:    sudo systemctl status pronote.timer"
echo "  4. View logs:       sudo journalctl -u pronote.service -f"
