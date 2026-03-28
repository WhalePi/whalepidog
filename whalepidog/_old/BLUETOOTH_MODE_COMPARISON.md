# Bluetooth Mode Comparison

## Overview

WhalePiDog supports two Bluetooth modes for remote communication with PAMGuard.

| Feature | Serial Bluetooth (SPP) | Bluetooth Low Energy (BLE) |
|---------|----------------------|---------------------------|
| **iOS Support** | ❌ No | ✅ Yes |
| **Android Support** | ✅ Yes | ✅ Yes |
| **Flutter Compatible** | ⚠️ Limited | ✅ Yes |
| **Power Consumption** | Higher | Lower |
| **Range** | ~10m | ~10m |
| **Setup Complexity** | Simple | Moderate |
| **Required Apps** | Serial Bluetooth Terminal | Custom Flutter app |
| **Protocol** | RFCOMM/SPP | GATT |

## When to Use Each Mode

### Use Serial Bluetooth (SERIAL) when:
- ✅ You only need Android support
- ✅ You're using existing Serial Bluetooth Terminal apps
- ✅ You want simple, quick setup
- ✅ You don't need iOS compatibility

### Use BLE Mode when:
- ✅ You need iOS support
- ✅ You're building a custom Flutter app
- ✅ You want cross-platform compatibility
- ✅ You need lower power consumption

## Configuration

Both modes use the same configuration structure in `whalepidog_settings.json`:

### Serial Bluetooth Configuration

```json
{
  "bluetoothSettings": {
    "bluetoothEnabled": true,
    "bluetoothMode": "SERIAL",
    "bluetoothPairing": true,
    "identification": "boat1",
    "verbose": false
  }
}
```

### BLE Configuration

```json
{
  "bluetoothSettings": {
    "bluetoothEnabled": true,
    "bluetoothMode": "BLE",
    "bluetoothPairing": true,
    "identification": "boat1",
    "verbose": true
  }
}
```

## Technical Details

### Serial Bluetooth (SERIAL)

**Technology:** Bluetooth Classic with RFCOMM/SPP protocol

**Communication:**
- Uses `/dev/rfcomm0` serial port
- Direct serial communication
- Text-based command protocol

**Requirements:**
- BlueZ Bluetooth stack
- rfcomm utility
- jSerialComm library (included)

**Device Discovery:**
- Appears as "whalepi" or "whalepi_<id>"
- Standard Bluetooth pairing
- Serial port profile (SPP)

### Bluetooth Low Energy (BLE)

**Technology:** Bluetooth 4.0+ with GATT protocol

**Communication:**
- Uses Nordic UART Service (NUS)
- GATT characteristics for RX/TX
- Notification-based responses

**Requirements:**
- BlueZ 5.43+
- Python 3.7+
- bluezero library (`pip3 install bluezero`)

**Service Structure:**
```
Service: 6E400001-B5A3-F393-E0A9-E50E24DCCA9E
├─ RX Char: 6E400002-B5A3-F393-E0A9-E50E24DCCA9E (Write)
└─ TX Char: 6E400003-B5A3-F393-E0A9-E50E24DCCA9E (Notify)
```

## Command Protocol

Both modes use the **same command protocol** - only the transport changes.

### Sending Commands

**Input:** Plain text command
```
status
```

**Response Format:**
```
ACK: status
RPLY: <status data>
```

### Available Commands

Both modes support:
- `status` - Get watchdog status
- `summary` - Get PAMGuard summary
- `start` - Start data acquisition
- `stop` - Stop data acquisition  
- `restart` - Restart PAMGuard
- `exit` - Stop watchdog
- `ping` - Test connection

## Migration Guide

### From Serial to BLE

1. **Install Python dependencies:**
   ```bash
   pip3 install bluezero
   ```

2. **Update settings:**
   ```json
   "bluetoothMode": "BLE"
   ```

3. **Restart WhalePiDog:**
   ```bash
   java -jar whalepidog.jar
   ```

4. **Update your mobile app** to use BLE scanning (see BLE_FLUTTER_APP.md)

### From BLE to Serial

1. **Update settings:**
   ```json
   "bluetoothMode": "SERIAL"
   ```

2. **Restart WhalePiDog:**
   ```bash
   java -jar whalepidog.jar
   ```

3. **Use Serial Bluetooth Terminal** app instead

## Compatibility Notes

### Serial Bluetooth Limitations

- **iOS:** Not supported (Apple restricts SPP access)
- **Modern Android:** May require additional permissions
- **Flutter:** Limited library support

### BLE Limitations

- **Setup:** Requires Python dependencies
- **Debugging:** More complex than serial
- **Legacy devices:** Requires Bluetooth 4.0+

## Performance Comparison

| Metric | Serial | BLE |
|--------|--------|-----|
| Latency | ~50ms | ~100ms |
| Throughput | ~100 KB/s | ~20 KB/s |
| Power (idle) | ~50mA | ~10mA |
| Power (active) | ~100mA | ~30mA |
| Connection time | 2-5s | 3-8s |

## Recommendations

### For Production Deployments

**Recommended:** BLE mode
- Future-proof with iOS support
- Better power efficiency for battery operation
- Modern protocol with good library support

### For Testing/Development

**Recommended:** Serial mode
- Faster setup
- Easier debugging
- Works with common terminal apps

### For Mixed Environments

**Solution:** Keep both modes available
- Configure BLE as default
- Keep Serial as fallback
- Document both for users

## Getting Help

- **BLE Setup:** See [BLE_SETUP.md](BLE_SETUP.md)
- **Flutter App:** See [BLE_FLUTTER_APP.md](BLE_FLUTTER_APP.md)
- **Serial Bluetooth:** See [BLUETOOTH_QUICKSTART.md](BLUETOOTH_QUICKSTART.md)
- **Troubleshooting:** See [BLUETOOTH_TROUBLESHOOTING.md](BLUETOOTH_TROUBLESHOOTING.md)
