package whalepidog.settings;

import whalepidog.bluetooth.BluetoothSettings;

/**
 * Settings for the WhalePIDog watchdog application.
 * These are loaded from a JSON file at startup.
 */
public class WhalePIDogSettings {

    // ── PAMGuard launch parameters ──────────────────────────────────────────

    /** Path to the PAMGuard jar file. */
    private String pamguardJar = "";

    /** Path to the PAMGuard settings file (.psfx). */
    private String psfxFile = "";

    /** Path to the recordings/wav-file folder (-wavfolder). */
    private String wavFolder = "";

    /** Path to the database file (-database). */
    private String database = "";

    /** Path to the native library folder (-Djava.library.path). */
    private String libFolder = "lib64";

    /** JRE / java executable to use when launching PAMGuard. Defaults to "java" on PATH. */
    private String jre = "java";

    /** Maximum JVM heap in megabytes (-Xmx). */
    private int mxMemory = 4096;

    /** Initial / minimum JVM heap in megabytes (-Xms). */
    private int msMemory = 2048;

    /** Additional JVM options (e.g. "-Dname=AutoPamguard"). */
    private String otherVMOptions = "";

    /** Additional PAMGuard command-line options. */
    private String otherOptions = "";

    /**
     * If {@code true}, PAMGuard is launched with {@code -nogui} so it runs
     * headless.  Set to {@code false} to allow the PAMGuard GUI to open.
     * Default: {@code true}.
     */
    private boolean noGui = true;

    /**
     * If {@code true}, PAMGuard will be sent the "start" command after launch
     * so that it begins acquiring/processing data.  If {@code false} PAMGuard
     * is started but kept in idle mode.
     */
    private boolean deploy = true;

    // ── UDP communication ────────────────────────────────────────────────────

    /** UDP port PAMGuard is listening on. */
    private int udpPort = 8000;

    // ── Watchdog timing ──────────────────────────────────────────────────────

    /**
     * Interval in seconds between watchdog health checks (ping / status).
     * Default: 30 s.
     */
    private int checkIntervalSeconds = 30;

    /**
     * Interval in seconds between summary display refreshes in the terminal.
     * Default: 5 s.
     */
    private int summaryIntervalSeconds = 5;

    /**
     * Seconds to wait after PAMGuard launches before the first status check.
     * Default: 10 s.
     */
    private int startWaitSeconds = 10;

    // ── Miscellaneous ────────────────────────────────────────────────────────

    /** Working directory for the PAMGuard process. If empty, the jar directory is used. */
    private String workingFolder = "";

    // ── Bluetooth settings ───────────────────────────────────────────────────

    /** Bluetooth communication settings. */
    private BluetoothSettings bluetoothSettings = new BluetoothSettings();

    // ── Getters / Setters ────────────────────────────────────────────────────

    public String getPamguardJar() { return pamguardJar; }
    public void setPamguardJar(String pamguardJar) { this.pamguardJar = pamguardJar; }

    public String getPsfxFile() { return psfxFile; }
    public void setPsfxFile(String psfxFile) { this.psfxFile = psfxFile; }

    public String getWavFolder() { return wavFolder; }
    public void setWavFolder(String wavFolder) { this.wavFolder = wavFolder; }

    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }

    public String getLibFolder() { return libFolder; }
    public void setLibFolder(String libFolder) { this.libFolder = libFolder; }

    public String getJre() { return jre; }
    public void setJre(String jre) { this.jre = jre; }

    public int getMxMemory() { return mxMemory; }
    public void setMxMemory(int mxMemory) { this.mxMemory = mxMemory; }

    public int getMsMemory() { return msMemory; }
    public void setMsMemory(int msMemory) { this.msMemory = msMemory; }

    public String getOtherVMOptions() { return otherVMOptions == null ? "" : otherVMOptions; }
    public void setOtherVMOptions(String otherVMOptions) { this.otherVMOptions = otherVMOptions; }

    public String getOtherOptions() { return otherOptions == null ? "" : otherOptions; }
    public void setOtherOptions(String otherOptions) { this.otherOptions = otherOptions; }

    public boolean isNoGui() { return noGui; }
    public void setNoGui(boolean noGui) { this.noGui = noGui; }

    public boolean isDeploy() { return deploy; }
    public void setDeploy(boolean deploy) { this.deploy = deploy; }

    public int getUdpPort() { return udpPort; }
    public void setUdpPort(int udpPort) { this.udpPort = udpPort; }

    public int getCheckIntervalSeconds() { return checkIntervalSeconds; }
    public void setCheckIntervalSeconds(int checkIntervalSeconds) { this.checkIntervalSeconds = checkIntervalSeconds; }

    public int getSummaryIntervalSeconds() { return summaryIntervalSeconds; }
    public void setSummaryIntervalSeconds(int summaryIntervalSeconds) { this.summaryIntervalSeconds = summaryIntervalSeconds; }

    public int getStartWaitSeconds() { return startWaitSeconds; }
    public void setStartWaitSeconds(int startWaitSeconds) { this.startWaitSeconds = startWaitSeconds; }

    public String getWorkingFolder() { return workingFolder; }
    public void setWorkingFolder(String workingFolder) { this.workingFolder = workingFolder; }

    public BluetoothSettings getBluetoothSettings() { return bluetoothSettings; }
    public void setBluetoothSettings(BluetoothSettings bluetoothSettings) { 
        this.bluetoothSettings = bluetoothSettings != null ? bluetoothSettings : new BluetoothSettings();
    }

    @Override
    public String toString() {
        return "WhalePIDogSettings{" +
                "pamguardJar='" + pamguardJar + '\'' +
                ", psfxFile='" + psfxFile + '\'' +
                ", wavFolder='" + wavFolder + '\'' +
                ", database='" + database + '\'' +
                ", libFolder='" + libFolder + '\'' +
                ", jre='" + jre + '\'' +
                ", mxMemory=" + mxMemory +
                ", deploy=" + deploy +
                ", noGui=" + noGui +
                ", udpPort=" + udpPort +
                ", deploy=" + deploy +
                ", checkIntervalSeconds=" + checkIntervalSeconds +
                ", summaryIntervalSeconds=" + summaryIntervalSeconds +
                ", startWaitSeconds=" + startWaitSeconds +
                '}';
    }
}
