package whalepidog.watchdog;

import whalepidog.process.PamProcess;
import whalepidog.settings.SettingsManager;
import whalepidog.settings.WhalePIDogSettings;
import whalepidog.udp.PamUDP;

import java.io.File;
import java.net.SocketException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Core watchdog logic.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Launch PAMGuard via {@link PamProcess}.</li>
 *   <li>Wait for PAMGuard to initialise, then optionally send {@code start}.</li>
 *   <li>Periodically ping PAMGuard (interval = {@code checkIntervalSeconds}).
 *       If it does not respond, kill and re-launch.</li>
 *   <li>Periodically fetch a {@code summary} (interval = {@code summaryIntervalSeconds})
 *       and make it available to the terminal UI.</li>
 * </ol>
 */
public class WatchdogController {

    // ── Watchdog state machine ───────────────────────────────────────────────
    public enum State {
        STOPPED, STARTING, WAITING_FOR_INIT, RUNNING, RESTARTING, ERROR
    }

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final WhalePIDogSettings    settings;
    private final File                  settingsFile;
    private final PamProcess            pamProcess;
    private       PamUDP                udp;

    /** Scheduler for health-check and summary polling tasks. */
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "watchdog-scheduler");
                t.setDaemon(true);
                return t;
            });

    private ScheduledFuture<?> checkFuture;
    private ScheduledFuture<?> summaryFuture;

    // ── Shared state (updated by scheduler, read by UI) ──────────────────────
    private final AtomicReference<State>  state         = new AtomicReference<>(State.STOPPED);
    private final AtomicReference<String> lastSummary   = new AtomicReference<>("No summary yet.");
    private final AtomicReference<String> lastCheckTime = new AtomicReference<>("Never");
    private final AtomicInteger           pamStatus     = new AtomicInteger(-1);
    private final AtomicInteger           restartCount  = new AtomicInteger(0);
    private final AtomicLong              startTime     = new AtomicLong(0);

    /** Listeners notified on state changes (called on scheduler thread). */
    private Consumer<State>  stateListener;
    /** Listeners notified on log messages (called on scheduler thread). */
    private Consumer<String> logListener;

    public WatchdogController(WhalePIDogSettings settings, File settingsFile) {
        this.settings     = settings;
        this.settingsFile = settingsFile;
        this.pamProcess   = new PamProcess(settings);
    }

    // ── Start / Stop ─────────────────────────────────────────────────────────

    /**
     * Start the watchdog: launch PAMGuard and begin scheduling health checks.
     */
    public synchronized void start() {
        if (state.get() != State.STOPPED && state.get() != State.ERROR) {
            log("Watchdog already running – ignoring start()");
            return;
        }

        try {
            udp = new PamUDP(settings.getUdpPort());
        } catch (SocketException e) {
            setState(State.ERROR);
            log("ERROR: Cannot open UDP socket – " + e.getMessage());
            return;
        }

        setState(State.STARTING);
        startTime.set(System.currentTimeMillis());
        log("=== WhalePIDog starting ===");
        log("Settings: " + settings);

        // Launch PAMGuard process
        if (!pamProcess.launch()) {
            setState(State.ERROR);
            log("ERROR: Failed to launch PAMGuard process.");
            return;
        }

        setState(State.WAITING_FOR_INIT);

        // Wait for init in a background thread, then schedule periodic tasks
        Thread initThread = new Thread(this::waitForInitAndSchedule, "watchdog-init");
        initThread.setDaemon(true);
        initThread.start();
    }

    /**
     * Stop the watchdog (cancel scheduled tasks, optionally kill PAMGuard).
     *
     * @param killPamguard if {@code true}, also kill the PAMGuard process
     */
    public synchronized void stop(boolean killPamguard) {
        cancelScheduledTasks();
        if (killPamguard) {
            log("Sending Exit command to PAMGuard...");
            if (udp != null) udp.sendExit(2000);
            pamProcess.kill();
        }
        if (udp != null) {
            udp.close();
            udp = null;
        }
        setState(State.STOPPED);
        log("=== WhalePIDog stopped ===");
    }

    // ── Init wait ────────────────────────────────────────────────────────────

    private void waitForInitAndSchedule() {
        int waitSec = settings.getStartWaitSeconds();
        log(String.format("Waiting %d s for PAMGuard to initialise...", waitSec));

        long deadline = System.currentTimeMillis() + waitSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            sleep(1000);
            if (!pamProcess.isAlive()) {
                log("ERROR: PAMGuard process died during startup.");
                setState(State.ERROR);
                return;
            }
        }

        // Poll until PAMGuard responds to a ping (max 60 s extra)
        long pingDeadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < pingDeadline) {
            if (udp != null && udp.ping(2000)) break;
            sleep(2000);
        }

        if (udp == null || !udp.ping(2000)) {
            log("WARNING: PAMGuard did not respond to ping after startup.");
        } else {
            log("PAMGuard responded to ping – initialisation complete.");
        }

        // Optionally send "start" command
        if (settings.isDeploy()) {
            log("deploy=true  – sending 'start' command to PAMGuard.");
            if (udp != null) {
                String resp = udp.sendStart(5000);
                log("start command response: " + resp);
            }
        } else {
            log("deploy=false – PAMGuard started but NOT set to run.");
        }

        setState(State.RUNNING);
        schedulePeriodicTasks();
    }

    // ── Periodic tasks ────────────────────────────────────────────────────────

    private void schedulePeriodicTasks() {
        long checkSec   = settings.getCheckIntervalSeconds();
        long summarySec = settings.getSummaryIntervalSeconds();

        log(String.format("Scheduling health-check every %d s, summary every %d s.", checkSec, summarySec));

        checkFuture = scheduler.scheduleAtFixedRate(
                this::doHealthCheck, checkSec, checkSec, TimeUnit.SECONDS);

        summaryFuture = scheduler.scheduleAtFixedRate(
                this::doSummary, 2, summarySec, TimeUnit.SECONDS);
    }

    private void cancelScheduledTasks() {
        if (checkFuture   != null) { checkFuture.cancel(false);   checkFuture   = null; }
        if (summaryFuture != null) { summaryFuture.cancel(false); summaryFuture = null; }
    }

    // ── Health check ─────────────────────────────────────────────────────────

    private void doHealthCheck() {
        lastCheckTime.set(TS_FMT.format(Instant.now()));

        if (!pamProcess.isAlive()) {
            log("PAMGuard process is NOT alive – restarting.");
            handleRestart();
            return;
        }

        if (udp == null || !udp.ping(3000)) {
            log("PAMGuard did not respond to ping – restarting.");
            handleRestart();
            return;
        }

        int status = udp.getStatus(3000);
        pamStatus.set(status);

        String statusName = statusName(status);
        log(String.format("[HealthCheck] ping OK  status=%d (%s)", status, statusName));
    }

    private void handleRestart() {
        setState(State.RESTARTING);
        cancelScheduledTasks();
        restartCount.incrementAndGet();

        // Try a graceful exit first
        if (udp != null) udp.sendExit(2000);
        sleep(2000);
        pamProcess.kill();
        sleep(2000);

        log(String.format("Restart #%d – re-launching PAMGuard...", restartCount.get()));

        if (!pamProcess.launch()) {
            setState(State.ERROR);
            log("ERROR: Failed to re-launch PAMGuard after watchdog restart.");
            return;
        }

        setState(State.WAITING_FOR_INIT);

        Thread t = new Thread(this::waitForInitAndSchedule, "watchdog-restart");
        t.setDaemon(true);
        t.start();
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    private void doSummary() {
        if (udp == null) return;
        String s = udp.getSummary(3000);
        if (s != null && !s.isBlank()) {
            lastSummary.set(s.trim());
        } else {
            // keep the previous summary but note the fetch failed
            lastSummary.set(lastSummary.get()); // no-op – just leave it
        }
    }

    // ── Accessors for the UI ─────────────────────────────────────────────────

    /**
     * Send any raw UDP command string to PAMGuard on behalf of the user.
     *
     * <p>If the command is {@code Status} (case-insensitive) the response is
     * parsed and {@link #getPamStatus()} is updated.  If the command is
     * {@code summary} the response is stored and {@link #getLastSummary()}
     * is updated.  All other commands return the raw response string.
     *
     * <p>If the command is {@code start}, the {@code deploy} setting is set
     * to {@code true} and saved.  If the command is {@code stop}, the
     * {@code deploy} setting is set to {@code false} and saved.  This ensures
     * PAMGuard maintains the user's desired state even after a Pi restart.
     *
     * @param command   the exact command string to send
     * @param timeoutMs milliseconds to wait for a reply
     * @return the raw response from PAMGuard, or {@code null} on timeout / error
     */
    public String sendCommandAndUpdate(String command, int timeoutMs) {
        if (udp == null) {
            log("Cannot send command – UDP socket not open.");
            return null;
        }

        // Reject anything that isn't a recognised PAMGuard command.
        // An unknown string would be sent to PAMGuard, which may ignore it,
        // but more importantly the socket would then block waiting for a reply
        // that never comes – previously this caused the watchdog to miss its
        // health-check ping and falsely restart PAMGuard.
        if (!PamUDP.isKnownCommand(command)) {
            String msg = "Unknown command \"" + command + "\" – not sent. "
                       + "Known: " + String.join(", ", PamUDP.ALL_KNOWN);
            log("[Manual] " + msg);
            return msg;  // return as a displayable message, not null
        }

        // Use the dedicated command socket so a long timeout never delays the
        // watchdog scheduler's health-check pings on the watchdog socket.
        String response = udp.sendManualCommand(command, timeoutMs);

        log(String.format("[Manual] >> %s  << %s",
                command, response != null ? response : "(no response / timeout)"));

        if (response != null) {
            if (command.equalsIgnoreCase(PamUDP.CMD_STATUS)) {
                try {
                    int code = Integer.parseInt(response.substring(7).trim());
                    pamStatus.set(code);
                } catch (Exception ignored) {}
            } else if (command.equalsIgnoreCase(PamUDP.CMD_SUMMARY)) {
                lastSummary.set(response.trim());
            } else if (command.equalsIgnoreCase(PamUDP.CMD_START)) {
                // User manually started PAMGuard – persist this choice
                settings.setDeploy(true);
                saveSettings();
                log("[Deploy] deploy set to TRUE and saved (PAMGuard will auto-start on restart).");
            } else if (command.equalsIgnoreCase(PamUDP.CMD_STOP)) {
                // User manually stopped PAMGuard – persist this choice
                settings.setDeploy(false);
                saveSettings();
                log("[Deploy] deploy set to FALSE and saved (PAMGuard will NOT auto-start on restart).");
            }
        }

        return response;
    }

    public State  getState()         { return state.get(); }
    public String getLastSummary()   { return lastSummary.get(); }
    public String getLastCheckTime() { return lastCheckTime.get(); }
    public int    getPamStatus()     { return pamStatus.get(); }
    public int    getRestartCount()  { return restartCount.get(); }
    public long   getStartTime()     { return startTime.get(); }
    public PamProcess getPamProcess(){ return pamProcess; }

    public void setStateListener(Consumer<State>  l) { this.stateListener = l; }
    public void setLogListener  (Consumer<String> l) {
        this.logListener = l;
        // NOTE: do NOT add a pamProcess.addLineListener here.
        // TerminalUI registers its own listener (appendLog) directly on the
        // PamProcess so that PAMGuard output is controlled by the renderLock.
        // Adding a second listener here would double-print every PAMGuard line.
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void saveSettings() {
        if (settingsFile == null) {
            log("[Deploy] WARNING: Cannot save settings – settingsFile is null.");
            return;
        }
        boolean ok = SettingsManager.save(settingsFile, settings);
        if (!ok) {
            log("[Deploy] ERROR: Failed to save settings to " + settingsFile.getAbsolutePath());
        }
    }

    private void setState(State s) {
        state.set(s);
        if (stateListener != null) {
            try { stateListener.accept(s); } catch (Exception ignored) {}
        }
    }

    private void log(String msg) {
        String ts  = TS_FMT.format(Instant.now());
        String out = "[" + ts + "] " + msg;
        // Only route through the logListener (which the UI controls).
        // Do NOT call System.out.println here – that would bypass the UI's
        // renderLock and cause text to appear over the Summary / Summary-Text views.
        if (logListener != null) {
            try { logListener.accept(out); } catch (Exception ignored) {}
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static String statusName(int code) {
        return switch (code) {
            case PamUDP.PAM_IDLE         -> "IDLE";
            case PamUDP.PAM_RUNNING      -> "RUNNING";
            case PamUDP.PAM_STALLED      -> "STALLED";
            case PamUDP.PAM_INITIALISING -> "INITIALISING";
            default                      -> "UNKNOWN(" + code + ")";
        };
    }
}
