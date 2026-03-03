#!/bin/bash
#
# Install BLE dependencies for WhalePiDog
#
# This script installs the required Python packages for BLE mode.
# Run with: sudo bash install_ble_deps.sh
#

set -e

echo "======================================"
echo "WhalePiDog BLE Dependencies Installer"
echo "======================================"
echo ""

# Check if running as root
if [ "$EUID" -ne 0 ]; then
    echo "ERROR: This script must be run as root"
    echo "Please run: sudo bash install_ble_deps.sh"
    exit 1
fi

# Check OS
if [ ! -f /etc/os-release ]; then
    echo "ERROR: Cannot detect OS"
    exit 1
fi

source /etc/os-release

echo "Detected OS: $PRETTY_NAME"
echo ""

# Update package list
echo ">> Updating package list..."
apt-get update -qq

# Install system dependencies
echo ">> Installing system dependencies..."
apt-get install -y python3 python3-pip python3-dbus python3-gi

# Check BlueZ version
echo ">> Checking BlueZ version..."
if command -v bluetoothctl &> /dev/null; then
    BT_VERSION=$(bluetoothctl --version | grep -oP '\d+\.\d+' | head -1)
    echo "   BlueZ version: $BT_VERSION"
    
    # Check if version is >= 5.43
    REQUIRED_VERSION="5.43"
    if [ "$(printf '%s\n' "$REQUIRED_VERSION" "$BT_VERSION" | sort -V | head -n1)" = "$REQUIRED_VERSION" ]; then
        echo "   ✓ BlueZ version is sufficient"
    else
        echo "   ⚠ WARNING: BlueZ version $BT_VERSION may be too old (need 5.43+)"
        echo "   You may encounter issues with BLE peripheral mode"
    fi
else
    echo "   ✗ ERROR: bluetoothctl not found"
    echo "   Installing bluez..."
    apt-get install -y bluez
fi

# Install Python BLE library
echo ">> Installing Python BLE library (bluezero)..."
pip3 install --break-system-packages bluezero 2>/dev/null || pip3 install bluezero

# Add user to bluetooth group (if not already)
if [ -n "$SUDO_USER" ]; then
    echo ">> Adding $SUDO_USER to bluetooth group..."
    usermod -a -G bluetooth $SUDO_USER
    echo "   ✓ Done (you may need to log out and back in)"
fi

# Set Bluetooth capabilities for Python
echo ">> Setting Bluetooth capabilities for Python..."
PYTHON_PATH=$(which python3)
setcap 'cap_net_raw,cap_net_admin+eip' $PYTHON_PATH || {
    echo "   ⚠ WARNING: Could not set capabilities"
    echo "   You may need to run WhalePiDog as root for BLE to work"
}

# Enable and start Bluetooth service
echo ">> Enabling Bluetooth service..."
systemctl enable bluetooth
systemctl start bluetooth

echo ""
echo "======================================"
echo "Installation Complete!"
echo "======================================"
echo ""
echo "Next steps:"
echo "1. Log out and back in (if you were added to bluetooth group)"
echo "2. Edit whalepidog_settings.json:"
echo "   \"bluetoothEnabled\": true"
echo "   \"bluetoothMode\": \"BLE\""
echo "3. Run: java -jar whalepidog.jar"
echo ""
echo "For more information, see BLE_SETUP.md"
echo ""
