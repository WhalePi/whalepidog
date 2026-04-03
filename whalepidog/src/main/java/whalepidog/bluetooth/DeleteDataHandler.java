package whalepidog.bluetooth;

import whalepidog.settings.WhalePIDogSettings;
import whalepidog.watchdog.WatchdogController;
import whalepidog.udp.PamUDP;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Consumer;

/**
 * Handles the {@code deletewav} and {@code deletedatabase} commands for
 * Bluetooth (BLE and Serial SPP) and the terminal UI.
 *
 * <p>Each command uses a two-step confirmation flow:
 * <ul>
 *   <li><b>{@code deletewav}</b> — returns a confirmation prompt asking the
 *       user to confirm by sending {@code deletewav yes}.</li>
 *   <li><b>{@code deletewav yes}</b> (or any positive response such as
 *       {@code y}, {@code confirm}) — deletes everything in the configured
 *       {@code wavFolder}.</li>
 *   <li><b>{@code deletedatabase}</b> — returns a confirmation prompt asking
 *       the user to confirm by sending {@code deletedatabase yes}.</li>
 *   <li><b>{@code deletedatabase yes}</b> — deletes the database and recreates
 *       an empty one using {@code sqlite3 <db> "VACUUM;"}.</li>
 * </ul>
 *
 * <p>PAMGuard must be stopped before data can be deleted.
 */
public class DeleteDataHandler {

    private DeleteDataHandler() {} // utility class

    // ── deletewav ────────────────────────────────────────────────────────────

    /**
     * Handle a {@code deletewav} command string.
     *
     * @param command          the raw command (e.g. "deletewav" or "deletewav yes")
     * @param watchdog         the watchdog controller (for process / settings access)
     * @param progressCallback receives human-readable progress lines during deletion;
     *                         may be {@code null}
     * @return a response string to send back to the client
     */
    public static String handleDeleteWav(String command, WatchdogController watchdog,
                                         Consumer<String> progressCallback) {
        Consumer<String> progress = progressCallback != null ? progressCallback : s -> {};

        // 1. Check PAMGuard is not actively processing data
        if (watchdog.getPamProcess().isAlive() && watchdog.getPamStatus() == PamUDP.PAM_RUNNING) {
            return "ERROR: PAMGuard is currently processing data. Stop PAMGuard first (send 'stop').";
        }

        // 2. Parse command: "deletewav" vs "deletewav yes"
        String arg = command.trim().substring("deletewav".length()).trim().toLowerCase();

        WhalePIDogSettings settings = watchdog.getSettings();
        String wavFolder = settings.getWavFolder();

        if (arg.isEmpty()) {
            // Confirmation prompt
            if (wavFolder == null || wavFolder.isBlank()) {
                return "ERROR: No wavFolder configured in settings. Nothing to delete.";
            }
            return "WARNING: This will permanently delete all files in:\n"
                 + "  " + wavFolder + "\n"
                 + "\nAre you sure? Send 'deletewav yes' to confirm.";
        }

        // Check for positive confirmation
        if (!isPositiveResponse(arg)) {
            return "Delete cancelled.";
        }

        // 3. Validate wavFolder
        if (wavFolder == null || wavFolder.isBlank()) {
            return "ERROR: No wavFolder configured in settings. Nothing to delete.";
        }

        Path wavPath = Path.of(wavFolder);
        if (!Files.isDirectory(wavPath)) {
            return "ERROR: wavFolder does not exist: " + wavFolder;
        }

        // 4. Delete wav folder contents
        progress.accept("Deleting contents of " + wavFolder + " ...");
        try {
            deleteDirectoryContents(wavPath, progress);
            progress.accept("Recordings folder cleared.");
        } catch (IOException e) {
            return "ERROR: Failed to delete recordings: " + e.getMessage();
        }

        return "Delete completed successfully. All recordings have been removed.";
    }

    // ── deletedatabase ───────────────────────────────────────────────────────

