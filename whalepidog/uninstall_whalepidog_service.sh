#!/bin/bash
# Uninstall script to remove the whalepidog systemd service.
# Run this script with sudo: sudo bash uninstall_whalepidog_service.sh

SERVICE_NAME="whalepidog"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"

# Check for root
if [ "$EUID" -ne 0 ]; then
    echo "Please run as root: sudo bash $0"
    exit 1
fi

# Check if the service file exists
if [ ! -f "$SERVICE_FILE" ]; then
    echo "Service file $SERVICE_FILE not found – nothing to uninstall."
    exit 0
fi

echo "Stopping ${SERVICE_NAME} service (if running) ..."
systemctl stop "${SERVICE_NAME}.service" 2>/dev/null

# Also kill any lingering tmux session used by the service
if tmux has-session -t pamguard 2>/dev/null; then
    echo "Killing tmux session 'pamguard' ..."
    tmux kill-session -t pamguard
fi

echo "Disabling ${SERVICE_NAME} service ..."
systemctl disable "${SERVICE_NAME}.service" 2>/dev/null

echo "Removing service file $SERVICE_FILE ..."
rm -f "$SERVICE_FILE"

echo "Reloading systemd daemon ..."
systemctl daemon-reload

echo "Resetting any failed state for ${SERVICE_NAME} ..."
systemctl reset-failed "${SERVICE_NAME}.service" 2>/dev/null

echo ""
echo "Uninstall complete. The whalepidog service has been removed."
echo "The whalepidog watchdog will no longer start automatically on boot."
echo ""
echo "Note: The whalepidog application files have NOT been deleted."
echo "      Only the systemd service has been removed."
