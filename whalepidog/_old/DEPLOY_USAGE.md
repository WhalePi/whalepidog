# Deploy Feature - Quick Usage Guide

## What is the Deploy Flag?

The **deploy** flag determines whether PAMGuard should start running (and therefore recording) automatically when launched by the watchdog.

## Visual Indicators

In all Terminal UI views (Summary View, Summary Text, Log), you'll see the Deploy status near the top:

```
Deploy     : true   (GREEN = will auto-start)
Deploy     : false  (ORANGE = will NOT auto-start)
```

## How It Works

### On WhalePIDog Startup
1. Watchdog **always** launches PAMGuard
2. If `deploy=true`: PAMGuard receives the `start` command → begins recording
3. If `deploy=false`: PAMGuard stays idle → no recording

### Manual Control

#### To Start Recording (and keep it running):
- Press `4` in any view, OR
- Enter command mode (`:`) and type `start`
- Result: 
  - PAMGuard starts recording immediately
  - `deploy` is set to `true` and saved
  - Even if Pi reboots, PAMGuard will auto-start

#### To Stop Recording (and keep it stopped):
- Press `5` in any view, OR
- Enter command mode (`:`) and type `stop`
- Result:
  - PAMGuard stops recording immediately
  - `deploy` is set to `false` and saved
  - Even if Pi reboots, PAMGuard will NOT auto-start

## Example Scenarios

### Scenario 1: Field Deployment
1. User starts PAMGuard with `4` or `start` command
2. `deploy=true` is saved to settings file
3. Pi loses power and reboots
4. WhalePIDog launches PAMGuard and automatically starts it
5. Recording resumes without user intervention ✓

### Scenario 2: Maintenance Mode
1. User stops PAMGuard with `5` or `stop` command
2. `deploy=false` is saved to settings file
3. User performs maintenance, Pi may reboot
4. WhalePIDog launches PAMGuard but does NOT start it
5. PAMGuard stays idle, no unwanted recordings ✓

## Settings File

The deploy state is persisted in your `whalepidog_settings.json`:

```json
{
  "deploy": true,
  ...
}
```

You can also manually edit this file, but using the start/stop commands is recommended.

## Logs

When you use start/stop commands, you'll see log messages like:

```
[Deploy] deploy set to TRUE and saved (PAMGuard will auto-start on restart).
[Deploy] deploy set to FALSE and saved (PAMGuard will NOT auto-start on restart).
```

These confirm that your choice has been persisted.
