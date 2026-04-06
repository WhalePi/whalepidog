package whalepidog.process;

import whalepidog.settings.WhalePIDogSettings;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Responsible for launching and managing the PAMGuard OS process.
 *
 * <p>Captured stdout / stderr lines are broadcast to any registered
 * {@link Consumer}{@code <String>} listeners so that the terminal UI can
 * display them.
 */
public class PamProcess {

    /** Maximum number of PAMGuard output lines kept in memory. */
    private static final int MAX_LOG_LINES = 2000;

    private final WhalePIDogSettings settings;

    /** The live PAMGuard {@link Process}, or {@code null} when not running. */
    private volatile Process process;

    /** Captured PAMGuard output lines (stdout + stderr, prefixed). */
    private final List<String> outputLines = new ArrayList<>();

    /** Listeners notified whenever a new line is captured. */
    private final List<Consumer<String>> lineListeners = new CopyOnWriteArrayList<>();

    /**
     * Path to the temporary working copy of the .psfx settings file.
     * PAMGuard is launched with this copy so that the original is never
     * modified or corrupted by PAMGuard (e.g. during a crash / forced kill).
     * {@code null} when no copy exists.
     */
    private volatile File workingPsfxFile;

    public PamProcess(WhalePIDogSettings settings) {
        this.settings = settings;
    }

    // ── Process lifecycle ────────────────────────────────────────────────────

    /**
     * Launch PAMGuard.  Any previously running process is NOT killed here –
     * the caller should ensure it is dead first.
     *
     * @return {@code true} if the OS process started without error
     */
    public boolean launch() {
        // Safety guard: refuse to launch if a PAMGuard process is already alive.
        // The caller should kill the previous process first. This prevents two
        // PAMGuard instances from running simultaneously (e.g. due to a race
        // in the watchdog restart logic), which would cause database errors.
        Process existing = process;
        if (existing != null && existing.isAlive()) {
            broadcastLine("[WhalePIDog] WARNING: launch() called while PAMGuard is still alive – refusing to start a second instance.");
            return false;
        }

        // Set microphone volume to zero before starting PAMGuard
        setMicrophoneVolume();

        // Create a working copy of the .psfx file to protect the original
        createWorkingPsfxCopy();

        String commandLine = buildCommandLine();
        broadcastLine("[WhalePIDog] Launching PAMGuard: " + commandLine);

        try {
            String workDir = settings.getWorkingFolder();
            File   dir     = (workDir != null && !workDir.isBlank())
                             ? new File(workDir)
                             : new File(settings.getPamguardJar()).getParentFile();

            String[] shellCmd = {"/bin/sh", "-c", commandLine};
            ProcessBuilder pb = new ProcessBuilder(shellCmd);
            if (dir != null && dir.exists()) pb.directory(dir);
            pb.redirectErrorStream(false);

            process = pb.start();

            startCapture(process.getInputStream(), "PAM");
            startCapture(process.getErrorStream(), "ERR");

            return true;
        } catch (IOException e) {
            broadcastLine("[WhalePIDog] Launch FAILED: " + e.getMessage());
            return false;
        }
    }

