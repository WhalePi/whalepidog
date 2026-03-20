package whalepidog.bluetooth;

import whalepidog.process.CopyDataTask;
import whalepidog.process.CopyDataTask.ExternalVolume;
import whalepidog.settings.WhalePIDogSettings;
import whalepidog.watchdog.WatchdogController;

import whalepidog.udp.PamUDP;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Handles the {@code copydata} command for Bluetooth (BLE and Serial SPP).
 *
 * <p>Because Bluetooth clients cannot do multi-step interactive prompts the
 * way the local terminal can, the command is split into two forms:
 * <ul>
 *   <li><b>{@code copydata}</b> — lists the available external volumes and
 *       returns the list as the response.</li>
 *   <li><b>{@code copydata <N>}</b> — selects volume number {@code N} (1-based)
 *       and starts the copy.  Progress is streamed back via
 *       {@link BluetoothInterface#sendCopyProgress(String)} with the
 *       {@code COPY:} prefix, and the final response indicates success or
 *       failure.</li>
 * </ul>
 *
 * <p>All validation (PAMGuard running check, wavFolder existence, free space)
 * is performed before the copy begins.
 */
public class CopyDataHandler {

    private CopyDataHandler() {} // utility class

    /**
     * Handle a {@code copydata} command string.
     *
     * @param command          the raw command (e.g. "copydata" or "copydata 2")
     * @param watchdog         the watchdog controller (for process / settings access)
     * @param progressCallback receives human-readable progress lines during copy;
     *                         may be {@code null}
     * @return a response string to send back to the Bluetooth client
     */
    public static String handle(String command, WatchdogController watchdog,
                                Consumer<String> progressCallback) {
        Consumer<String> progress = progressCallback != null ? progressCallback : s -> {};

        // 1. Check PAMGuard is not actively processing data
        if (watchdog.getPamProcess().isAlive() && watchdog.getPamStatus() == PamUDP.PAM_RUNNING) {
            return "ERROR: PAMGuard is currently processing data. Stop PAMGuard first (send 'stop').";
        }

        // 2. Validate wavFolder
        WhalePIDogSettings settings = watchdog.getSettings();
        String wavFolder = settings.getWavFolder();
        if (wavFolder == null || wavFolder.isBlank()) {
            return "ERROR: No wavFolder configured in settings.";
        }
        Path sourcePath = Path.of(wavFolder);
        if (!Files.isDirectory(sourcePath)) {
            return "ERROR: wavFolder does not exist: " + wavFolder;
        }

        // 3. Parse command: "copydata" vs "copydata <N>"
        String arg = command.trim().substring("copydata".length()).trim();

        if (arg.isEmpty()) {
            // List mode
            return listVolumes();
        }

        // Selection mode
        int selection;
        try {
            selection = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            return "ERROR: Invalid volume number '" + arg + "'. Use 'copydata' to list volumes.";
        }

        List<ExternalVolume> volumes = CopyDataTask.listExternalVolumes();
        if (volumes.isEmpty()) {
            return "ERROR: No external storage drives detected.";
        }
        if (selection < 1 || selection > volumes.size()) {
            return "ERROR: Invalid selection " + selection + ". Choose 1-" + volumes.size() + ".";
        }

        ExternalVolume target = volumes.get(selection - 1);

        // 4. Validate space
        String dbSetting = settings.getDatabase();
        Path dbPath = (dbSetting != null && !dbSetting.isBlank()) ? Path.of(dbSetting) : null;

        CopyDataTask task = new CopyDataTask(sourcePath, dbPath, progress);
        String spaceError = task.validateSpace(target);
        if (spaceError != null) {
            return "ERROR: " + spaceError;
        }

        // 5. Copy (blocking — runs on the Bluetooth command thread)
        progress.accept("Starting copy to " + target.getMountPoint() + " ...");
        boolean ok = task.copy(target);

        if (ok) {
            return "Copy completed successfully to " + target.getMountPoint();
        } else {
            return "ERROR: Copy failed. Check progress messages for details.";
        }
    }

    /**
     * Build a human-readable listing of available external volumes.
     */
    private static String listVolumes() {
        List<ExternalVolume> volumes = CopyDataTask.listExternalVolumes();
        if (volumes.isEmpty()) {
            return "No external storage drives detected. Attach a USB drive and try again.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Available volumes:\n");
        for (int i = 0; i < volumes.size(); i++) {
            sb.append(String.format("  %d  %s%n", i + 1, volumes.get(i).toDisplayString()));
        }
        sb.append("\nSend 'copydata <N>' to copy data to that volume.");
        return sb.toString();
    }
}
