# WhalePiDog

A terminal-based watchdog for [PAMGuard](https://www.pamguard.org/) designed to run on a Raspberry Pi. WhalePiDog launches PAMGuard as a headless subprocess, monitors its health over UDP, connects via Bluetooth to peripherals, automatically restarts it if it stops responding, and provides a live terminal UI for status and control.

---

## Features

- **Automatic restart** – if PAMGuard stops responding to pings or its process dies, WhalePiDog kills it and re-launches it automatically
- **UDP communication** – sends PAMGuard commands (`ping`, `Status`, `summary`, `start`, `stop`, `Exit`) over a configurable UDP port using two independent sockets (one for the watchdog scheduler, one for manual commands) so a slow command never blocks a health-check ping
- **Live terminal UI** – a flicker-free, in-place terminal display refreshed on a schedule with three modes:
  - **Summary View** – rich graphical panels showing dB level meters per channel, sound recorder state and disk space, GPS fix, NMEA sentence, analog sensors, and Pi CPU temperature
  - **Summary Text** – raw PAMGuard summary XML text with the watchdog status banner
  - **Log** – live PAMGuard stdout/stderr output
- **Command mode** – interactive prompt (`:`), with quick-send key shortcuts for common commands
- **Deploy mode** – optionally sends the `start` command automatically after PAMGuard initialises, for unattended deployment
- **Bluetooth communication** – handles Bluetooth commands e.g. sent via a phone app such as [BlueToothSerial app](https://play.google.com/store/apps/details?id=de.kai_morich.serial_bluetooth_terminal&hl=en_GB). 

---

## Requirements

| Requirement | Details |
|---|---|
| Java | 17 or later |
| Build tool | Maven 3.6+ |
| PAMGuard | Any version with UDP control support and the `-nogui` flag |
| OS | Linux (tested on Raspberry Pi OS); any ANSI terminal emulator |

---

## Building

```bash
cd whalepidog
mvn package
```

This produces a self-contained fat jar at `target/whalepidog.jar` (all dependencies shaded in).

---

## Quick Start

**1. Generate a settings template**

If no settings file is provided, WhalePiDog writes a template to the current directory and exits:

```bash
java -jar target/whalepidog.jar
# → writes whalepidog_settings.json
```

**2. Edit the settings file**

Open `whalepidog_settings.json` and fill in the paths for your PAMGuard installation (see [Settings Reference](#settings-reference) below).

**3. Run**

```bash
java -jar target/whalepidog.jar whalepidog_settings.json
```

---

## Settings Reference

Settings are stored in a JSON file. All fields are optional — defaults are shown.

```json
{
  "pamguardJar"            : "/opt/pamguard/pamguard.jar",
  "psfxFile"               : "/data/config/myConfig.psfx",
  "wavFolder"              : "/data/recordings",
  "database"               : "/data/pamguard.sqlite3",
  "libFolder"              : "lib64",
  "jre"                    : "java",
  "mxMemory"               : 4096,
  "msMemory"               : 2048,
  "otherVMOptions"         : "",
  "otherOptions"           : "",
  "noGui"                  : true,
  "deploy"                 : true,
  "udpPort"                : 8000,
  "checkIntervalSeconds"   : 30,
  "summaryIntervalSeconds" : 5,
  "startWaitSeconds"       : 10,
  "workingFolder"          : ""
}
```

| Key | Type | Default | Description |
|---|---|---|---|
| `pamguardJar` | string | `""` | **Required.** Path to the PAMGuard `.jar` file |
| `psfxFile` | string | `""` | PAMGuard settings file (`-psf`) |
| `wavFolder` | string | `""` | Recording output folder (`-wavfolder`) |
| `database` | string | `""` | SQLite database path (`-database`) |
| `libFolder` | string | `"lib64"` | Native library path (`-Djava.library.path`) |
| `jre` | string | `"java"` | Java executable (full path or on `PATH`) |
| `mxMemory` | int | `4096` | Max JVM heap in MB (`-Xmx`) |
| `msMemory` | int | `2048` | Initial JVM heap in MB (`-Xms`) |
| `otherVMOptions` | string | `""` | Extra JVM flags inserted before `-jar` |
| `otherOptions` | string | `""` | Extra PAMGuard command-line options |
| `noGui` | bool | `true` | Pass `-nogui` to PAMGuard for headless operation |
| `deploy` | bool | `true` | Send `start` automatically after initialisation |
| `udpPort` | int | `8000` | UDP port PAMGuard listens on |
| `checkIntervalSeconds` | int | `30` | Health-check interval (ping + status) |
| `summaryIntervalSeconds` | int | `5` | Summary fetch and UI refresh interval |
| `startWaitSeconds` | int | `10` | Seconds to wait for PAMGuard to initialise before the first ping |
| `workingFolder` | string | `""` | Working directory for the PAMGuard process; defaults to the jar's directory |

---

## Terminal UI

### Default UI

The terminal by default looks as follows. 


```bash
──────────────────────────────────────────────────────────────────────
  WhalePIDog – PAMGuard Summary View           Time: 12:01:31
──────────────────────────────────────────────────────────────────────
  Dog: RUNNING   PAM: RUNNING   Up: 1m 17s   Restarts: 0   Port: 8000

  ┌─ Sound Acquisition ────────────────────────────────────────
  │ Ch 0  RMS   -90.1 dB  [░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░]
  │ Ch 0  Pk    -63.3 dB  [████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░]  [LOW ]
  │
  │ Ch 1  RMS   -91.0 dB  [░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░]
  │ Ch 1  Pk    -78.0 dB  [█░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░]  [LOW ]
  │
  └───────────────────────────────────────────────────────────

  ┌─ Sound Recorder ──────────────────────────────────────────
  │ State:  ● RECORDING    Button: start
  │ Disk free: ████████░░░░░░░░░░░░░░░░░░░░░░    137.0 GB
  └───────────────────────────────────────────────────────────

  ┌─ GPS ─────────────────────────────────────────────────────
  │ Status : NO DATA   Time: 
  │ Lat    : N 0.000000°   Lon: E 0.000000°
  │ Heading: 0.0°  ↑ N
  └───────────────────────────────────────────────────────────

  ┌─ NMEA ────────────────────────────────────────────────────
  │ $GPGSV,2,2,07,04,18,303,15,09,12,334,,27,01,243,*48
  └───────────────────────────────────────────────────────────

  ┌─ Analog Sensors ──────────────────────────────────────────
  │ Depth                 val:   1.9410  V: -0.0590 V
  │   ███████████░░░░░░░░░
  └───────────────────────────────────────────────────────────

  ┌─ Pi Temperature ──────────────────────────────────────────
  │ ██████████████░░░░░░░░░░░░░░░░  48.3 °C
  └───────────────────────────────────────────────────────────


──────────────────────────────────────────────────────────────────────
  [:] cmd  [s] Summary View  [t] Summary Text  [l] Log  [q] Quit  [h] Help
  [1] ping  [2] Status  [3] summary  [4] start  [5] stop
```

There are various commands which can be used to change the interface and to manually send PAMGuard commands

### Display modes

| Mode | Key | Description |
|---|---|---|
| Summary View | `s` | Rich graphical panels (default) |
| Summary Text | `t` | Raw PAMGuard summary XML text |
| Log | `l` | Live PAMGuard stdout / stderr |
| Command | `:` | Interactive UDP command prompt |

### Key bindings

| Key | Action |
|---|---|
| `s` | Switch to Summary View |
| `t` | Switch to Summary Text |
| `l` | Switch to Log view |
| `:` | Enter command mode |
| `q` | Quit (stops PAMGuard and exits) |
| `h` | Help |
| `1` | Send `ping` |
| `2` | Send `Status` (updates status display immediately) |
| `3` | Send `summary` (updates summary panel immediately) |
| `4` | Send `start` |
| `5` | Send `stop` |

### Command mode

Type any recognised PAMGuard UDP command and press Enter. Unknown commands are rejected locally and not forwarded to PAMGuard. An empty line or `back` returns to the previous view.

Recognised commands: `ping`, `Status`, `summary`, `start`, `stop`, `Exit`, `kill`

---

## Watchdog State Machine

```
STOPPED ──start()──► STARTING ──► WAITING_FOR_INIT ──► RUNNING
                                                          │
                                           no ping / dead ▼
                                        RESTARTING ──► WAITING_FOR_INIT
                                                          │
                                              launch fail ▼
                                                        ERROR
```

| State | Meaning |
|---|---|
| `STOPPED` | WhalePiDog not running |
| `STARTING` | PAMGuard process being launched |
| `WAITING_FOR_INIT` | Waiting for PAMGuard to respond to its first ping |
| `RUNNING` | PAMGuard alive and responding; health checks scheduled |
| `RESTARTING` | PAMGuard not responding; being killed and re-launched |
| `ERROR` | Unrecoverable error (e.g. UDP socket failure, repeated launch failure) |

---

## PAMGuard UDP Status Codes

| Code | Name | Meaning |
|---|---|---|
| `0` | `IDLE` | PAMGuard running but not acquiring |
| `1` | `RUNNING` | Acquiring and processing data |
| `3` | `STALLED` | PAMGuard running but not progressing |
| `4` | `INITIALISING` | PAMGuard starting up |

---

## Project Structure

```
whalepidog/
└── src/main/java/whalepidog/
    ├── mainapp/
    │   └── WhalePIDog.java          # Entry point
    ├── process/
    │   └── PamProcess.java          # OS process management, stdout/stderr capture
    ├── settings/
    │   ├── WhalePIDogSettings.java  # Settings model
    │   └── SettingsManager.java     # JSON load / save / template writer
    ├── udp/
    │   └── PamUDP.java              # UDP send/receive, dual-socket design
    ├── ui/
    │   ├── TerminalUI.java          # Display modes, key bindings, render loop
    │   ├── SummaryView.java         # Rich graphical summary panels
    │   └── SummaryParser.java       # PAMGuard summary XML parser
    └── watchdog/
        └── WatchdogController.java  # State machine, health checks, restart logic
```

---

## License

See [LICENSE](LICENSE) if present, or contact the project maintainer.
