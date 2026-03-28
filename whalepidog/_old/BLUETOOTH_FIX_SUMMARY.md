# Bluetooth Fix Summary

## Problem
Commands were being sent from phone via Bluetooth but appeared to do nothing.

## Root Causes Addressed

1. **Insufficient logging** - Couldn't see what was actually happening
2. **Line ending issues** - Different phone apps send different line endings (`\n` vs `\r\n`)
3. **Response format** - Needed `\r\n` endings for phone compatibility
4. **Hidden whitespace** - Extra spaces or control characters not being stripped

## Changes Made

### 1. Enhanced Input Cleaning
```java
// Before: Just trim()
String line = scanner.nextLine().trim();

// After: Strip ALL trailing whitespace including \r\n
String line = scanner.nextLine();
String cleanLine = line.replaceAll("[\\r\\n\\s]+$", "").trim();
```

### 2. Comprehensive Logging
Now logs:
- Raw input (exactly as received)
- Cleaned input (after processing)
- Byte length (to spot hidden characters)
- Command being processed
- Response from PAMGuard
- What's being sent back

### 3. Improved Response Format
```java
// Before: Used \n
"ACK: " + command + "\n"

// After: Uses \r\n for better phone compatibility
"ACK: " + command + "\r\n"
```

### 4. Output Flushing
```java
// Added explicit flush to ensure data is sent immediately
comPort.getOutputStream().flush();
```

### 5. Better Error Reporting
All errors now include:
- Stack traces
- Context (what command was being processed)
- Port status

## Files Modified

- `BluetoothCommands.java` - Enhanced logging, input cleaning, response handling

## Files Created

- `BLUETOOTH_TROUBLESHOOTING.md` - Comprehensive troubleshooting guide
- `BLUETOOTH_TEST_GUIDE.md` - Step-by-step testing instructions

## How to Use

1. **Rebuild** (already done):
   ```bash
   cd /home/jdjm/Documents/GitHub/whalepidog/whalepidog
   mvn clean package
   ```

2. **Enable verbose logging** in `whalepidog_settings.json`:
   ```json
   {
     "bluetoothSettings": {
       "bluetoothEnabled": true,
       "bluetoothPairing": true,
       "verbose": true
     }
   }
   ```

3. **Run WhalePIDog**:
   ```bash
   java -jar target/whalepidog.jar whalepidog_settings.json
   ```

4. **Connect from phone** and send `ping`

5. **Check the logs** - You should now see:
   ```
   [Bluetooth] Received raw: 'ping' (bytes: 4)
   [Bluetooth] Cleaned to: 'ping' (bytes: 4)
   [Bluetooth] Processing command: 'ping' (length=4)
   [Manual] >> ping  << ping
   [Bluetooth] Response from PAMGuard: 'ping'
   [Bluetooth] Sent ACK: ping
   [Bluetooth] Sent RPLY: ping
   ```

## Debugging Steps

If commands still don't work, check the logs for:

### Issue: No "Received raw" message
- Commands aren't reaching Bluetooth layer
- Check phone is connected (BT indicator in Summary View)
- Verify `/dev/rfcomm0` exists

### Issue: "Unknown command" error
- Command name is wrong
- Check "Received raw" to see exact input
- Use correct command: `ping`, `Status`, `summary`, `start`, `stop`

### Issue: "no response / timeout"
- PAMGuard isn't responding
- Check Dog=RUNNING and PAM shows a status number
- Test UDP: `echo -n "ping" | nc -u -w 2 127.0.0.1 8000`

### Issue: Response sent but phone doesn't show
- Phone app not displaying received data
- Try different Bluetooth terminal app
- Check app's "receive" settings

## What Should Happen

**Normal operation:**
1. Send `ping` from phone
2. WhalePIDog logs show command received
3. Command forwarded to PAMGuard via UDP
4. Response received from PAMGuard
5. ACK + RPLY sent back to phone
6. Phone displays the response

## Next Steps

1. Run the new version
2. Send `ping` command
3. Copy the log output and share it
4. We can diagnose exactly where the issue is

The enhanced logging will show us:
- ✓ Is the command reaching Bluetooth layer?
- ✓ Is it being cleaned correctly?
- ✓ Is it a valid command?
- ✓ Is PAMGuard responding?
- ✓ Is the response being sent back?

This should solve the issue or at least make it very clear what's failing!