    /**
     * Handle a {@code deletedatabase} command string.
     *
     * @param command          the raw command (e.g. "deletedatabase" or "deletedatabase yes")
     * @param watchdog         the watchdog controller (for process / settings access)
     * @param progressCallback receives human-readable progress lines during deletion;
     *                         may be {@code null}
     * @return a response string to send back to the client
     */
    public static String handleDeleteDatabase(String command, WatchdogController watchdog,
                                              Consumer<String> progressCallback) {
        Consumer<String> progress = progressCallback != null ? progressCallback : s -> {};

        // 1. Check PAMGuard is not actively processing data
        if (watchdog.getPamProcess().isAlive() && watchdog.getPamStatus() == PamUDP.PAM_RUNNING) {
            return "ERROR: PAMGuard is currently processing data. Stop PAMGuard first (send 'stop').";
        }

        // 2. Parse command: "deletedatabase" vs "deletedatabase yes"
        String arg = command.trim().substring("deletedatabase".length()).trim().toLowerCase();

        WhalePIDogSettings settings = watchdog.getSettings();
        String database = settings.getDatabase();

        if (arg.isEmpty()) {
            // Confirmation prompt
            if (database == null || database.isBlank()) {
                return "ERROR: No database configured in settings. Nothing to delete.";
            }
            return "WARNING: This will permanently delete and recreate a blank database:\n"
                 + "  " + database + "\n"
                 + "\nAre you sure? Send 'deletedatabase yes' to confirm.";
        }

        // Check for positive confirmation
        if (!isPositiveResponse(arg)) {
            return "Delete cancelled.";
        }

        // 3. Validate database setting
        if (database == null || database.isBlank()) {
            return "ERROR: No database configured in settings. Nothing to delete.";
        }

        // 4. Delete and recreate database
        progress.accept("Recreating blank database: " + database + " ...");
        try {
            // Delete the existing database file if it exists
            Files.deleteIfExists(Path.of(database));
            // Also delete WAL and SHM journal files if present
            Files.deleteIfExists(Path.of(database + "-wal"));
            Files.deleteIfExists(Path.of(database + "-shm"));
            Files.deleteIfExists(Path.of(database + "-journal"));

            // Create a blank database using sqlite3 VACUUM
            ProcessBuilder pb = new ProcessBuilder("sqlite3", database, "VACUUM;");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                String output = new String(proc.getInputStream().readAllBytes()).trim();
                return "ERROR: sqlite3 VACUUM failed (exit " + exitCode + "): " + output;
            }
            progress.accept("Blank database created.");
        } catch (IOException e) {
            return "ERROR: Failed to recreate database: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR: Database recreation interrupted.";
        }

        return "Delete completed successfully. Database has been recreated blank.";
    }

    // ── Shared helpers ───────────────────────────────────────────────────────

    /**
     * Check if a response string is a positive confirmation.
     */
    static boolean isPositiveResponse(String response) {
        return switch (response) {
            case "yes", "y", "confirm", "ok", "sure", "true" -> true;
            default -> false;
        };
    }

    /**
     * Delete all contents of a directory (files and subdirectories) but keep
     * the directory itself.
     */
    static void deleteDirectoryContents(Path directory, Consumer<String> progress)
            throws IOException {
        File[] children = directory.toFile().listFiles();
        if (children == null) return;

        int total = children.length;
        int deleted = 0;
        for (File child : children) {
            Path childPath = child.toPath();
            if (Files.isDirectory(childPath)) {
                // Recursively delete entire sub-directory tree
                Files.walkFileTree(childPath, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                            throws IOException {
                        if (exc != null) throw exc;
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                Files.delete(childPath);
            }
            deleted++;
            if (total > 10 && deleted % 10 == 0) {
                progress.accept("  Deleted " + deleted + "/" + total + " items ...");
            }
        }
        progress.accept("  Deleted " + deleted + " items total.");
    }
}