    /**
     * Forcefully destroy the PAMGuard process (if still alive).
     */
    public void kill() {
        Process p = process;
        if (p != null) {
            broadcastLine("[WhalePIDog] Destroying PAMGuard process tree.");
            // PAMGuard is launched via "/bin/sh -c <cmd>", so the Process
            // handle points to the shell.  destroyForcibly() would only kill
            // the shell, potentially orphaning the child Java/PAMGuard process.
            // Kill all descendant processes first, then the shell itself.
            try {
                p.descendants().forEach(child -> {
                    broadcastLine("[WhalePIDog]   Killing child PID " + child.pid());
                    child.destroyForcibly();
                });
            } catch (Exception e) {
                broadcastLine("[WhalePIDog]   WARNING: Error killing descendants: " + e.getMessage());
            }
            p.destroyForcibly();
            try {
                // Wait briefly for the process to actually terminate so that
                // any subsequent launch() doesn't race with a dying process
                // that still holds the database lock.
                p.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            process = null;
        }
        deleteWorkingPsfxCopy();
    }

    /**
     * @return {@code true} if the PAMGuard OS process is still alive
     */
    public boolean isAlive() {
        Process p = process;
        return p != null && p.isAlive();
    }

    /**
     * Set the microphone volume to zero before starting PAMGuard.
     * This is required for certain sound cards that only work properly when
     * the Line input volume is set to zero.
     */
    private void setMicrophoneVolume() {
        try {
            broadcastLine("[WhalePIDog] Setting microphone volume to zero...");
            String[] cmd = {"/bin/sh", "-c", "amixer -c 0 set Line 0"};
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            
            // Wait for the command to complete (with timeout)
            boolean completed = p.waitFor(5, TimeUnit.SECONDS);
            if (!completed) {
                broadcastLine("[WhalePIDog] WARNING: amixer command timed out.");
                p.destroyForcibly();
            } else {
                int exitCode = p.exitValue();
                if (exitCode == 0) {
                    broadcastLine("[WhalePIDog] Microphone volume set successfully.");
                } else {
                    broadcastLine("[WhalePIDog] WARNING: amixer exited with code " + exitCode);
                }
            }
        } catch (IOException e) {
            broadcastLine("[WhalePIDog] WARNING: Failed to set microphone volume: " + e.getMessage());
        } catch (InterruptedException e) {
            broadcastLine("[WhalePIDog] WARNING: Interrupted while setting microphone volume.");
            Thread.currentThread().interrupt();
        }
    }

    // ── Command line builder ─────────────────────────────────────────────────

    /**
     * Build the full Java command line used to start PAMGuard.
     */
    public String buildCommandLine() {
        String jre = settings.getJre();
        if (jre == null || jre.isBlank()) jre = "java";

        StringBuilder sb = new StringBuilder();
        sb.append(jre);
        sb.append(" -Dname=AutoPamguard");
        sb.append(" -Xms").append(settings.getMsMemory()).append("m");
        sb.append(" -Xmx").append(settings.getMxMemory()).append("m");
        sb.append(" -Djava.library.path=").append(settings.getLibFolder());

        String vmOpts = settings.getOtherVMOptions();
        if (vmOpts != null && !vmOpts.isBlank()) sb.append(' ').append(vmOpts);

        sb.append(" -jar \"").append(settings.getPamguardJar()).append('"');

        // PAMGuard-specific arguments – use the working copy of the .psfx
        // file if available, otherwise fall back to the original path.
        String psf = (workingPsfxFile != null)
                   ? workingPsfxFile.getAbsolutePath()
                   : settings.getPsfxFile();
        if (psf != null && !psf.isBlank())
            sb.append(" -psf \"").append(psf).append('"');

        int port = settings.getUdpPort();
        if (port > 0)
            sb.append(" -port ").append(port);

        String wav = settings.getWavFolder();
        if (wav != null && !wav.isBlank())
            sb.append(" -wavfilefolder \"").append(wav).append('"');

        String db = settings.getDatabase();
        if (db != null && !db.isBlank())
            sb.append(" -databasefile \"").append(db).append('"');

        // Sound card name – tells PAMGuard which sound card to use
        String scName = settings.getSoundCardName();
        if (scName != null && !scName.isBlank())
            sb.append(" -scname \"").append(scName).append('"');

        // Recording filename prefix
        String recPrefix = settings.getRecordingPrefix();
        if (recPrefix != null && !recPrefix.isBlank())
            sb.append(" -recording.Prefix \"").append(recPrefix).append('"');

        // nogui flag – only set if running headless
        if (settings.isNoGui())
            sb.append(" -nogui");

        String other = settings.getOtherOptions();
        if (other != null && !other.isBlank()) sb.append(' ').append(other);

        return sb.toString();
    }

    // ── PSFX working-copy helpers ───────────────────────────────────────────

    /**
     * Create a temporary working copy of the original {@code .psfx} settings
     * file.  PAMGuard will be launched with this copy so that the original
     * file is never modified or corrupted – even if PAMGuard crashes or is
     * forcefully terminated.
     *
     * <p>The working copy is placed in the same directory as the original
     * with the suffix {@code _working.psfx}.
     */
    private void createWorkingPsfxCopy() {
        String psfx = settings.getPsfxFile();
        if (psfx == null || psfx.isBlank()) {
            workingPsfxFile = null;
            return;
        }

        Path original = Path.of(psfx);
        if (!Files.exists(original)) {
            broadcastLine("[WhalePIDog] WARNING: .psfx file not found: " + psfx
                        + " – will pass original path to PAMGuard.");
            workingPsfxFile = null;
            return;
        }

        try {
            // Derive the working-copy name: e.g. myConfig.psfx -> myConfig_working.psfx
            String name = original.getFileName().toString();
            String workingName;
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                workingName = name.substring(0, dot) + "_working" + name.substring(dot);
            } else {
                workingName = name + "_working";
            }

            Path workingPath = original.resolveSibling(workingName);
            Files.copy(original, workingPath, StandardCopyOption.REPLACE_EXISTING);
            workingPsfxFile = workingPath.toFile();

            broadcastLine("[WhalePIDog] Created working .psfx copy: " + workingPath);
            broadcastLine("[WhalePIDog] Original .psfx is protected: " + original);
        } catch (IOException e) {
            broadcastLine("[WhalePIDog] WARNING: Failed to create working .psfx copy: "
                        + e.getMessage() + " – will use original.");
            workingPsfxFile = null;
        }
    }

    /**
     * Delete the temporary working copy of the {@code .psfx} file, if it exists.
     */
    private void deleteWorkingPsfxCopy() {
        File wf = workingPsfxFile;
        if (wf != null) {
            try {
                Files.deleteIfExists(wf.toPath());
                broadcastLine("[WhalePIDog] Deleted working .psfx copy: " + wf.getAbsolutePath());
            } catch (IOException e) {
                broadcastLine("[WhalePIDog] WARNING: Could not delete working .psfx copy: "
                            + e.getMessage());
            }
            workingPsfxFile = null;
        }
    }

    // ── Output capture ───────────────────────────────────────────────────────

    private void startCapture(InputStream in, String prefix) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String tagged = "[" + prefix + "] " + line;
                    broadcastLine(tagged);
                }
            } catch (IOException e) {
                // stream closed – process ended
            }
        }, "pam-capture-" + prefix);
        t.setDaemon(true);
        t.start();
    }

    private synchronized void broadcastLine(String line) {
        outputLines.add(line);
        if (outputLines.size() > MAX_LOG_LINES)
            outputLines.remove(0);
        for (Consumer<String> l : lineListeners) {
            try { l.accept(line); } catch (Exception ignored) {}
        }
    }

    // ── Listeners ────────────────────────────────────────────────────────────

    /**
     * Register a listener to receive every PAMGuard output line as it arrives.
     */
    public void addLineListener(Consumer<String> listener) {
        lineListeners.add(listener);
    }

    /**
     * Return a snapshot copy of all captured output lines.
     */
    public synchronized List<String> getOutputLines() {
        return new ArrayList<>(outputLines);
    }

    /**
     * Clear the captured output buffer.
     */
    public synchronized void clearOutput() {
        outputLines.clear();
    }
}
