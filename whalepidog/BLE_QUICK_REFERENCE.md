# BLE Quick Reference

## UUIDs

```
Service:  6E400001-B5A3-F393-E0A9-E50E24DCCA9E (Nordic UART Service)
RX Char:  6E400002-B5A3-F393-E0A9-E50E24DCCA9E (Write to send commands)
TX Char:  6E400003-B5A3-F393-E0A9-E50E24DCCA9E (Notify to receive responses)
```

## Configuration

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

## Installation

```bash
# Python dependencies
sudo apt-get install python3-dbus python3-gi
pip3 install bluezero

# Start WhalePiDog
java -jar whalepidog.jar
```

## Flutter Integration

```dart
// Dependencies
dependencies:
  flutter_blue_plus: ^1.14.0

// UUIDs
final serviceUuid = Guid("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
final rxCharUuid = Guid("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
final txCharUuid = Guid("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");

// Scan
FlutterBluePlus.startScan(timeout: Duration(seconds: 4));

// Connect
await device.connect();
List<BluetoothService> services = await device.discoverServices();

// Find characteristics
var rxChar = service.characteristics.firstWhere((c) => c.uuid == rxCharUuid);
var txChar = service.characteristics.firstWhere((c) => c.uuid == txCharUuid);

// Subscribe to notifications
await txChar.setNotifyValue(true);
txChar.value.listen((value) {
  String response = String.fromCharCodes(value);
  print('Received: $response');
});

// Send command
await rxChar.write(utf8.encode('status\n'));
```

## Commands

```
status     - Get watchdog status
summary    - Get PAMGuard summary
start      - Start data acquisition
stop       - Stop data acquisition
restart    - Restart PAMGuard
exit       - Stop watchdog
ping       - Test connection
```

## Response Format

```
ACK: <command>           - Command acknowledged
RPLY: <response data>    - Response data
```

## Troubleshooting

```bash
# Check Python
python3 --version
pip3 show bluezero

# Check BlueZ
bluetoothctl --version

# Enable Bluetooth
sudo bluetoothctl
> power on
> discoverable on
> pairable on

# View logs
java -jar whalepidog.jar 2>&1 | tee whalepidog.log
```

## Common Issues

**"bluezero not found"**
```bash
pip3 install bluezero
```

**"Permission denied"**
```bash
sudo usermod -a -G bluetooth $USER
# Log out and back in
```

**"Device not discoverable"**
```bash
sudo hciconfig hci0 piscan
```

## Testing with nRF Connect

1. Install "nRF Connect" app (iOS/Android)
2. Scan for "whalepi" device
3. Connect
4. Find service `6E40...`
5. Enable notifications on TX characteristic
6. Write to RX characteristic: `status`
7. Read response from TX notifications

## Documentation

- [BLE_SETUP.md](BLE_SETUP.md) - Complete setup guide
- [BLE_FLUTTER_APP.md](BLE_FLUTTER_APP.md) - Flutter app example
- [BLUETOOTH_MODE_COMPARISON.md](BLUETOOTH_MODE_COMPARISON.md) - Serial vs BLE

## Support

For issues, check verbose logs:
```bash
# Enable verbose in settings
"verbose": true

# Run and save logs
java -jar whalepidog.jar 2>&1 | tee debug.log
```
