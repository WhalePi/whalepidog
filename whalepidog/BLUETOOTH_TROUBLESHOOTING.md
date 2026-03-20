# Bluetooth Troubleshooting Guide

## Issue: Commands Sent but Nothing Happens

If you can connect via Bluetooth but commands don't seem to work, follow these steps:

### Step 1: Enable Verbose Logging

Edit your `whalepidog_settings.json`:

```json
{
  "bluetoothSettings": {
    "bluetoothEnabled": true,
    "bluetoothPairing": true,
    "verbose": true
  }
}
```

Restart WhalePIDog and try sending commands again. Watch the log output.

### Step 2: Check the Logs

When you send a command (e.g., `ping`), you should see:

```
[Bluetooth] Received raw: 'ping' (bytes: 4)
[Bluetooth] Cleaned to: 'ping' (bytes: 4)
[Bluetooth] Processing command: 'ping' (length=4)
[Manual] >> ping  << ping
[Bluetooth] Response from PAMGuard: 'ping'
```

If you don't see this, the command isn't reaching WhalePIDog.

### Step 3: Verify PAMGuard is Running

Switch to Summary View (press `s`) and check:
- **Dog status**: Should be "RUNNING" (green)
- **PAM status**: Should show a status number (0=IDLE, 1=RUNNING)
- **Port**: Should match your settings (default 8000)

If PAMGuard isn't running, commands won't work.

### Step 4: Test with Terminal First

Before using Bluetooth, test that commands work via the terminal:
1. Press `:` to enter command mode
2. Type `ping` and press Enter
3. You should see a response

If terminal commands don't work, there's an issue with PAMGuard/UDP, not Bluetooth.

### Step 5: Check What's Actually Being Sent

The improved logging now shows:
- **Raw input**: What came from the Bluetooth terminal
- **Cleaned input**: After whitespace removal
- **Command length**: To spot hidden characters

Look for:
- Extra spaces or tabs
- Carriage returns (\\r) or line feeds (\\n)
- Non-printable characters

### Step 6: Common Issues

#### Issue: "Unknown command" message
**Symptom**: You see `Unknown command "xyz" – not sent`

**Cause**: The command isn't recognized

**Solution**: Use exact command names (case-insensitive):
- `ping` ✓
- `Status` ✓
- `summary` ✓
- `start` ✓
- `stop` ✓
- `Exit` ✓
- `kill` ✓
- `diagnostics` ✓

#### Issue: Command sent but no response
**Symptom**: You see "Processing command" but no response

**Cause**: PAMGuard isn't responding to UDP

**Solution**:
1. Check PAMGuard is running (Summary View)
2. Verify UDP port matches (default 8000)
3. Check PAMGuard's network module is configured
4. Try `ping` command first to test connectivity

#### Issue: Bluetooth disconnects immediately
**Symptom**: Connection drops right after connecting

**Cause**: rfcomm not running or port permissions

**Solution**:
```bash
# Check if rfcomm is running
ps aux | grep rfcomm

# If not, manually start it
sudo rfcomm watch hci0 1 &

# Check port exists and has correct permissions
ls -l /dev/rfcomm0
sudo chmod 666 /dev/rfcomm0
```

#### Issue: Can't pair device
**Symptom**: Phone can't find or pair with Pi

**Cause**: Pairing mode not enabled

**Solution**:
```bash
# Enable Bluetooth and make discoverable
sudo bluetoothctl power on
sudo bluetoothctl pairable on
sudo bluetoothctl discoverable on

# Or set in settings
"bluetoothPairing": true
```

### Step 7: Manual Testing

Try sending commands manually to PAMGuard via UDP:

```bash
# Test if PAMGuard responds to UDP
echo -n "ping" | nc -u -w 2 127.0.0.1 8000
```

You should get back "ping". If not, PAMGuard's UDP isn't working.

### Step 8: Phone Terminal App Settings

Different Bluetooth terminal apps have different settings. Make sure:

1. **Line ending**: Set to `\\n` (LF) or `\\r\\n` (CRLF)
2. **Character encoding**: UTF-8
3. **Echo**: Disabled (optional, for cleaner display)
4. **Auto-send**: Disabled (send on Enter/newline)

Recommended apps:
- **Android**: "Serial Bluetooth Terminal" by Kai Morich
  - Settings → Send → Newline = `\\n`
- **iOS**: "Bluetooth Terminal" or "BlueTerm"

### Step 9: Debug Output Example

**Good output (working)**:
```
[Bluetooth] Received raw: 'ping' (bytes: 4)
[Bluetooth] Cleaned to: 'ping' (bytes: 4)
[Bluetooth] Processing command: 'ping' (length=4)
[Manual] >> ping  << ping
[Bluetooth] Response from PAMGuard: 'ping'
```

**Bad output (unknown command)**:
```
[Bluetooth] Received raw: 'pong' (bytes: 4)
[Bluetooth] Cleaned to: 'pong' (bytes: 4)
[Bluetooth] Processing command: 'pong' (length=4)
[Manual] Unknown command "pong" – not sent. Known: ping, Status, summary, ...
[Bluetooth] Response from PAMGuard: 'Unknown command ...'
```

**Bad output (PAMGuard not responding)**:
```
[Bluetooth] Received raw: 'ping' (bytes: 4)
[Bluetooth] Cleaned to: 'ping' (bytes: 4)
[Bluetooth] Processing command: 'ping' (length=4)
[Manual] >> ping  << (no response / timeout)
[Bluetooth] Response from PAMGuard: null
```

### Step 10: If Still Not Working

1. **Check WhalePIDog is in the right state**:
   - Press `s` for Summary View
   - Verify Dog=RUNNING, PAM shows a status

2. **Test UDP directly**:
   ```bash
   echo -n "ping" | nc -u -w 2 127.0.0.1 8000
   ```

3. **Check logs in Log view**:
   - Press `l` to switch to Log view
   - Look for Bluetooth messages

4. **Restart everything**:
   ```bash
   # Stop WhalePIDog
   q
   
   # Restart Bluetooth
   sudo systemctl restart bluetooth
   
   # Start WhalePIDog again
   java -jar whalepidog.jar whalepidog_settings.json
   ```

5. **Check system logs**:
   ```bash
   # Bluetooth system logs
   sudo journalctl -u bluetooth -f
   
   # Look for connection/disconnection events
   ```

## Quick Test Commands

Once connected, try these in order:

1. `ping` - Should echo back "ping" (tests connectivity)
2. `Status` - Should return "Status 0" or "Status 1" etc.
3. `summary` - Should return a large XML/text summary

If these work, Bluetooth is functioning correctly!

## Still Having Issues?

Report the following:
1. Complete log output when sending a command
2. Your phone's Bluetooth terminal app name
3. Output of: `ps aux | grep rfcomm`
4. Output of: `ls -l /dev/rfcomm0`
5. WhalePIDog Summary View screenshot (Dog/PAM status)
