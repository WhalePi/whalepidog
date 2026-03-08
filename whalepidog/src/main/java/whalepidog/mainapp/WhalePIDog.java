package whalepidog.mainapp;

import whalepidog.settings.SettingsManager;
import whalepidog.settings.WhalePIDogSettings;
import whalepidog.test.BluetoothTest;
import whalepidog.ui.TerminalUI;
import whalepidog.watchdog.WatchdogController;

import java.io.File;

/**
 * WhalePIDog – a terminal-based watchdog for PAMGuard.
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -jar whalepidog.jar &lt;settings.json&gt;
 * </pre>
 *
 * <p>If no argument is provided a template settings file called
 * {@code whalepidog_settings.json} is written to the current directory and
 * the program exits so the user can fill it in.
 *
 * <h2>Settings JSON keys</h2>
 * <pre>
 * {
 *   "pamguardJar"            : "/opt/pamguard/pamguard.jar",
 *   "psfxFile"               : "/data/config/myConfig.psfx",
 *   "wavFolder"              : "/data/recordings",
 *   "database"               : "/data/pamguard.sqlite3",
 *   "libFolder"              : "/opt/pamguard/lib64",
 *   "jre"                    : "java",
 *   "mxMemory"               : 4096,
 *   "msMemory"               : 2048,
 *   "otherVMOptions"         : "",
 *   "otherOptions"           : "",
 *   "deploy"                 : true,
 *   "udpPort"                : 8000,
 *   "checkIntervalSeconds"   : 30,
 *   "summaryIntervalSeconds" : 5,
 *   "startWaitSeconds"       : 10,
 *   "workingFolder"          : ""
 * }
 * </pre>
 */
public class WhalePIDog {

    public static void main(String[] args) {

        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║          WhalePIDog  v1.0.0              ║");
        System.out.println("║  PAMGuard Watchdog for Raspberry Pi      ║");
        System.out.println("╚══════════════════════════════════════════╝");

        // ── 0. Check for --bletest mode ──────────────────────────────────────
        if (args.length >= 1 && args[0].equals("--bletest")) {
            String deviceName = (args.length >= 2) ? args[1] : "whalepi";
            BluetoothTest.run(deviceName);
            return; // never reached — BluetoothTest.run() blocks
        }

        // ── 1. Resolve settings file ─────────────────────────────────────────
        File settingsFile;
        if (args.length < 1) {
            File template = new File("whalepidog_settings.json");
            if (!template.exists()) {
                System.out.println();
                System.out.println("No settings file provided.");
                System.out.println("Writing template to: " + template.getAbsolutePath());
                SettingsManager.writeTemplate(template);
                System.out.println("Edit the template then run:");
                System.out.println("  java -jar whalepidog.jar whalepidog_settings.json");
            } else {
                System.out.println("Found existing settings file: " + template.getAbsolutePath());
                System.out.println("Run: java -jar whalepidog.jar whalepidog_settings.json");
            }
            System.exit(0);
        }

        settingsFile = new File(args[0]);
        if (!settingsFile.exists()) {
            System.err.println("ERROR: Settings file not found: " + settingsFile.getAbsolutePath());
            System.exit(1);
        }

        // ── 2. Load settings ─────────────────────────────────────────────────
        WhalePIDogSettings settings = SettingsManager.load(settingsFile);
        if (settings == null) {
            System.err.println("ERROR: Failed to load settings from: " + settingsFile.getAbsolutePath());
            System.exit(1);
        }

        // ── 3. Validate mandatory fields ─────────────────────────────────────
        if (settings.getPamguardJar() == null || settings.getPamguardJar().isBlank()) {
            System.err.println("ERROR: 'pamguardJar' must be set in settings.");
            System.exit(1);
        }
        File pamJar = new File(settings.getPamguardJar());
        if (!pamJar.exists()) {
            System.err.println("WARNING: PAMGuard jar not found: " + pamJar.getAbsolutePath());
            System.err.println("         Continuing anyway – jar may be on a mounted path.");
        }

        // ── 4. Create watchdog and UI ─────────────────────────────────────────
        WatchdogController watchdog = new WatchdogController(settings, settingsFile);
        TerminalUI         ui       = new TerminalUI(watchdog, settings);

        // State changes are already logged inside WatchdogController.
        // Do NOT add a raw System.out.println listener here – it bypasses
        // the UI renderLock and causes spurious scrolling.

        // ── 5. Register shutdown hook ─────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[WhalePIDog] Shutdown hook – stopping watchdog.");
            watchdog.stop(true);
        }, "shutdown-hook"));

        // ── 6. Start watchdog then hand control to the UI ─────────────────────
        watchdog.start();
        ui.run(); // blocks until user quits

        System.out.println("[WhalePIDog] Exiting.");
        System.exit(0);
    }
}