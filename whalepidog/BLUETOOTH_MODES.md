# WhalePiDog Bluetooth Modes

## Quick Start

WhalePiDog supports two Bluetooth modes:

### 🆕 BLE Mode (Recommended)
**Best for:** iOS and Android apps, Flutter development, modern devices

```bash
# 1. Install dependencies
sudo bash install_ble_deps.sh

# 2. Configure (edit whalepidog_settings.json)
{
  "bluetoothSettings": {
    "bluetoothEnabled": true,
    "bluetoothMode": "BLE"
  }
}

# 3. Run
java -jar whalepidog.jar
```

📖 **Full Guide:** [BLE_SETUP.md](BLE_SETUP.md)  
📱 **Flutter App:** [BLE_FLUTTER_APP.md](BLE_FLUTTER_APP.md)  
⚡ **Quick Ref:** [BLE_QUICK_REFERENCE.md](BLE_QUICK_REFERENCE.md)

---

### 📟 Serial Mode (Legacy)
**Best for:** Android-only, Serial Bluetooth Terminal apps

```bash
# Configure (edit whalepidog_settings.json)
{
  "bluetoothSettings": {
    "bluetoothEnabled": true,
    "bluetoothMode": "SERIAL"
  }
}

# Run
java -jar whalepidog.jar
```

📖 **Full Guide:** [BLUETOOTH_README.md](BLUETOOTH_README.md)  
🔧 **Troubleshooting:** [BLUETOOTH_TROUBLESHOOTING.md](BLUETOOTH_TROUBLESHOOTING.md)

---

## Comparison

| Feature | Serial | BLE |
|---------|--------|-----|
| iOS | ❌ | ✅ |
| Android | ✅ | ✅ |
| Flutter | ⚠️ | ✅ |
| Setup | Easy | Moderate |

📊 **Detailed Comparison:** [BLUETOOTH_MODE_COMPARISON.md](BLUETOOTH_MODE_COMPARISON.md)

## Available Commands

Both modes support the same commands:

```
status    - Get watchdog status
summary   - Get PAMGuard summary  
start     - Start data acquisition
stop      - Stop data acquisition
restart   - Restart PAMGuard
exit      - Stop watchdog
ping      - Test connection
```

## Need Help?

- **BLE Setup Issues?** → [BLE_SETUP.md](BLE_SETUP.md#troubleshooting)
- **Serial Bluetooth Issues?** → [BLUETOOTH_TROUBLESHOOTING.md](BLUETOOTH_TROUBLESHOOTING.md)
- **Want to build an app?** → [BLE_FLUTTER_APP.md](BLE_FLUTTER_APP.md)
- **Quick reference?** → [BLE_QUICK_REFERENCE.md](BLE_QUICK_REFERENCE.md)

## Documentation Index

### BLE Mode (iOS/Android)
- [BLE_SETUP.md](BLE_SETUP.md) - Complete setup guide
- [BLE_FLUTTER_APP.md](BLE_FLUTTER_APP.md) - Flutter app example
- [BLE_QUICK_REFERENCE.md](BLE_QUICK_REFERENCE.md) - Quick reference
- [BLE_IMPLEMENTATION_SUMMARY.md](BLE_IMPLEMENTATION_SUMMARY.md) - Technical details

### Serial Mode (Android)
- [BLUETOOTH_README.md](BLUETOOTH_README.md) - Complete guide
- [BLUETOOTH_QUICKSTART.md](BLUETOOTH_QUICKSTART.md) - Quick start
- [BLUETOOTH_TROUBLESHOOTING.md](BLUETOOTH_TROUBLESHOOTING.md) - Troubleshooting

### General
- [BLUETOOTH_MODE_COMPARISON.md](BLUETOOTH_MODE_COMPARISON.md) - Mode comparison
