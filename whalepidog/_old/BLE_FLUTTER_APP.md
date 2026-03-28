# Flutter BLE App Example for WhalePiDog

This document provides a complete example of a Flutter app that communicates with WhalePiDog via Bluetooth Low Energy (BLE).

## Overview

This Flutter app will:
- Scan for WhalePiDog devices
- Connect via BLE
- Send commands (status, summary, start, stop, etc.)
- Display real-time responses
- Work on both iOS and Android

## Project Setup

### 1. Create Flutter Project

```bash
flutter create whalepidog_app
cd whalepidog_app
```

### 2. Add Dependencies

Edit `pubspec.yaml`:

```yaml
dependencies:
  flutter:
    sdk: flutter
  flutter_blue_plus: ^1.14.0
  permission_handler: ^11.0.0
```

Then run:

```bash
flutter pub get
```

### 3. Configure Permissions

**iOS (ios/Runner/Info.plist):**

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>This app needs Bluetooth to communicate with WhalePiDog</string>
<key>NSBluetoothPeripheralUsageDescription</key>
<string>This app needs Bluetooth to communicate with WhalePiDog</string>
```

**Android (android/app/src/main/AndroidManifest.xml):**

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

## Complete App Code

### lib/main.dart

```dart
import 'package:flutter/material.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import 'package:permission_handler/permission_handler.dart';
import 'dart:convert';

void main() {
  runApp(const WhalePiDogApp());
}

class WhalePiDogApp extends StatelessWidget {
  const WhalePiDogApp({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'WhalePiDog Controller',
      theme: ThemeData(
        primarySwatch: Colors.blue,
        useMaterial3: true,
      ),
      home: const DeviceScanScreen(),
    );
  }
}

class DeviceScanScreen extends StatefulWidget {
  const DeviceScanScreen({Key? key}) : super(key: key);

  @override
  State<DeviceScanScreen> createState() => _DeviceScanScreenState();
}

class _DeviceScanScreenState extends State<DeviceScanScreen> {
  List<ScanResult> scanResults = [];
  bool isScanning = false;

  @override
  void initState() {
    super.initState();
    _requestPermissions();
  }

  Future<void> _requestPermissions() async {
    await Permission.bluetoothScan.request();
    await Permission.bluetoothConnect.request();
    await Permission.location.request();
  }

