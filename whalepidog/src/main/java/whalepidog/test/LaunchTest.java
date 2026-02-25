package whalepidog.test;

import whalepidog.settings.SettingsManager;
import whalepidog.settings.WhalePIDogSettings;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Simple smoke-test: reads a JSON settings file and launches PAMGuard,
 * piping all stdout and stderr directly to this terminal.
 *
 * <p>Usage:
 * <pre>
 *   java -cp whalepidog.jar whalepidog.test.LaunchTest &lt;settings.json&gt;
 * </pre>
 *
 * <p>Press Ctrl-C to stop PAMGuard and exit.
 */
public class LaunchTest {

    public static void main(String[] args) throws Exception {

        // ── 1. Load settings ────────────────────────────────────────────────
        if (args.length < 1) {
            System.err.println("Usage: LaunchTest <settings.json>");
            System.exit(1);
        }

        File settingsFile = new File(args[0]);
        if (!settingsFile.exists()) {
            System.err.println("Settings file not found: " + settingsFile.getAbsolutePath());
            System.exit(1);
        }

        WhalePIDogSettings s = SettingsManager.load(settingsFile);
        if (s == null) {
            System.err.println("Failed to parse settings file.");
            System.exit(1);
        }

        // ── 2. Print what we are about to run ───────────────────────────────
        System.out.println("=================================================");
        System.out.println("  WhalePIDog – PAMGuard Launch Test");
        System.out.println("=================================================");
        System.out.println("  jar      : " + s.getPamguardJar());
        System.out.println("  psfx     : " + s.getPsfxFile());
        System.out.println("  wavFolder: " + s.getWavFolder());
        System.out.println("  database : " + s.getDatabase());
        System.out.println("  libFolder: " + s.getLibFolder());
        System.out.println("  jre      : " + s.getJre());
        System.out.println("  UDP port : " + s.getUdpPort());
        System.out.println("  deploy   : " + s.isDeploy());
        System.out.println("  noGui    : " + s.isNoGui());
        System.out.println("  mxMemory : " + s.getMxMemory() + " MB");
        System.out.println("  msMemory : " + s.getMsMemory() + " MB");
        System.out.println("=================================================");

        // ── 3. Build command line ────────────────────────────────────────────
        String cmd = buildCommandLine(s);
        System.out.println("Command line:");
        System.out.println("  " + cmd);
        System.out.println("=================================================");
        System.out.println("PAMGuard output follows (Ctrl-C to stop):");
        System.out.println();

        // ── 4. Launch ────────────────────────────────────────────────────────
        String workDir = s.getWorkingFolder();
        File dir = (workDir != null && !workDir.isBlank())
                   ? new File(workDir)
                   : new File(s.getPamguardJar()).getParentFile();

        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", cmd);
        if (dir != null && dir.exists()) {
            pb.directory(dir);
        }
        // Merge stderr into stdout so we see everything in one stream
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Shutdown hook so Ctrl-C kills PAMGuard cleanly
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[LaunchTest] Shutdown – destroying PAMGuard process.");
            process.destroyForcibly();
        }, "shutdown-hook"));

        // ── 5. Stream output to terminal ─────────────────────────────────────
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                System.out.println(line);
            }
        }

        int exit = process.waitFor();
        System.out.println("[LaunchTest] PAMGuard process exited with code: " + exit);
    }

    // ── Command-line builder (mirrors PamProcess.buildCommandLine) ───────────

    private static String buildCommandLine(WhalePIDogSettings s) {
        String jre = s.getJre();
        if (jre == null || jre.isBlank()) jre = "java";

        StringBuilder sb = new StringBuilder();
        sb.append(jre);
        sb.append(" -Dname=AutoPamguard");
        sb.append(" -Xms").append(s.getMsMemory()).append("m");
        sb.append(" -Xmx").append(s.getMxMemory()).append("m");
        sb.append(" -Djava.library.path=").append(quote(s.getLibFolder()));

        String vmOpts = s.getOtherVMOptions();
        if (vmOpts != null && !vmOpts.isBlank()) sb.append(' ').append(vmOpts);

        sb.append(" -jar ").append(quote(s.getPamguardJar()));

        String psf = s.getPsfxFile();
        if (psf != null && !psf.isBlank())
            sb.append(" -psf ").append(quote(psf));

        int port = s.getUdpPort();
        if (port > 0)
            sb.append(" -port ").append(port);

        String wav = s.getWavFolder();
        if (wav != null && !wav.isBlank())
            sb.append(" -wavfolder ").append(quote(wav));

        String db = s.getDatabase();
        if (db != null && !db.isBlank())
            sb.append(" -database ").append(quote(db));

        if (s.isNoGui())
            sb.append(" -nogui");

        String other = s.getOtherOptions();
        if (other != null && !other.isBlank()) sb.append(' ').append(other);

        return sb.toString();
    }

    private static String quote(String s) {
        if (s == null || s.isBlank()) return "\"\"";
        return '"' + s + '"';
    }
}
