sudo bash install_whalepidog_service.sh
#!/bin/bash
# Install script to start whalepidog_pizero_tmux.sh on boot via systemd
# Run this script with sudo: sudo bash install_whalepidog_service.sh

SERVICE_NAME="whalepidog"
SCRIPT_PATH="/home/whalepi/pamguard_pizero/whalepidog_pizero_tmux.sh"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
RUN_USER="whalepi"

# Check for root
if [ "$EUID" -ne 0 ]; then
    echo "Please run as root: sudo bash $0"
    exit 1
fi

# Check that the target script exists
if [ ! -f "$SCRIPT_PATH" ]; then
    echo "WARNING: $SCRIPT_PATH not found. The service will be installed but won't work until the script is in place."
fi

# Ensure the script is executable
chmod +x "$SCRIPT_PATH" 2>/dev/null

echo "Creating systemd service at $SERVICE_FILE ..."

cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=WhalePiDog Watchdog (tmux)
After=network.target

[Service]
Type=forking
User=${RUN_USER}
WorkingDirectory=/home/${RUN_USER}/pamguard_pizero
ExecStart=${SCRIPT_PATH}
ExecStop=/usr/bin/tmux kill-session -t pamguard
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
EOF

echo "Reloading systemd daemon ..."
systemctl daemon-reload

echo "Enabling ${SERVICE_NAME} service to start on boot ..."
systemctl enable "${SERVICE_NAME}.service"

echo ""
echo "Installation complete. The whalepidog watchdog will start automatically on boot."
echo ""
echo "Useful commands:"
echo "  sudo systemctl start ${SERVICE_NAME}    # Start now"
echo "  sudo systemctl stop ${SERVICE_NAME}     # Stop"
echo "  sudo systemctl status ${SERVICE_NAME}   # Check status"
echo "  sudo systemctl disable ${SERVICE_NAME}  # Disable auto-start"
echo "  tmux attach -t pamguard                 # Attach to the tmux session"
