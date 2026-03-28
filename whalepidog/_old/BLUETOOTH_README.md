# Bluetooth Integration for WhalePIDog

> **📱 iOS/Android Support Available!**  
> This document covers legacy **Serial Bluetooth** mode. For iOS compatibility and cross-platform Flutter apps, see [BLE_SETUP.md](BLE_SETUP.md) for the new **BLE mode**.  
> Compare modes: [BLUETOOTH_MODE_COMPARISON.md](BLUETOOTH_MODE_COMPARISON.md)

This document describes the Bluetooth functionality integrated into WhalePIDog, allowing remote command and control via Bluetooth serial communication.

## Overview

The Bluetooth integration allows you to send commands to PAMGuard through WhalePIDog using a Bluetooth connection from a phone, tablet, or computer. Commands sent via Bluetooth are processed exactly the same way as terminal or UDP commands.

## Architecture

The Bluetooth implementation consists of three main components:

1. **BluetoothSettings** (`whalepidog.bluetooth.BluetoothSettings`)
   - Configuration for Bluetooth functionality
   - Controls whether Bluetooth is enabled, pairing mode, and logging verbosity

2. **BluetoothCommands** (`whalepidog.bluetooth.BluetoothCommands`)
   - Manages the Bluetooth serial connection
   - Runs a background server that listens for incoming connections
   - Forwards received commands to the WatchdogController

3. **WatchdogController Integration**
   - Initializes and manages the Bluetooth server
   - Routes commands to PAMGuard via UDP
   - Updates deploy settings when start/stop commands are received

## Configuration

Add the following to your `whalepidog_settings.json` file:

```json
{
  "bluetoothSettings": {
    "bluetoothEnabled": true,
    "bluetoothPairing": true,
    "verbose": false
  }
}
```

### Settings Description

- **bluetoothEnabled**: Set to `true` to enable Bluetooth functionality (default: `false`)
- **bluetoothPairing**: Set to `true` to enable discoverable mode for pairing new devices (default: `true`)
- **verbose**: Set to `true` to enable detailed Bluetooth logging (default: `false`)

## Requirements

### Hardware
- Raspberry Pi (or any Linux device) with Bluetooth hardware
- Bluetooth must be enabled on the system

### Software
- BlueZ Bluetooth stack (typically pre-installed on Raspberry Pi OS)
- `bluetoothctl` command-line tool
- `rfcomm` utility
- `sdptool` utility
- Appropriate sudo permissions to run Bluetooth commands

### Dependencies
- jSerialComm library (automatically included via Maven)

## How It Works

1. When WhalePIDog starts with Bluetooth enabled, it:
   - Powers on the Bluetooth adapter
   - Enables pairing mode (if configured)
   - Registers a Serial Port Profile (SPP) service
   - Starts an `rfcomm` listener on channel 1

2. The Bluetooth server runs in a background thread and:
   - Waits for devices to connect on `/dev/rfcomm0`
   - Reads commands line-by-line from the connected device
   - Forwards each command to the WatchdogController
   - Sends acknowledgments and responses back to the device

3. When a command is received:
   - It's validated against known PAMGuard commands
   - Sent to PAMGuard via UDP
   - The response is returned to the Bluetooth device
   - Special handling for `start`/`stop` commands updates the deploy flag

## Supported Commands

All PAMGuard UDP commands are supported:

- `ping` - Check if PAMGuard is responding
- `Status` - Get current PAMGuard status (0=IDLE, 1=RUNNING, etc.)
- `summary` - Get detailed status summary
- `diagnostics` - Get diagnostic information
- `start` - Start PAMGuard data acquisition
- `stop` - Stop PAMGuard data acquisition
- `Exit` - Gracefully exit PAMGuard
- `kill` - Force-kill PAMGuard

## Pairing a Device

### From a Phone/Tablet (Android)

1. Enable Bluetooth on your device
2. Make sure WhalePIDog is running with `bluetoothPairing: true`
3. Search for available Bluetooth devices
4. Look for your Raspberry Pi's Bluetooth name
5. Pair with the device (PIN may be required - typically `0000` or `1234`)
6. Use a Bluetooth serial terminal app to connect
   - Recommended apps: "Serial Bluetooth Terminal", "BlueTerm"
   - Connect to the "Serial Port" service

### From a Computer (Linux)

```bash
# Scan for devices
bluetoothctl scan on

# Pair with the Pi (replace XX:XX:XX:XX:XX:XX with the Pi's MAC address)
bluetoothctl pair XX:XX:XX:XX:XX:XX

# Connect
bluetoothctl connect XX:XX:XX:XX:XX:XX

# Bind to rfcomm
sudo rfcomm bind 0 XX:XX:XX:XX:XX:XX 1

# Connect using a serial terminal
screen /dev/rfcomm0 115200
```

## Example Usage

Once connected via Bluetooth terminal:

```
> ping
ACK: ping
RPLY: ping

> Status
ACK: Status
RPLY: Status 1

> start
ACK: start
RPLY: start

> summary
ACK: summary
RPLY: [detailed summary text...]
```

## Troubleshooting

### Bluetooth service won't start
- Check that Bluetooth is enabled: `sudo systemctl status bluetooth`
- Ensure BlueZ is installed: `sudo apt-get install bluez`
- Check for permission issues: `sudo chmod 777 /var/run/sdp`

### Device won't pair
- Enable pairing mode: Set `bluetoothPairing: true` in settings
- Check discoverable status: `bluetoothctl show`
- Remove old pairings: `bluetoothctl remove XX:XX:XX:XX:XX:XX`

### Connection drops immediately
- Check `/dev/rfcomm0` exists when connected
- Verify `rfcomm watch` is running: `ps aux | grep rfcomm`
- Check system logs: `sudo journalctl -u bluetooth -f`

### Commands not working
- Verify PAMGuard is running and responding to UDP
- Check UDP port matches settings (default: 8000)
- Enable verbose logging: Set `verbose: true` in Bluetooth settings
- Check WhalePIDog logs for error messages

## Security Considerations

- Bluetooth connections are typically encrypted after pairing
- Consider disabling pairing mode after initial setup (`bluetoothPairing: false`)
- Only trusted devices should be paired
- Commands sent via Bluetooth have full control over PAMGuard
- The Bluetooth server requires sudo privileges for setup

## Implementation Notes

- The Bluetooth server runs on a separate daemon thread
- Connection/disconnection is handled automatically
- Multiple connection attempts are supported (reconnection after disconnect)
- The implementation is Linux-specific (uses `rfcomm` and `bluetoothctl`)
- Heartbeat messages are sent periodically to detect connection loss
- All received messages are logged (if verbose mode is enabled)

## Comparison with PamDog

This implementation is based on the `BluetoothCommands` class from PamDog but adapted for WhalePIDog's architecture:

### Similarities
- Uses jSerialComm library for serial communication
- Monitors `/dev/rfcomm0` for connections
- Sends acknowledgments and replies via Bluetooth
- Supports the same Bluetooth setup commands

### Differences
- Integrated directly into WatchdogController instead of DogCommandManager
- Uses WhalePIDog's UDP communication layer
- Leverages WhalePIDog's settings persistence
- Simplified command processing (no separate command adapter pattern)
- Uses Consumer-based listeners instead of Swing events

## Future Enhancements

Potential improvements for future versions:

- Support for custom Bluetooth device names
- Configurable rfcomm channel
- Multiple simultaneous Bluetooth connections
- Bluetooth status in terminal UI
- Authentication/authorization for commands
- Command history via Bluetooth
- Binary data transfer support
