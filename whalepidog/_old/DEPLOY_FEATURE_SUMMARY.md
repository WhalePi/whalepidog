# Deploy Feature Implementation Summary

## Overview
Implemented a persistent deploy state feature for WhalePIDog that controls whether PAMGuard starts running automatically after launch.

## Changes Made

### 1. WatchdogController.java
- **Added settingsFile field**: Store reference to the settings JSON file for persistence
- **Updated constructor**: Now accepts `File settingsFile` parameter
- **Added saveSettings() method**: Helper to persist settings to JSON file
- **Enhanced sendCommandAndUpdate()**: 
  - When user sends `start` command, sets `deploy=true` and saves to file
  - When user sends `stop` command, sets `deploy=false` and saves to file
  - Logs deploy state changes for visibility

### 2. WhalePIDog.java (main app)
- **Updated WatchdogController instantiation**: Now passes `settingsFile` to constructor

### 3. TerminalUI.java
- **Added ANSI_ORANGE color constant**: For visual distinction of deploy=false state
- **Added colourDeploy() method**: Returns green+bold "true" or orange+bold "false"
- **Updated appendBannerSB()**: Now displays Deploy status with color coding

### 4. SummaryView.java
- **Added OR (orange) color constant**: For visual distinction of deploy=false state
- **Added colourDeploy() method**: Returns green+bold "true" or orange+bold "false"
- **Updated appendBanner()**: Now displays Deploy status in the summary view banner

## Behavior

### Display
- **Deploy = true**: Shown in **GREEN** (bold) in all UI views
- **Deploy = false**: Shown in **ORANGE** (bold) in all UI views
- Location: Near the top of the Terminal UI banner, after PAM status

### Logic
1. **On startup**: 
   - PAMGuard is ALWAYS launched by the watchdog
   - If `deploy=true`: PAMGuard receives the `start` command after initialization
   - If `deploy=false`: PAMGuard remains idle (not recording/processing)

2. **When user sends "start" command**:
   - PAMGuard is told to start
   - `deploy` is set to `true` in memory
   - Settings file is saved to disk
   - Even if Pi restarts, PAMGuard will auto-start

3. **When user sends "stop" command**:
   - PAMGuard is told to stop
   - `deploy` is set to `false` in memory
   - Settings file is saved to disk
   - Even if Pi restarts, PAMGuard will NOT auto-start (will launch but remain idle)

## Benefits
- **Persistent user intent**: User's last action (start/stop) is remembered across Pi reboots
- **Safety**: If user stops PAMGuard, it stays stopped even after power cycle
- **Convenience**: If user starts PAMGuard, it continues running after crashes/restarts
- **Visual feedback**: Clear indication of deploy state in all UI views

## Testing
- Compile tested: `mvn clean compile` ✓
- No syntax errors
- All dependencies resolved
