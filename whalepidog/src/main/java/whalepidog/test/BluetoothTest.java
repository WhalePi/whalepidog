package whalepidog.test;

import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone BLE diagnostic test for WhalePiDog.
 *
 * <p>This class starts a minimal BLE server (without PAMGuard or the full
 * watchdog stack) and responds to every received command with a PONG message.
 * All BLE activity is logged to the terminal with maximum verbosity to help
 * diagnose connectivity issues — especially on lower-powered boards like the
 * Raspberry Pi Zero.
 *
 * <p>The test talks directly to the Python {@code ble_server.py} script via
 * stdin/stdout, bypassing {@link whalepidog.bluetooth.BluetoothBLE} and
 * {@link whalepidog.watchdog.WatchdogController} entirely.  This means there
 * are no PAMGuard, UDP, or settings-file dependencies — just Bluetooth.
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -jar whalepidog.jar --bletest
 *   java -jar whalepidog.jar --bletest MyDeviceName
 *   java -cp  whalepidog.jar whalepidog.test.BluetoothTest [deviceName]
 * </pre>
 *
 * <p>Once running, connect with a BLE terminal app (e.g.&nbsp;nRF Connect,
 * Serial Bluetooth Terminal, or a Flutter app using the Nordic UART Service)
 * and send any text.  The server will echo back:
 * <pre>
 *   PONG #1 [2026-03-08 12:34:56.789]: your text here
 * </pre>
 */
public class BluetoothTest {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final AtomicInteger pingCount = new AtomicInteger(0);

