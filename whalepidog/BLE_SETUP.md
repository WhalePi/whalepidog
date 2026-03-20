# Bluetooth Low Energy (BLE) Setup Guide

This guide explains how to set up and use Bluetooth Low Energy (BLE) mode in WhalePiDog, which enables cross-platform app communication with both iOS and Android devices.

## Overview

WhalePiDog now supports two Bluetooth modes:

1. **Serial Bluetooth (SPP)** - Legacy mode, works with Serial Bluetooth Terminal apps
2. **BLE (Bluetooth Low Energy)** - Modern mode, compatible with iOS and Android Flutter apps

## Why BLE?

- **iOS Compatibility**: Apple devices don't support Serial Bluetooth (SPP) in third-party apps
- **Cross-Platform**: Works with both iOS and Android using the same protocol
- **Lower Power**: BLE is more energy-efficient
- **Modern**: Better supported by current BLE libraries (e.g., Flutter's `flutter_blue_plus`)

## Requirements

### System Requirements

- Raspberry Pi with Bluetooth hardware
- Linux with BlueZ 5.43 or later
- Python 3.7 or later

### Python Dependencies

Install the required Python packages:

```bash
# Install system dependencies
sudo apt-get update
sudo apt-get install python3-dbus python3-gi

# Install bluezero library
pip3 install bluezero
```

Or install bluezero manually:

```bash
git clone https://github.com/ukBaz/python-bluezero.git
cd python-bluezero
sudo python3 setup.py install
```

## Configuration

### Settings File

Edit your `whalepidog_settings.json` file to enable BLE mode:

```json
{
  "bluetoothSettings": {
    "bluetoothEnabled": true,
    "bluetoothMode": "BLE",
    "bluetoothPairing": true,
    "identification": "X12",
    "verbose": true
  }
}
```

**Settings explained:**

- `bluetoothEnabled`: Set to `true` to enable Bluetooth
- `bluetoothMode`: Set to `"BLE"` for iOS/Android compatibility, or `"SERIAL"` for legacy mode
- `bluetoothPairing`: Set to `true` to enable pairing mode on startup
- `identification`: Optional tag appended to device name (appears as `whalepi_X12`)
- `verbose`: Enable detailed Bluetooth logging

### Bluetooth Permissions

Ensure the user running WhalePiDog has Bluetooth permissions:

```bash
# Add user to bluetooth group
sudo usermod -a -G bluetooth $USER

# Log out and back in for changes to take effect
```

## BLE Service Details

WhalePiDog implements the **Nordic UART Service (NUS)**, a widely-supported BLE UART profile.

### Service UUID
`6E400001-B5A3-F393-E0A9-E50E24DCCA9E`

### Characteristics

**RX Characteristic (Write):**
- UUID: `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
- Properties: Write, Write Without Response
- Use: Client writes commands here

**TX Characteristic (Notify):**
- UUID: `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`
- Properties: Notify, Read
- Use: Server sends responses via notifications

## Using BLE Mode

### Starting WhalePiDog

```bash
java -jar whalepidog.jar
```

You should see log messages indicating BLE mode:

```
[WhalePIDog] Starting Bluetooth in BLE mode (iOS/Android compatible)
[BLE] Starting Python BLE peripheral server...
[BLE] Device name: whalepi_X12
[BLE] BLE peripheral 'whalepi_X12' is now advertising
```

### Connecting from a Mobile App

1. **Scan for devices**: Look for `whalepi` or `whalepi_<id>` in your BLE scanner
2. **Connect**: Select the device to establish a connection
3. **Discover services**: Find service UUID `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
4. **Enable notifications**: Subscribe to TX characteristic for responses
5. **Send commands**: Write to RX characteristic

### Protocol

**Sending a command:**
Write UTF-8 text to RX characteristic: `status`

**Receiving responses:**
- ACK: `ACK: status\n`
- Reply: `RPLY: <response data>\n`

## Flutter App Integration

### Dependencies

Add to your `pubspec.yaml`:

```yaml
dependencies:
  flutter_blue_plus: ^1.14.0
```

### Example Code

```dart
import 'package:flutter_blue_plus/flutter_blue_plus.dart';

// Service and characteristic UUIDs
final serviceUuid = Guid("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
final rxCharUuid = Guid("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
final txCharUuid = Guid("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");

// Scan for device
FlutterBluePlus.startScan(timeout: Duration(seconds: 4));
FlutterBluePlus.scanResults.listen((results) {
  for (ScanResult r in results) {
    if (r.device.name.startsWith('whalepi')) {
      // Found it!
      connectToDevice(r.device);
    }
  }
});

// Connect and setup
Future<void> connectToDevice(BluetoothDevice device) async {
  await device.connect();
  List<BluetoothService> services = await device.discoverServices();
  
  for (BluetoothService service in services) {
    if (service.uuid == serviceUuid) {
      var rxChar = service.characteristics.firstWhere(
        (c) => c.uuid == rxCharUuid
      );
      var txChar = service.characteristics.firstWhere(
        (c) => c.uuid == txCharUuid
      );
      
      // Subscribe to notifications
      await txChar.setNotifyValue(true);
      txChar.value.listen((value) {
        String response = String.fromCharCodes(value);
        print('Received: $response');
      });
      
      // Send command
      await rxChar.write(utf8.encode('status\n'));
    }
  }
}
```

## Troubleshooting

### BLE Server Won't Start

**Check Python installation:**
```bash
python3 --version  # Should be 3.7 or later
pip3 show bluezero
```

**Check BlueZ version:**
```bash
bluetoothctl --version  # Should be 5.43 or later
```

### Permission Denied

```bash
# Ensure Bluetooth permissions
sudo setcap 'cap_net_raw,cap_net_admin+eip' $(which python3)
```

### Device Not Discoverable

```bash
# Manually enable discoverable mode
sudo bluetoothctl
> power on
> discoverable on
> pairable on
```

### Check Logs

Enable verbose mode in settings and check the logs:

```bash
java -jar whalepidog.jar 2>&1 | tee whalepidog.log
```

## Switching Between BLE and Serial Mode

To switch back to Serial Bluetooth mode, change your settings:

```json
{
  "bluetoothSettings": {
    "bluetoothEnabled": true,
    "bluetoothMode": "SERIAL",
    ...
  }
}
```

Both modes can work with the same WhalePiDog commands - only the transport layer changes.

## Next Steps

- See [BLE_FLUTTER_APP.md](BLE_FLUTTER_APP.md) for a complete Flutter app example
- See [COMMANDS.md](COMMANDS.md) for available PAMGuard commands
- See [TROUBLESHOOTING.md](TROUBLESHOOTING.md) for common issues
