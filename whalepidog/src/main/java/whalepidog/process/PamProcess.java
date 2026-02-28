package whalepidog.process;

import whalepidog.settings.WhalePIDogSettings;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
            broadcastLine("[WhalePIDog] Destroying PAMGuard process.");
            p.destroyForcibly();
            process = null;
        }
    }

    /**
     * @return {@code true} if the PAMGuard OS process is still alive
     */
    public boolean isAlive() {
        Process p = process;
        return p != null && p.isAlive();
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

        // PAMGuard-specific arguments
        String psf = settings.getPsfxFile();
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

        // nogui flag – only set if running headless
        if (settings.isNoGui())
            sb.append(" -nogui");

        String other = settings.getOtherOptions();
        if (other != null && !other.isBlank()) sb.append(' ').append(other);

        return sb.toString();
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