  void _startScan() async {
    setState(() {
      scanResults.clear();
      isScanning = true;
    });

    FlutterBluePlus.startScan(timeout: const Duration(seconds: 4));

    FlutterBluePlus.scanResults.listen((results) {
      setState(() {
        scanResults = results
            .where((r) => r.device.name.startsWith('whalepi'))
            .toList();
      });
    });

    await Future.delayed(const Duration(seconds: 4));
    FlutterBluePlus.stopScan();
    setState(() {
      isScanning = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('WhalePiDog Devices'),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16.0),
            child: ElevatedButton.icon(
              onPressed: isScanning ? null : _startScan,
              icon: const Icon(Icons.search),
              label: Text(isScanning ? 'Scanning...' : 'Scan for Devices'),
            ),
          ),
          Expanded(
            child: scanResults.isEmpty
                ? const Center(
                    child: Text('No WhalePiDog devices found.\nTap "Scan" to search.'),
                  )
                : ListView.builder(
                    itemCount: scanResults.length,
                    itemBuilder: (context, index) {
                      final result = scanResults[index];
                      return ListTile(
                        leading: const Icon(Icons.bluetooth),
                        title: Text(result.device.name),
                        subtitle: Text(result.device.id.toString()),
                        trailing: Text('${result.rssi} dBm'),
                        onTap: () {
                          Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (context) =>
                                  DeviceControlScreen(device: result.device),
                            ),
                          );
                        },
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}

class DeviceControlScreen extends StatefulWidget {
  final BluetoothDevice device;

  const DeviceControlScreen({Key? key, required this.device}) : super(key: key);

  @override
  State<DeviceControlScreen> createState() => _DeviceControlScreenState();
}

class _DeviceControlScreenState extends State<DeviceControlScreen> {
  // Nordic UART Service UUIDs
  static final serviceUuid = Guid("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
  static final rxCharUuid = Guid("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
  static final txCharUuid = Guid("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");

  BluetoothCharacteristic? rxCharacteristic;
  BluetoothCharacteristic? txCharacteristic;

  bool isConnected = false;
  bool isConnecting = false;
  String statusText = 'Disconnected';
  List<String> logMessages = [];
  final TextEditingController _commandController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _connectToDevice();
  }

  Future<void> _connectToDevice() async {
    setState(() {
      isConnecting = true;
      statusText = 'Connecting...';
    });

    try {
      await widget.device.connect(timeout: const Duration(seconds: 15));
      _log('Connected to ${widget.device.name}');

      List<BluetoothService> services = await widget.device.discoverServices();
      _log('Discovered ${services.length} services');

      for (BluetoothService service in services) {
        if (service.uuid == serviceUuid) {
          _log('Found Nordic UART Service');

          for (BluetoothCharacteristic characteristic in service.characteristics) {
            if (characteristic.uuid == rxCharUuid) {
              rxCharacteristic = characteristic;
              _log('Found RX characteristic');
            } else if (characteristic.uuid == txCharUuid) {
              txCharacteristic = characteristic;
              _log('Found TX characteristic');

              // Subscribe to notifications
              await characteristic.setNotifyValue(true);
              characteristic.value.listen((value) {
                String response = String.fromCharCodes(value);
                _log('← $response');
              });
            }
          }
        }
      }

      if (rxCharacteristic != null && txCharacteristic != null) {
        setState(() {
          isConnected = true;
          statusText = 'Connected';
        });
        _log('Ready to send commands');
      } else {
        _log('ERROR: Could not find Nordic UART characteristics');
      }
    } catch (e) {
      _log('Connection error: $e');
      setState(() {
        statusText = 'Connection failed';
      });
    } finally {
      setState(() {
        isConnecting = false;
      });
    }
  }

  Future<void> _sendCommand(String command) async {
    if (!isConnected || rxCharacteristic == null) {
      _log('ERROR: Not connected');
      return;
    }

    try {
      _log('→ $command');
      await rxCharacteristic!.write(utf8.encode(command + '\n'));
    } catch (e) {
      _log('ERROR sending command: $e');
    }
  }

  void _log(String message) {
    setState(() {
      logMessages.insert(0, '${DateTime.now().toString().substring(11, 19)} $message');
      if (logMessages.length > 100) {
        logMessages.removeLast();
      }
    });
  }

  @override
  void dispose() {
    widget.device.disconnect();
    _commandController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.device.name),
        actions: [
          Padding(
            padding: const EdgeInsets.all(8.0),
            child: Chip(
              avatar: Icon(
                isConnected ? Icons.bluetooth_connected : Icons.bluetooth_disabled,
                color: Colors.white,
              ),
              label: Text(statusText),
              backgroundColor: isConnected ? Colors.green : Colors.grey,
            ),
          ),
        ],
      ),
      body: Column(
        children: [
          // Quick command buttons
          Padding(
            padding: const EdgeInsets.all(8.0),
            child: Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                ElevatedButton.icon(
                  onPressed: isConnected ? () => _sendCommand('status') : null,
                  icon: const Icon(Icons.info),
                  label: const Text('Status'),
                ),
                ElevatedButton.icon(
                  onPressed: isConnected ? () => _sendCommand('summary') : null,
                  icon: const Icon(Icons.summarize),
                  label: const Text('Summary'),
                ),
                ElevatedButton.icon(
                  onPressed: isConnected ? () => _sendCommand('start') : null,
                  icon: const Icon(Icons.play_arrow),
                  label: const Text('Start'),
                  style: ElevatedButton.styleFrom(backgroundColor: Colors.green),
                ),
                ElevatedButton.icon(
                  onPressed: isConnected ? () => _sendCommand('stop') : null,
                  icon: const Icon(Icons.stop),
                  label: const Text('Stop'),
                  style: ElevatedButton.styleFrom(backgroundColor: Colors.orange),
                ),
                ElevatedButton.icon(
                  onPressed: isConnected ? () => _sendCommand('restart') : null,
                  icon: const Icon(Icons.refresh),
                  label: const Text('Restart'),
                ),
              ],
            ),
          ),

          const Divider(),

          // Custom command input
          Padding(
            padding: const EdgeInsets.all(8.0),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _commandController,
                    decoration: const InputDecoration(
                      labelText: 'Custom Command',
                      border: OutlineInputBorder(),
                    ),
                    onSubmitted: isConnected
                        ? (value) {
                            if (value.isNotEmpty) {
                              _sendCommand(value);
                              _commandController.clear();
                            }
                          }
                        : null,
                  ),
                ),
                const SizedBox(width: 8),
                IconButton(
                  onPressed: isConnected
                      ? () {
                          if (_commandController.text.isNotEmpty) {
                            _sendCommand(_commandController.text);
                            _commandController.clear();
                          }
                        }
                      : null,
                  icon: const Icon(Icons.send),
                ),
              ],
            ),
          ),

          const Divider(),

          // Log messages
          Expanded(
            child: Container(
              color: Colors.black87,
              child: ListView.builder(
                reverse: true,
                itemCount: logMessages.length,
                itemBuilder: (context, index) {
                  return Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8.0,
                      vertical: 2.0,
                    ),
                    child: Text(
                      logMessages[index],
                      style: const TextStyle(
                        color: Colors.greenAccent,
                        fontFamily: 'monospace',
                        fontSize: 12,
                      ),
                    ),
                  );
                },
              ),
            ),
          ),
        ],
      ),
    );
  }
}
```

## Running the App

### iOS

```bash
cd ios
pod install
cd ..
flutter run
```

### Android

```bash
flutter run
```

## Available Commands

Send these commands to WhalePiDog:

- `status` - Get current watchdog status
- `summary` - Get PAMGuard summary
- `start` - Start PAMGuard data acquisition
- `stop` - Stop data acquisition
- `restart` - Restart PAMGuard
- `exit` - Stop watchdog and exit PAMGuard
- `ping` - Test connection

## Testing

1. **Enable BLE mode** on your Raspberry Pi (see BLE_SETUP.md)
2. **Start WhalePiDog** on the Raspberry Pi
3. **Launch the Flutter app** on your phone
4. **Tap "Scan"** to find nearby WhalePiDog devices
5. **Select your device** from the list
6. **Send commands** using the buttons or custom input

## Troubleshooting

### iOS Connection Issues

- Ensure Bluetooth permissions are granted in Settings
- Check that the device name matches (starts with "whalepi")
- Try restarting Bluetooth on the phone

### Android Connection Issues

- Enable Location services (required for BLE scanning on Android)
- Grant all requested permissions
- Check for conflicts with other BLE apps

### No Responses

- Check that notifications are enabled on TX characteristic
- Verify the WhalePiDog server is running in BLE mode
- Check Python BLE server logs on Raspberry Pi

## Advanced Features

### Adding Auto-Reconnect

```dart
// In DeviceControlScreen
StreamSubscription<BluetoothDeviceState>? _stateSubscription;

@override
void initState() {
  super.initState();
  _stateSubscription = widget.device.state.listen((state) {
    if (state == BluetoothDeviceState.disconnected) {
      _log('Disconnected - attempting to reconnect...');
      Future.delayed(Duration(seconds: 2), () => _connectToDevice());
    }
  });
  _connectToDevice();
}

@override
void dispose() {
  _stateSubscription?.cancel();
  super.dispose();
}
```

### Adding Response Parsing

```dart
void _handleResponse(String response) {
  if (response.startsWith('ACK:')) {
    _log('Command acknowledged');
  } else if (response.startsWith('RPLY:')) {
    String data = response.substring(5).trim();
    // Parse and display the response data
    _showResponseDialog(data);
  }
}
```

## Next Steps

- Add authentication/pairing support
- Implement data visualization
- Add file transfer capabilities
- Create widgets for real-time monitoring
- Add support for multiple simultaneous connections
