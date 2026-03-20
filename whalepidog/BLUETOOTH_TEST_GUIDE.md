# Quick Bluetooth Testing Guide

## What I Changed

I've updated the Bluetooth code with:

1. **Better logging** - Now shows exactly what's received and what's sent
2. **Improved input cleaning** - Strips all whitespace and line endings
3. **Better response handling** - Uses `\r\n` (CRLF) for better phone compatibility
4. **More debug output** - Shows command length and cleaned version

## How to Test

### 1. Rebuild and Run

```bash
# The jar has been rebuilt
cd /home/jdjm/Documents/GitHub/whalepidog/whalepidog
java -jar target/whalepidog.jar whalepidog_settings.json
```

### 2. Make Sure Bluetooth is Enabled

Your settings should have:
```json
{
  "bluetoothSettings": {
    "bluetoothEnabled": true,
    "bluetoothPairing": true,
    "verbose": true
  }
}
```

### 3. Watch the Logs

When you send a command from your phone, you should now see detailed output:

```
[Bluetooth] Waiting for Bluetooth connection on /dev/rfcomm0...
[Bluetooth] Bluetooth device connected!
[Bluetooth] Bluetooth session started
[Bluetooth] Received raw: 'ping' (bytes: 4)
[Bluetooth] Cleaned to: 'ping' (bytes: 4)
[Bluetooth] Processing command: 'ping' (length=4)
[Manual] >> ping  << ping
[Bluetooth] Response from PAMGuard: 'ping'
[Bluetooth] Sent ACK: ping
[Bluetooth] Sent RPLY: ping
```

### 4. What to Look For

**If you see this** → Commands are reaching WhalePIDog:
```
[Bluetooth] Received raw: 'ping' (bytes: 4)
[Bluetooth] Processing command: 'ping' (length=4)
```

**If you see this** → PAMGuard is responding:
```
[Manual] >> ping  << ping
[Bluetooth] Response from PAMGuard: 'ping'
```

**If you see this** → Response is being sent to your phone:
```
[Bluetooth] Sent ACK: ping
[Bluetooth] Sent RPLY: ping
```

### 5. Common Problems and Solutions

#### Problem: No "Received raw" message
**Cause**: Commands aren't reaching WhalePIDog

**Check**:
- Is your phone connected? (Check Summary View - BT indicator)
- Try disconnecting and reconnecting
- Check `/dev/rfcomm0` exists: `ls -l /dev/rfcomm0`

#### Problem: "Unknown command" message
**Cause**: Command name is wrong or has extra characters

**Try**:
- Use exact command names: `ping`, `Status`, `summary`, `start`, `stop`
- Check your phone app's line ending settings (should be `\n` or `\r\n`)
- Look at the "Received raw" log - it shows EXACTLY what was sent

#### Problem: "no response / timeout"
**Cause**: PAMGuard isn't running or not responding to UDP

**Check**:
1. Press `s` to view Summary - Dog should be RUNNING
2. PAM status should show a number (not -1)
3. Test UDP manually: `echo -n "ping" | nc -u -w 2 127.0.0.1 8000`

#### Problem: Response sent but phone doesn't show it
**Cause**: Phone app not displaying received data

**Try**:
- Different Bluetooth terminal app
- Check app settings for "receive" or "display" options
- The new code uses `\r\n` which most apps expect

### 6. Test Commands in Order

Try these commands one by one:

1. **ping**
   - Simplest command
   - Should echo back "ping"
   - Tests basic connectivity

2. **Status**
   - Should return "Status 0" or "Status 1"
   - Tests PAMGuard UDP is working

3. **summary**
   - Returns lots of data
   - Tests that large responses work

4. **start**
   - Starts PAMGuard
   - Should see status change to 1

5. **stop**
   - Stops PAMGuard  
   - Should see status change to 0

### 7. Switch to Log View

To see all Bluetooth messages in real-time:

1. Press `l` to switch to Log view
2. Send commands from your phone
3. Watch the log scroll
4. Press `s` to go back to Summary view

### 8. If Still Not Working

Send me:

1. **The complete log output** when you send `ping`:
   - Everything from "Received raw" to "Sent RPLY"

2. **Your phone app name and version**:
   - e.g., "Serial Bluetooth Terminal v1.40"

3. **App settings screenshot**:
   - Line ending setting
   - Character encoding

4. **WhalePIDog status**:
   - What does Summary View show?
   - Dog status?
   - PAM status?
   - BT indicator (● or ○)?

5. **Manual UDP test result**:
   ```bash
   echo -n "ping" | nc -u -w 2 127.0.0.1 8000
   ```
   What did this return?

## Expected Behavior

When everything works correctly:

1. **You type**: `ping` → press send
2. **Phone shows**: 
   ```
   ACK: ping
   RPLY: ping
   ```
3. **WhalePIDog log shows**:
   ```
   [Bluetooth] Received raw: 'ping' (bytes: 4)
   [Bluetooth] Cleaned to: 'ping' (bytes: 4)
   [Bluetooth] Processing command: 'ping' (length=4)
   [Manual] >> ping  << ping
   [Bluetooth] Response from PAMGuard: 'ping'
   [Bluetooth] Sent ACK: ping
   [Bluetooth] Sent RPLY: ping
   ```

The improved logging should help us figure out exactly where things are failing!
