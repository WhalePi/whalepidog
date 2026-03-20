package whalepidog.process;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Self-contained task that copies the contents of a source directory (the
 * {@code wavFolder} setting) to a user-selected external storage device.
 *
 * <h2>Workflow</h2>
 * <ol>
 *   <li>{@link #listExternalVolumes()} — detects removable / USB block devices
 *       mounted under {@code /media} or {@code /mnt} (Linux / Raspberry Pi specific).</li>
 *   <li>{@link #validateSpace(ExternalVolume)} — compares the size of the source
 *       directory against the free space on the selected volume.</li>
 *   <li>{@link #copy(ExternalVolume)} — recursively copies every file from the
 *       source folder to a sub-folder on the target volume, reporting progress
 *       via a {@link Consumer}{@code <String>} callback.</li>
 * </ol>
 *
 * <p>Progress messages are sent through the {@code progressListener} callback
 * so they can be displayed on both the standard terminal and the Bluetooth
 * terminal simultaneously.
 *
 * <p><b>Note:</b> This class uses Linux-specific commands ({@code lsblk}) and
 * filesystem paths and is intended only for Raspberry Pi / Debian systems.
 */
public class CopyDataTask {

    /** Represents a mounted external storage volume. */
    public static class ExternalVolume {
        private final String devicePath;   // e.g. /dev/sda1
        private final String mountPoint;   // e.g. /media/pi/USBSTICK
        private final String label;        // e.g. USBSTICK
        private final long   totalBytes;
        private final long   freeBytes;

        public ExternalVolume(String devicePath, String mountPoint, String label,
                              long totalBytes, long freeBytes) {
            this.devicePath = devicePath;
            this.mountPoint = mountPoint;
            this.label      = label;
            this.totalBytes = totalBytes;
            this.freeBytes  = freeBytes;
        }

        public String getDevicePath() { return devicePath; }
        public String getMountPoint() { return mountPoint; }
        public String getLabel()      { return label; }
        public long   getTotalBytes() { return totalBytes; }
        public long   getFreeBytes()  { return freeBytes; }

        /** Human-readable summary shown to the user when listing volumes. */
        public String toDisplayString() {
            return String.format("%s  [%s]  %s free / %s total  (%s)",
                    label.isEmpty() ? mountPoint : label,
                    devicePath,
                    humanSize(freeBytes),
                    humanSize(totalBytes),
                    mountPoint);
        }

        @Override
        public String toString() { return toDisplayString(); }
    }

    // ── Fields ───────────────────────────────────────────────────────────────

    private final Path             sourceFolder;
    private final Consumer<String> progressListener;
    private final AtomicBoolean    cancelled = new AtomicBoolean(false);

    /**
     * @param sourceFolder     the directory whose contents should be copied
     *                         (typically the {@code wavFolder} from settings)
     * @param progressListener callback that receives human-readable progress
     *                         messages; may be {@code null}
     */
    public CopyDataTask(Path sourceFolder, Consumer<String> progressListener) {
        this.sourceFolder     = sourceFolder;
        this.progressListener = progressListener != null ? progressListener : s -> {};
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Detect external (removable / USB) volumes that are currently mounted.
     *
     * <p>Uses {@code lsblk} to enumerate block devices and filters for those
     * mounted under {@code /media} or {@code /mnt}.  The root filesystem and
     * boot partitions are excluded.
     *
     * @return a (possibly empty) list of mounted external volumes
     */
    public static List<ExternalVolume> listExternalVolumes() {
        List<ExternalVolume> volumes = new ArrayList<>();

        try {
            // lsblk -Pno NAME,MOUNTPOINT,LABEL,SIZE,FSSIZE,FSAVAIL,TYPE
            // -P  = key="value" pairs (easy to parse)
            // -n  = no header
            // -o  = selected columns
            // -b  = bytes for size fields
            ProcessBuilder pb = new ProcessBuilder(
                    "lsblk", "-Pbno", "NAME,MOUNTPOINT,LABEL,FSSIZE,FSAVAIL,TYPE");
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String name       = extractField(line, "NAME");
                    String mountpoint = extractField(line, "MOUNTPOINT");
                    String label      = extractField(line, "LABEL");
                    String fssize     = extractField(line, "FSSIZE");
                    String fsavail    = extractField(line, "FSAVAIL");
                    String type       = extractField(line, "TYPE");

                    // Only consider partitions (type "part") that are mounted
                    if (mountpoint.isEmpty()) continue;
                    if (!type.equals("part")) continue;

                    // Filter: only /media/* or /mnt/* paths (external drives)
                    if (!mountpoint.startsWith("/media") && !mountpoint.startsWith("/mnt")) continue;

                    long total = parseLong(fssize);
                    long free  = parseLong(fsavail);

                    volumes.add(new ExternalVolume(
                            "/dev/" + name, mountpoint, label, total, free));
                }
            }
            proc.waitFor();

        } catch (Exception e) {
            // Fall back: scan /media and /mnt manually
            scanMountPoints(volumes, "/media");
            scanMountPoints(volumes, "/mnt");
        }

        return volumes;
    }

    /**
     * Check whether the selected volume has enough free space for the
     * contents of the source folder.
     *
     * @param volume the target volume
     * @return {@code null} if there is enough space, or an error message
     *         explaining the shortfall
     */
    public String validateSpace(ExternalVolume volume) {
        long needed = directorySize(sourceFolder);
        if (needed < 0) {
            return "Cannot calculate size of source folder: " + sourceFolder;
        }
        long free = volume.getFreeBytes();
        // Re-check free space from the filesystem in case lsblk data is stale
        try {
            FileStore store = Files.getFileStore(Path.of(volume.getMountPoint()));
            free = store.getUsableSpace();
        } catch (IOException ignored) {}

        if (needed > free) {
            return String.format(
                    "Not enough space on %s. Need %s but only %s is available.",
                    volume.getLabel().isEmpty() ? volume.getMountPoint() : volume.getLabel(),
                    humanSize(needed), humanSize(free));
        }
        return null; // OK
    }

    /**
     * Recursively copy every file from the source folder to a sub-directory
     * on the target volume.  The sub-directory is named after the source
     * folder (e.g. {@code PAMRecordings}).
     *
     * <p>Progress is reported through the {@code progressListener} supplied
     * at construction time.
     *
     * @param volume the target volume
     * @return {@code true} if the copy completed successfully
     */
    public boolean copy(ExternalVolume volume) {
        Path targetRoot = Path.of(volume.getMountPoint(), sourceFolder.getFileName().toString());

        progress("Copying " + sourceFolder + " → " + targetRoot);

        long totalBytes = directorySize(sourceFolder);
        if (totalBytes < 0) {
            progress("ERROR: Cannot determine source folder size.");
            return false;
        }
        progress("Total data to copy: " + humanSize(totalBytes));

        AtomicLong copiedBytes = new AtomicLong(0);
        AtomicLong fileCount   = new AtomicLong(0);
        long startMs = System.currentTimeMillis();

        // Track last progress report time to avoid flooding
        final long[] lastReport = {0};

        try {
            Files.walkFileTree(sourceFolder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    if (cancelled.get()) return FileVisitResult.TERMINATE;
                    Path relative = sourceFolder.relativize(dir);
                    Path target   = targetRoot.resolve(relative);
                    Files.createDirectories(target);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    if (cancelled.get()) return FileVisitResult.TERMINATE;

                    Path relative = sourceFolder.relativize(file);
                    Path target   = targetRoot.resolve(relative);

                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);

                    long size = attrs.size();
                    long copied = copiedBytes.addAndGet(size);
                    fileCount.incrementAndGet();

                    // Report progress at most every 2 seconds
                    long now = System.currentTimeMillis();
                    if (now - lastReport[0] >= 2000) {
                        lastReport[0] = now;
                        int pct = totalBytes > 0
                                ? (int) (copied * 100 / totalBytes) : 100;
                        long elapsed = now - startMs;
                        String rate = elapsed > 0
                                ? humanSize(copied * 1000 / elapsed) + "/s"
                                : "---";
                        progress(String.format("  %3d%%  %s / %s  (%d files)  %s",
                                pct, humanSize(copied), humanSize(totalBytes),
                                fileCount.get(), rate));
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    progress("WARNING: Failed to copy " + file + ": " + exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });

        } catch (IOException e) {
            progress("ERROR during copy: " + e.getMessage());
            return false;
        }

        if (cancelled.get()) {
            progress("Copy CANCELLED by user.");
            return false;
        }

        long elapsedSec = (System.currentTimeMillis() - startMs) / 1000;
        progress(String.format("Copy complete: %d files, %s in %ds → %s",
                fileCount.get(), humanSize(copiedBytes.get()), elapsedSec, targetRoot));
        return true;
    }

    /**
     * Request cancellation of a running copy.  The copy loop checks this
     * flag between files.
     */
    public void cancel() {
        cancelled.set(true);
    }

    /** @return {@code true} if a cancellation has been requested */
    public boolean isCancelled() {
        return cancelled.get();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void progress(String message) {
        progressListener.accept(message);
    }

    /**
     * Calculate the total size in bytes of every file under {@code dir}.
     *
     * @return total bytes, or {@code -1} on error
     */
    static long directorySize(Path dir) {
        if (!Files.isDirectory(dir)) return -1;
        AtomicLong size = new AtomicLong(0);
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    size.addAndGet(attrs.size());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return -1;
        }
        return size.get();
    }

    /**
     * Fallback volume scanner: list sub-directories of the given root that
     * look like mount points.
     */
    private static void scanMountPoints(List<ExternalVolume> out, String root) {
        File rootDir = new File(root);
        if (!rootDir.isDirectory()) return;

        // /media/<user>/<volume> or /mnt/<volume>
        File[] children = rootDir.listFiles();
        if (children == null) return;

        for (File child : children) {
            if (!child.isDirectory()) continue;

            // /media/<user>/<volume>  → go one level deeper
            if (root.equals("/media")) {
                File[] grandchildren = child.listFiles();
                if (grandchildren == null) continue;
                for (File gc : grandchildren) {
                    if (gc.isDirectory()) {
                        addVolumeFromPath(out, gc);
                    }
                }
            } else {
                addVolumeFromPath(out, child);
            }
        }
    }

    private static void addVolumeFromPath(List<ExternalVolume> out, File dir) {
        try {
            FileStore store = Files.getFileStore(dir.toPath());
            long total = store.getTotalSpace();
            long free  = store.getUsableSpace();
            // Skip tiny or zero-size filesystems (proc, sys, etc.)
            if (total <= 0) return;
            out.add(new ExternalVolume(
                    "unknown", dir.getAbsolutePath(), dir.getName(), total, free));
        } catch (IOException ignored) {}
    }

    /**
     * Extract a field value from an lsblk {@code -P} output line.
     * Format: {@code KEY="value" KEY2="value2" ...}
     */
    static String extractField(String line, String key) {
        String prefix = key + "=\"";
        int start = line.indexOf(prefix);
        if (start < 0) return "";
        start += prefix.length();
        int end = line.indexOf('"', start);
        if (end < 0) return "";
        return line.substring(start, end).trim();
    }

    private static long parseLong(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    /**
     * Format a byte count as a human-readable string (e.g. "1.5 GB").
     */
    static String humanSize(long bytes) {
        if (bytes < 0) return "? B";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        double gb = mb / 1024.0;
        if (gb < 1024) return String.format("%.1f GB", gb);
        double tb = gb / 1024.0;
        return String.format("%.2f TB", tb);
    }
}
