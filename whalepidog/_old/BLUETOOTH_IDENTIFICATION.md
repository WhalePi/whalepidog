# Bluetooth Identification Feature

## Overview
Added an `identification` field to both WhalePIDog and PamDog Bluetooth settings. This allows you to customize the Bluetooth device name that appears when pairing from a phone or other device.

## How It Works

### WhalePIDog
When you set an identification in `whalepidog_settings.json`, the Bluetooth device name will be formatted as `whalepi_<identification>`.

**Example:**
```json
{
  "bluetoothSettings": {
    "bluetoothEnabled": true,
    "bluetoothPairing": true,
    "verbose": false,
    "identification": "X12"
  }
}
```

With this configuration, when you search for Bluetooth devices on your phone, you will see:
- **Device Name:** `whalepi_X12`

If the identification field is empty or not set, the device name will be `whalepi`.

### PamDog
Similarly, for PamDog, the device name will be formatted as `pamdog_<identification>`.

**Example:**
If you set `identification = "X12"` in PamDog settings, the device will appear as `pamdog_X12`.

## Configuration Files Updated

### WhalePIDog
1. **BluetoothSettings.java** - Added `identification` field with getter/setter
2. **BluetoothCommands.java** - Modified `setupBluetooth()` to set the device name using `bluetoothctl system-alias`
3. **whalepidog_settings_example.json** - Added example identification field
4. **whalepidog_settings.json** - Added bluetoothSettings section with identification field

### PamDog
1. **PamBluetoothSettings.java** - Added `identification` field
2. **BluetoothCommands.java** - Added `setupBluetoothWithName()` method to set the device name

## Technical Details

The device name is set using the Linux `bluetoothctl` command:
```bash
sudo bluetoothctl system-alias <device_name>
```

This command is executed during Bluetooth setup, before the device becomes discoverable.

## Usage Examples

### Use Case 1: Multiple Devices
If you have multiple WhalePIDog devices deployed, you can give each one a unique identifier:
- Device 1: `identification: "A1"` → appears as `whalepi_A1`
- Device 2: `identification: "A2"` → appears as `whalepi_A2`
- Device 3: `identification: "B1"` → appears as `whalepi_B1`

### Use Case 2: Location-Based Naming
You can use location codes as identifiers:
- `identification: "NORTH"` → `whalepi_NORTH`
- `identification: "SOUTH"` → `whalepi_SOUTH`

### Use Case 3: Default Behavior
Leave the identification field empty for the default device name:
- `identification: ""` → `whalepi` (WhalePIDog) or `pamdog` (PamDog)

## Notes
- The identification string is trimmed of leading/trailing whitespace
- Special characters in the identification may need to be compatible with Bluetooth naming conventions
- The device name change takes effect when the Bluetooth server starts