    private static volatile Process pythonProcess;
    private static volatile BufferedWriter toScript;

    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        String deviceName = "whalepi";
        if (args.length >= 1 && !args[0].startsWith("-")) {
            deviceName = args[0];
        }
        run(deviceName);
    }

    /**
     * Entry point when invoked from {@link whalepidog.mainapp.WhalePIDog}.
     *
     * @param deviceName BLE advertised name (e.g. "whalepi" or "whalepi_X12")
     */
    public static void run(String deviceName) {

        banner();
        log("BLE diagnostic test starting");
        log("Device name: " + deviceName);
        log("Timestamp  : " + LocalDateTime.now().format(TS));

        // ── 1. System diagnostics ────────────────────────────────────────────
        printSystemInfo();
        checkBluetoothPrerequisites();

        // ── 2. Attempt to power on the adapter ─────────────────────────────
        ensureAdapterPowered();

        // ── 3. Extract & launch the Python BLE server ────────────────────────
        log("── Launching BLE Server ───────────────────────");

        File scriptFile;
        try {
            scriptFile = extractBleScript();
        } catch (IOException e) {
            log("FATAL: Could not extract ble_server.py: " + e.getMessage());
            System.exit(1);
            return;
        }

        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("python3");
            cmd.add(scriptFile.getAbsolutePath());
            cmd.add("--name");
            cmd.add(deviceName);
            cmd.add("--verbose");

            log("Command: " + String.join(" ", cmd));
            log("");
            log("Nordic UART Service UUIDs:");
            log("  Service : 6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
            log("  RX write: 6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
            log("  TX notif: 6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
            log("");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            pythonProcess = pb.start();

            toScript = new BufferedWriter(new OutputStreamWriter(
                    pythonProcess.getOutputStream(), StandardCharsets.UTF_8));

            // Shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log("Shutdown hook — stopping BLE test");
                shutdown();
            }, "bletest-shutdown"));

            // stderr reader (Python logs go here)
            Thread stderrThread = new Thread(() -> pipeStream(
                    pythonProcess.getErrorStream(), "PY"), "ble-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            // stdout reader — this is where we get RX: / CONNECTED / DISCONNECTED
            log("BLE server started — waiting for connections…  (Ctrl-C to stop)");
            log("");

            try (BufferedReader stdout = new BufferedReader(new InputStreamReader(
                    pythonProcess.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = stdout.readLine()) != null) {
                    handlePythonLine(line);
                }
            }

            int exit = pythonProcess.waitFor();
            log("Python process exited with code: " + exit);

        } catch (Exception e) {
            log("FATAL: " + e.getMessage());
            e.printStackTrace();
        } finally {
            shutdown();
        }
    }

    // ── Protocol handling ────────────────────────────────────────────────────

    private static void handlePythonLine(String line) {
        if (line.startsWith("RX:")) {
            String command = line.substring(3).trim();
            int seq = pingCount.incrementAndGet();
            String ts = LocalDateTime.now().format(TS);

            log(">>> RECEIVED command #" + seq + ": '" + command + "'");

            // Build PONG response
            String pong = "PONG #" + seq + " [" + ts + "]: " + command;
            log("<<< REPLYING: '" + pong + "'");

            sendToClient("ACK: " + command);
            sendToClient(pong);

        } else if (line.startsWith("CONNECTED")) {
            log("═══ CLIENT CONNECTED ═══");

        } else if (line.startsWith("DISCONNECTED")) {
            log("═══ CLIENT DISCONNECTED ═══");

        } else {
            // Any other stdout from Python
            log("[PY-stdout] " + line);
        }
    }

    /**
     * Send a line to the connected BLE client via the Python process stdin.
     */
    private static void sendToClient(String data) {
        try {
            if (toScript != null) {
                toScript.write("TX:" + data + "\n");
                toScript.flush();
                log("    → sent: " + data);
            }
        } catch (IOException e) {
            log("ERROR sending to client: " + e.getMessage());
        }
    }

    private static void shutdown() {
        if (pythonProcess != null) {
            try {
                if (toScript != null) {
                    toScript.write("SHUTDOWN\n");
                    toScript.flush();
                    toScript.close();
                }
            } catch (IOException ignored) { }

            try {
                pythonProcess.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) { }

            if (pythonProcess.isAlive()) {
                pythonProcess.destroyForcibly();
            }
            pythonProcess = null;
        }
    }

    // ── BLE script extraction ────────────────────────────────────────────────

    private static File extractBleScript() throws IOException {
        // Try classpath first (when running from JAR)
        InputStream scriptStream = BluetoothTest.class.getResourceAsStream("/ble_server.py");
        if (scriptStream != null) {
            File tmp = File.createTempFile("ble_server_test_", ".py");
            tmp.deleteOnExit();
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = scriptStream.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                }
            }
            scriptStream.close();
            tmp.setExecutable(true);
            log("Extracted ble_server.py to: " + tmp.getAbsolutePath());
            return tmp;
        }

        // Fallback: look for it in the source tree
        File local = new File("src/main/resources/ble_server.py");
        if (local.exists()) {
            log("Using ble_server.py at: " + local.getAbsolutePath());
            return local;
        }

        throw new FileNotFoundException(
                "ble_server.py not found in classpath or at " + local.getAbsolutePath());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static final long START_MS = System.currentTimeMillis();

    private static String uptime() {
        long s = (System.currentTimeMillis() - START_MS) / 1000;
        return String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    private static void banner() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║     WhalePiDog  BLE Diagnostic Test          ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();
    }

    private static void log(String msg) {
        String ts = LocalDateTime.now().format(TS);
        System.out.println("[" + ts + "] " + msg);
    }

    /**
     * Pipe an InputStream to our log, prefixed with a tag.
     */
    private static void pipeStream(InputStream is, String tag) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = br.readLine()) != null) {
                log("[" + tag + "] " + line);
            }
        } catch (IOException e) {
            // stream closed — normal during shutdown
        }
    }

    // ── System info ──────────────────────────────────────────────────────────

    private static void printSystemInfo() {
        log("── System Information ──────────────────────────");
        log("  os.name    : " + System.getProperty("os.name"));
        log("  os.version : " + System.getProperty("os.version"));
        log("  os.arch    : " + System.getProperty("os.arch"));
        log("  java       : " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");
        try {
            log("  hostname   : " + InetAddress.getLocalHost().getHostName());
        } catch (Exception e) {
            log("  hostname   : (unknown)");
        }

        // Detect Pi model
        try {
            File model = new File("/proc/device-tree/model");
            if (model.exists()) {
                String piModel = new String(
                        java.nio.file.Files.readAllBytes(model.toPath())).trim();
                log("  pi model   : " + piModel);
            }
        } catch (Exception e) {
            log("  pi model   : (could not read)");
        }

        // CPU info
        try {
            File cpuinfo = new File("/proc/cpuinfo");
            if (cpuinfo.exists()) {
                try (BufferedReader br = new BufferedReader(new FileReader(cpuinfo))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.startsWith("model name") || line.startsWith("Hardware")) {
                            log("  cpu        : " + line.split(":")[1].trim());
                        }
                    }
                }
            }
        } catch (Exception ignored) { }

        log("");
    }

    // ── Adapter power-on ────────────────────────────────────────────────────

    /**
     * Attempt to bring the Bluetooth adapter to a powered-on state.
     *
     * <p>This addresses the common "org.bluez.Error.Failed Not Powered" error
     * by working through the usual causes in order:
     * <ol>
     *   <li>rfkill soft-block</li>
     *   <li>hciconfig interface down</li>
     *   <li>bluetoothctl power off</li>
     *   <li>hciuart service not running (Pi Zero W UART-attached BT)</li>
     * </ol>
     */
    private static void ensureAdapterPowered() {
        log("── Ensuring Bluetooth Adapter Is Powered ──────");

        // ── 1. rfkill ────────────────────────────────────────────────────────
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"rfkill", "list", "bluetooth"});
            String out = readStream(p.getInputStream());
            p.waitFor();
            log("  rfkill list bluetooth:");
            for (String l : out.split("\n")) {
                log("    " + l);
            }

            if (out.contains("Soft blocked: yes")) {
                log("  ⚠ Bluetooth is SOFT-BLOCKED — attempting rfkill unblock…");
                Process unblock = Runtime.getRuntime().exec(
                        new String[]{"sudo", "rfkill", "unblock", "bluetooth"});
                int rc = unblock.waitFor();
                if (rc == 0) {
                    log("  ✓ rfkill unblock succeeded");
                } else {
                    log("  ✗ rfkill unblock failed (exit " + rc
                            + ") — try manually: sudo rfkill unblock bluetooth");
                }
            } else if (out.contains("Hard blocked: yes")) {
                log("  ✗ Bluetooth is HARD-BLOCKED — this is a hardware switch/BIOS setting");
                log("    Cannot fix in software.");
            } else {
                log("  ✓ Bluetooth is not rfkill-blocked");
            }
        } catch (Exception e) {
            log("  ? Could not check rfkill: " + e.getMessage());
        }

        // ── 2. hciconfig up ─────────────────────────────────────────────────
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"hciconfig", "hci0"});
            String out = readStream(p.getInputStream());
            p.waitFor();

            if (out.contains("DOWN")) {
                log("  ⚠ hci0 is DOWN — attempting hciconfig hci0 up…");
                Process up = Runtime.getRuntime().exec(
                        new String[]{"sudo", "hciconfig", "hci0", "up"});
                String upErr = readStream(up.getErrorStream());
                int rc = up.waitFor();
                if (rc == 0) {
                    log("  ✓ hciconfig hci0 up succeeded");
                } else {
                    log("  ✗ hciconfig hci0 up failed (exit " + rc + ")");
                    if (!upErr.isBlank()) log("    " + upErr.trim());
                }
            } else if (out.contains("UP RUNNING")) {
                log("  ✓ hci0 interface is UP");
            } else if (out.isBlank()) {
                log("  ✗ hci0 not found — adapter may not be detected by kernel");
                log("    Check: dmesg | grep -i bluetooth");
                log("    Check: sudo systemctl status hciuart");
            }
        } catch (Exception e) {
            log("  ? Could not check hci0: " + e.getMessage());
        }

        // ── 3. bluetoothctl power on ────────────────────────────────────────
        try {
            log("  Attempting: bluetoothctl power on");
            ProcessBuilder pb = new ProcessBuilder("bluetoothctl", "power", "on");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = readStream(p.getInputStream());
            int rc = p.waitFor();
            for (String l : out.split("\n")) {
                if (!l.isBlank()) log("    " + l.trim());
            }
            if (out.contains("Changing power on") || out.contains("succeeded")) {
                log("  ✓ bluetoothctl power on succeeded");
            } else if (out.contains("Not Powered") || out.contains("Failed")) {
                log("  ✗ bluetoothctl power on FAILED — adapter cannot be powered");
                log("    Possible causes:");
                log("    • rfkill hard-block");
                log("    • hciuart service failed (Pi Zero W: UART not attached)");
                log("    • BT firmware blob missing or failed to load");
                log("    • Kernel/firmware mismatch — try: sudo apt full-upgrade");
                log("    • Under-voltage / inadequate power supply");
                log("    Run on the Pi:  dmesg | grep -iE 'bluetooth|firmware|hci|voltage'");
            }
        } catch (Exception e) {
            log("  ? Could not run bluetoothctl: " + e.getMessage());
        }

        // ── 4. hciuart service (Pi Zero W specific) ─────────────────────────
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"systemctl", "is-active", "hciuart"});
            String status = readStream(p.getInputStream()).trim();
            p.waitFor();
            if ("active".equals(status)) {
                log("  ✓ hciuart service is active");
            } else if ("inactive".equals(status) || "failed".equals(status)) {
                log("  ⚠ hciuart service is " + status
                        + " — this attaches the UART Bluetooth chip on Pi Zero W");
                log("    Try: sudo systemctl restart hciuart");
                log("    Also check: sudo raspi-config → Interface Options → Serial Port");
                log("      → Disable login shell over serial, Enable serial hardware");
            } else {
                log("  ? hciuart service status: " + status);
            }
        } catch (Exception e) {
            log("  ? Could not check hciuart: " + e.getMessage());
        }

        // ── 5. Final verification ───────────────────────────────────────────
        try {
            Thread.sleep(500); // give BlueZ a moment after power-on
            ProcessBuilder pb = new ProcessBuilder("bluetoothctl", "show");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = readStream(p.getInputStream());
            p.waitFor();
            boolean powered = false;
            for (String l : out.split("\n")) {
                String lt = l.trim();
                if (lt.startsWith("Powered:")) {
                    powered = lt.contains("yes");
                    log("  Final status — " + lt);
                } else if (lt.startsWith("Name:") || lt.startsWith("Address:")
                        || lt.startsWith("Alias:")) {
                    log("  " + lt);
                }
            }
            if (powered) {
                log("  ✓ Adapter is POWERED ON — ready to advertise");
            } else {
                log("  ✗ Adapter is still NOT POWERED — BLE will fail");
                log("    See diagnostic suggestions above.");
            }
        } catch (Exception e) {
            log("  ? Could not verify final adapter state: " + e.getMessage());
        }

        log("");
    }

    // ── Prerequisite checks ──────────────────────────────────────────────────

    private static void checkBluetoothPrerequisites() {
        log("── Bluetooth Prerequisites ────────────────────");

        checkCommand("bluetoothctl --version",  "bluetoothctl");
        checkCommand("python3 --version",       "python3");
        checkCommand("bluetoothd --version",    "bluetoothd (BlueZ)");
        checkPythonPackage("bluezero");
        checkCommand("hciconfig hci0",          "hci0 adapter");

        // Check adapter is UP
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"hciconfig", "hci0"});
            String out = readStream(p.getInputStream());
            p.waitFor();
            if (out.contains("UP RUNNING")) {
                log("  ✓ hci0 is UP RUNNING");
            } else if (out.contains("DOWN")) {
                log("  ✗ hci0 is DOWN — try: sudo hciconfig hci0 up");
            } else {
                log("  ? hci0 status unclear:");
                for (String l : out.split("\n")) {
                    log("    " + l);
                }
            }
        } catch (Exception e) {
            log("  ✗ Could not query hci0: " + e.getMessage());
        }

        // D-Bus check
        checkCommand(
            "dbus-send --system --print-reply --dest=org.bluez / "
                + "org.freedesktop.DBus.Introspectable.Introspect",
            "D-Bus → BlueZ");

        // ble_server.py availability
        InputStream script = BluetoothTest.class.getResourceAsStream("/ble_server.py");
        if (script != null) {
            log("  ✓ ble_server.py found in classpath resources");
            try { script.close(); } catch (IOException ignored) { }
        } else {
            File local = new File("src/main/resources/ble_server.py");
            if (local.exists()) {
                log("  ✓ ble_server.py found at " + local.getAbsolutePath());
            } else {
                log("  ✗ ble_server.py NOT FOUND — BLE will fail");
            }
        }

        // Check if user is in bluetooth group
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"id", "-Gn"});
            String groups = readStream(p.getInputStream());
            p.waitFor();
            if (groups.contains("bluetooth")) {
                log("  ✓ User is in 'bluetooth' group");
            } else {
                log("  ⚠ User NOT in 'bluetooth' group (may need sudo)");
                log("    Groups: " + groups.trim());
            }
        } catch (Exception e) {
            log("  ? Could not check user groups: " + e.getMessage());
        }

        log("");
    }

    private static void checkCommand(String cmd, String label) {
        try {
            Process p = Runtime.getRuntime().exec(cmd.split("\\s+"));
            String out = readStream(p.getInputStream());
            String err = readStream(p.getErrorStream());
            int rc = p.waitFor();
            if (rc == 0) {
                String ver = out.isBlank() ? err.trim() : out.trim();
                if (ver.contains("\n")) ver = ver.substring(0, ver.indexOf('\n'));
                if (ver.length() > 80) ver = ver.substring(0, 80) + "…";
                log("  ✓ " + label + (ver.isEmpty() ? "" : " — " + ver));
            } else {
                log("  ✗ " + label + " — exit code " + rc);
            }
        } catch (Exception e) {
            log("  ✗ " + label + " — " + e.getMessage());
        }
    }

    private static void checkPythonPackage(String pkg) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "python3", "-c",
                    "import " + pkg + "; print(" + pkg + ".__version__)"});
            String out = readStream(p.getInputStream());
            String err = readStream(p.getErrorStream());
            int rc = p.waitFor();
            if (rc == 0) {
                log("  ✓ python3 " + pkg + " — " + out.trim());
            } else {
                log("  ✗ python3 " + pkg + " NOT installed — pip3 install " + pkg);
                if (!err.isBlank()) {
                    log("    " + err.trim().split("\n")[0]);
                }
            }
        } catch (Exception e) {
            log("  ✗ python3 " + pkg + " — " + e.getMessage());
        }
    }

    private static String readStream(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
