# Bluetooth Quick Start Guide

## Enable Bluetooth in WhalePIDog

1. **Edit your settings file** (`whalepidog_settings.json`):
   ```json
   {
     "bluetoothSettings": {
       "bluetoothEnabled": true,
       "bluetoothPairing": true,
       "verbose": false
     }
   }
   ```

2. **Start WhalePIDog**:
   ```bash
   java -jar whalepidog.jar whalepidog_settings.json
   ```

3. **Check Bluetooth status** in the Summary View:
   - Look for "BT:" in the banner
   - Green dot (●) = connected
   - Gray dot (○) = disconnected

## Connect from Android Phone/Tablet

1. **Install a Bluetooth Serial Terminal app**:
   - "Serial Bluetooth Terminal" (recommended)
   - "BlueTerm"
   - Any app supporting SPP (Serial Port Profile)

2. **Pair with your Raspberry Pi**:
   - Open Android Bluetooth settings
   - Scan for devices
   - Select your Pi (look for its hostname)
   - Pair (PIN is usually `0000` or `1234`)

3. **Connect via the terminal app**:
   - Open the serial terminal app
   - Look for "Devices" or "Connect" menu
   - Select your paired Pi
   - Connect to the Serial Port service

4. **Send commands**:
   ```
   ping
   Status
   start
   summary
   stop
   ```

## Connect from Linux Computer

```bash
# 1. Pair with the Raspberry Pi
bluetoothctl scan on
# (find the Pi's MAC address, e.g., AA:BB:CC:DD:EE:FF)
bluetoothctl pair AA:BB:CC:DD:EE:FF
bluetoothctl trust AA:BB:CC:DD:EE:FF
bluetoothctl connect AA:BB:CC:DD:EE:FF

# 2. Bind to rfcomm
sudo rfcomm bind 0 AA:BB:CC:DD:EE:FF 1

# 3. Connect with a terminal program
screen /dev/rfcomm0 115200
# OR
minicom -D /dev/rfcomm0 -b 115200

# 4. Send commands
ping
Status
start
```

## Troubleshooting

### "Connection refused" or won't pair
- Make sure `bluetoothPairing: true` in settings
- Restart WhalePIDog
- Check Bluetooth is enabled: `sudo systemctl status bluetooth`

### Commands don't work
- Verify PAMGuard is running (check Summary View)
- Check UDP port is correct (default 8000)
- Enable verbose logging: `"verbose": true` in settings

### Connection drops immediately
- Check system logs: `sudo journalctl -u bluetooth -f`
- Verify `/dev/rfcomm0` exists when connected
- Make sure `rfcomm watch` is running: `ps aux | grep rfcomm`

## Supported Commands

All PAMGuard UDP commands:
- `ping` - Test connection
- `Status` - Get status (0=IDLE, 1=RUNNING, etc.)
- `summary` - Get detailed summary
- `diagnostics` - Get diagnostics
- `start` - Start data acquisition (sets deploy=true)
- `stop` - Stop data acquisition (sets deploy=false)
- `Exit` - Exit PAMGuard gracefully
- `kill` - Force kill PAMGuard

## Command Response Format

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
```

## Disable Bluetooth

Set in `whalepidog_settings.json`:
```json
{
  "bluetoothSettings": {
    "bluetoothEnabled": false
  }
}
```

Restart WhalePIDog for changes to take effect.

---

For more details, see `BLUETOOTH_README.md`
