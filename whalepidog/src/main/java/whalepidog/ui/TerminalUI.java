package whalepidog.ui;

import whalepidog.settings.WhalePIDogSettings;
import whalepidog.udp.PamUDP;
import whalepidog.watchdog.WatchdogController;
import whalepidog.watchdog.WatchdogController.State;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Terminal-based user interface for WhalePIDog.
 *
 * <h2>Display modes</h2>
 * <ul>
 *   <li><b>SUMMARY</b> (default) – clears the screen and shows the latest PAMGuard
 *       {@code summary} response, refreshed every {@code summaryIntervalSeconds}.
 *       Does not scroll.</li>
 *   <li><b>LOG</b> – raw PAMGuard stdout/stderr lines scroll in as they arrive.</li>
 *   <li><b>COMMAND</b> – an interactive prompt for sending any UDP command to
 *       PAMGuard and viewing the response. Enter with {@code :}.</li>
 * </ul>
 *
 * <h2>Key bindings (SUMMARY / LOG mode)</h2>
 * <pre>
 *   :   Enter command mode
 *   s   Switch to SUMMARY view
 *   l   Switch to LOG view
 *   q   Quit watchdog and exit
 *   h   Help
 *   --- Quick-send shortcuts ---
 *   1   ping
 *   2   Status    (updates status display immediately)
 *   3   summary   (updates summary panel immediately)
 *   4   start
 *   5   stop
 * </pre>
 *
 * <h2>Command mode</h2>
 * <p>Type any UDP command string (e.g. {@code summary}, {@code Status},
 * {@code start}, {@code stop}, {@code Exit}, {@code ping}) and press Enter.
 * The raw PAMGuard response is printed inline and, for {@code Status} and
 * {@code summary}, the watchdog's stored values are updated so the SUMMARY
 * view reflects fresh data immediately.
 * An empty line or {@code back} returns to the previous view.
 */
public class TerminalUI {

    // ── ANSI helpers ─────────────────────────────────────────────────────────
    private static final String ANSI_RESET   = "\u001B[0m";
    private static final String ANSI_BOLD    = "\u001B[1m";
    private static final String ANSI_DIM     = "\u001B[2m";
    private static final String ANSI_GREEN   = "\u001B[32m";
    private static final String ANSI_YELLOW  = "\u001B[33m";
    private static final String ANSI_CYAN    = "\u001B[36m";
    private static final String ANSI_RED     = "\u001B[31m";
    private static final String ANSI_MAGENTA = "\u001B[35m";
    private static final String ANSI_CLEAR   = "\u001B[H\u001B[2J";
    private static final String ANSI_HOME    = "\u001B[H";
    private static final String ANSI_ERASE_DOWN = "\u001B[J";
    private static final String ANSI_HIDE_CUR   = "\u001B[?25l";
    private static final String ANSI_SHOW_CUR   = "\u001B[?25h";

    // ── Single lock that gates ALL terminal writes ────────────────────────────
    // Used only for appendLog (which runs on arbitrary capture threads) so it
    // never races with a render task executing on the uiScheduler thread.
    private final Object renderLock = new Object();

    // ── Display mode ─────────────────────────────────────────────────────────
    public enum DisplayMode { SUMMARY_VIEW, SUMMARY_TEXT, LOG, COMMAND }

    private final AtomicReference<DisplayMode> mode =
            new AtomicReference<>(DisplayMode.SUMMARY_VIEW);

    /** Mode we return to after leaving COMMAND mode. */
    private volatile DisplayMode priorMode = DisplayMode.SUMMARY_VIEW;

    // ── Dependencies ─────────────────────────────────────────────────────────
    private final WatchdogController watchdog;
    private final WhalePIDogSettings settings;
    private final SummaryView        summaryView;

    // ── Log buffer (LOG mode) ─────────────────────────────────────────────────
    private static final int MAX_LOG_LINES = 1000;
    private final List<String> logBuffer = new CopyOnWriteArrayList<>();

    // ── Last manual command result (shown in SUMMARY panels) ─────────────────
    private volatile String lastCmd     = "";
    private volatile String lastCmdResp = "";
    private volatile String lastCmdTime = "";

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Set to true after a mode switch so the next scheduled render does a
     * full screen clear before drawing, guaranteeing no stale content from the
     * previous view bleeds through.
     */
    private volatile boolean pendingClear = false;

    /** Single-thread scheduler – only one refresh task runs at a time. */
    private final ScheduledExecutorService uiScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ui-refresh");
                t.setDaemon(true);
                return t;
            });

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final String[] KNOWN_COMMANDS = PamUDP.ALL_KNOWN;

    // ── Constructor ───────────────────────────────────────────────────────────

    public TerminalUI(WatchdogController watchdog, WhalePIDogSettings settings) {
        this.watchdog    = watchdog;
        this.settings    = settings;
        this.summaryView = new SummaryView(watchdog, settings);

        // Route all incoming log lines through appendLog.
        // appendLog only echoes to stdout in LOG mode and always holds renderLock.
        watchdog.setLogListener(this::appendLog);
        watchdog.getPamProcess().addLineListener(this::appendLog);
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Start the UI refresh loop and block on keyboard input until the user quits.
     */
    public void run() {
        running.set(true);
        printHelp();

        long summaryMs = settings.getSummaryIntervalSeconds() * 1000L;
        uiScheduler.scheduleAtFixedRate(
                this::scheduledRefresh, 2000, summaryMs, TimeUnit.MILLISECONDS);

        readInput(); // blocks until quit

        uiScheduler.shutdownNow();
    }

    // ── Scheduled refresh ─────────────────────────────────────────────────────

    private void scheduledRefresh() {
        // This runs on the uiScheduler thread – the only thread that may write
        // to the terminal in SUMMARY_VIEW / SUMMARY_TEXT / LOG modes.
        // renderLock is only used by appendLog (capture threads), so we acquire
        // it here to ensure no log line can interleave with a frame render.
        synchronized (renderLock) {
            if (pendingClear) {
                // A mode switch just happened. Erase the whole screen once so
                // the previous view's content is completely gone before we draw
                // the first frame of the new view.
                System.out.print(ANSI_CLEAR);
                System.out.flush();
                pendingClear = false;
            }
            switch (mode.get()) {
                case SUMMARY_VIEW -> summaryView.render();
                case SUMMARY_TEXT -> drawSummaryText();
                default           -> {} // LOG / COMMAND own their output
            }
        }
    }

    // ── SUMMARY TEXT view ─────────────────────────────────────────────────────

    /** Called while already holding renderLock (via scheduledRefresh or switchToSummaryText). */
    private void drawSummaryText() {
        // Build the entire frame into a StringBuilder, then emit it as a single
        // write so no partial state is ever visible.  Move to top-left without
        // erasing first so the terminal never goes blank between refreshes.
        StringBuilder sb = new StringBuilder(4096);

        sb.append(ANSI_HIDE_CUR).append(ANSI_HOME);

        appendBannerSB(sb);

        sb.append("\n");
        sb.append(ANSI_BOLD).append(ANSI_CYAN)
          .append("--- PAMGuard Summary Text (auto-refreshes every ")
          .append(settings.getSummaryIntervalSeconds()).append("s) ---")
          .append(ANSI_RESET).append("\n\n");

        String summary = watchdog.getLastSummary();
        if (summary == null || summary.isBlank()) {
            sb.append(ANSI_YELLOW).append("  (no summary data yet)").append(ANSI_RESET).append("\n");
        } else {
            for (String line : summary.split("\\r?\\n")) {
                sb.append("  ").append(line).append("\n");
            }
        }

        if (!lastCmd.isBlank()) {
            sb.append("\n");
            sb.append(ANSI_BOLD).append("--- Last Command (").append(lastCmdTime).append(") ---")
              .append(ANSI_RESET).append("\n");
            sb.append("  ").append(ANSI_CYAN).append(">> ").append(lastCmd).append(ANSI_RESET).append("\n");
            if (lastCmdResp == null || lastCmdResp.isBlank()) {
                sb.append("  ").append(ANSI_RED).append("<< (no response / timeout)").append(ANSI_RESET).append("\n");
            } else {
                for (String line : lastCmdResp.split("\\r?\\n")) {
                    sb.append("  ").append(ANSI_GREEN).append("<< ").append(ANSI_RESET).append(line).append("\n");
                }
            }
        }

        sb.append("\n");
        sb.append(ANSI_BOLD).append("--- Controls ---").append(ANSI_RESET).append("\n");
        sb.append("  [:] Command  [s] Summary View  [t] Summary Text  [l] Log  [q] Quit  [h] Help\n");
        sb.append("  [1] ping  [2] Status  [3] summary  [4] start  [5] stop\n");

        // Erase leftover lines from any previous (taller) frame, then restore cursor.
        sb.append(ANSI_ERASE_DOWN).append(ANSI_SHOW_CUR);

        System.out.print(sb);
        System.out.flush();
    }

    // Called from handleInput – submits to the uiScheduler so the render
    // always happens on the same thread as the periodic refresh, eliminating
    // any race between the input thread and the scheduler thread.
    private void printSummaryTextView() {
        submitRender(() -> {
            synchronized (renderLock) { drawSummaryText(); }
        });
    }

    private void appendBannerSB(StringBuilder sb) {
        State  s      = watchdog.getState();
        int    pStat  = watchdog.getPamStatus();
        long   upMs   = System.currentTimeMillis() - watchdog.getStartTime();
        String now    = TS_FMT.format(Instant.now());

        sb.append(ANSI_BOLD).append(ANSI_GREEN)
          .append("================================================================").append(ANSI_RESET).append("\n");
        sb.append(ANSI_BOLD).append(ANSI_GREEN)
          .append("         WhalePIDog - PAMGuard Watchdog").append(ANSI_RESET).append("\n");
        sb.append(ANSI_BOLD).append(ANSI_GREEN)
          .append("================================================================").append(ANSI_RESET).append("\n");
        sb.append(String.format("  Time       : %s%n", now));
        sb.append(String.format("  Dog state  : %s%n", colourState(s)));
        sb.append(String.format("  PAM status : %s%n", colourPamStatus(pStat)));
        sb.append(String.format("  Uptime     : %s%n", formatUptime(upMs)));
        sb.append(String.format("  Restarts   : %d%n", watchdog.getRestartCount()));
        sb.append(String.format("  Last check : %s%n", watchdog.getLastCheckTime()));
        sb.append(String.format("  UDP port   : %d%n", settings.getUdpPort()));
        sb.append(String.format("  Deploy     : %b%n", settings.isDeploy()));
    }

    // ── COMMAND mode ──────────────────────────────────────────────────────────

    private void enterCommandMode() {
        priorMode = mode.get();
        mode.set(DisplayMode.COMMAND);

        synchronized (renderLock) {
            System.out.println();
            System.out.println(ANSI_BOLD + ANSI_CYAN + "--- Command mode ---" + ANSI_RESET);
            System.out.println(ANSI_DIM + "Known: " + String.join("  ", KNOWN_COMMANDS) + ANSI_RESET);
            System.out.println(ANSI_DIM + "Unrecognised commands are rejected and NOT sent to PAMGuard." + ANSI_RESET);
            System.out.println(ANSI_DIM + "Empty line or 'back' to return." + ANSI_RESET);
            System.out.println();
            System.out.flush();
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            // Print prompt outside the lock so the user can type while PAMGuard
            // log lines are arriving (they are silenced in COMMAND mode anyway).
            System.out.print(ANSI_BOLD + ANSI_CYAN + "PAMGuard> " + ANSI_RESET);
            System.out.flush();
            try {
                String input = br.readLine();
                if (input == null) break;
                input = input.trim();
                if (input.isEmpty() || input.equalsIgnoreCase("back")) break;
                if (input.equalsIgnoreCase("q") || input.equalsIgnoreCase("quit")) {
                    quit(); return;
                }
                sendAndDisplay(input);
            } catch (IOException e) { break; }
        }

        // Return to prior view
        mode.set(priorMode);
        if      (priorMode == DisplayMode.SUMMARY_VIEW) switchToSummaryView();
        else if (priorMode == DisplayMode.SUMMARY_TEXT) switchToSummaryText();
        else                                             switchToLog();
    }

    private void sendAndDisplay(String command) {
        synchronized (renderLock) {
            System.out.println(ANSI_DIM + "  Sending \"" + command + "\" ..." + ANSI_RESET);
            System.out.flush();
        }

        String response = watchdog.sendCommandAndUpdate(command, 5000);

        lastCmd     = command;
        lastCmdResp = response != null ? response : "";
        lastCmdTime = TS_FMT.format(Instant.now());

        synchronized (renderLock) {
            System.out.println();
            if (response == null || response.isBlank()) {
                System.out.println(ANSI_RED + "  << (no response / timeout)" + ANSI_RESET);
            } else {
                System.out.println(ANSI_BOLD + "  Response:" + ANSI_RESET);
                for (String line : response.split("\\r?\\n")) {
                    System.out.println(ANSI_GREEN + "    " + line + ANSI_RESET);
                }
            }
            System.out.println();
            System.out.flush();
        }
    }

    // ── Quick-send shortcuts ──────────────────────────────────────────────────

    private void quickSend(String command) {
        // Show a brief "[Quick]" notice only in LOG mode (where scrolling is
        // expected).  In summary modes we skip the notice and just re-render
        // after the command returns, so no raw text ever bleeds over the UI.
        if (mode.get() == DisplayMode.LOG) {
            synchronized (renderLock) {
                System.out.println(ANSI_CYAN + "[Quick] >> " + command + ANSI_RESET);
                System.out.flush();
            }
        }
        sendAndDisplay(command);
        sleep(400);
        // Force immediate re-render of the active summary view via the scheduler.
        submitRender(() -> {
            synchronized (renderLock) {
                if      (mode.get() == DisplayMode.SUMMARY_VIEW) summaryView.render();
                else if (mode.get() == DisplayMode.SUMMARY_TEXT) drawSummaryText();
            }
        });
    }

    // ── Keyboard input loop ───────────────────────────────────────────────────

    private void readInput() {
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        while (running.get()) {
            try {
                if (!br.ready()) { sleep(100); continue; }
                String line = br.readLine();
                if (line == null) break;
                handleInput(line.trim());
            } catch (IOException e) { break; }
        }
    }

    private void handleInput(String raw) {
        if (mode.get() == DisplayMode.COMMAND) return;
        String key = raw.toLowerCase();
        switch (key) {
            case ":"  -> enterCommandMode();
            case "s"  -> switchToSummaryView();
            case "t"  -> switchToSummaryText();
            case "l"  -> switchToLog();
            case "q"  -> quit();
            case "h"  -> printHelp();
            case "1"  -> quickSend(PamUDP.CMD_PING);
            case "2"  -> quickSend(PamUDP.CMD_STATUS);
            case "3"  -> quickSend(PamUDP.CMD_SUMMARY);
            case "4"  -> quickSend(PamUDP.CMD_START);
            case "5"  -> quickSend(PamUDP.CMD_STOP);
            default -> {
                for (String cmd : KNOWN_COMMANDS) {
                    if (raw.equalsIgnoreCase(cmd)) { quickSend(cmd); return; }
                }
            }
        }
    }

    private void switchToSummaryView() {
        pendingClear = true;
        mode.set(DisplayMode.SUMMARY_VIEW);
        submitRender(() -> {
            synchronized (renderLock) {
                if (pendingClear) { System.out.print(ANSI_CLEAR); System.out.flush(); pendingClear = false; }
                summaryView.render();
            }
        });
    }

    private void switchToSummaryText() {
        pendingClear = true;
        mode.set(DisplayMode.SUMMARY_TEXT);
        submitRender(() -> {
            synchronized (renderLock) {
                if (pendingClear) { System.out.print(ANSI_CLEAR); System.out.flush(); pendingClear = false; }
                drawSummaryText();
            }
        });
    }

    private void switchToLog() {
        submitRender(() -> {
            synchronized (renderLock) {
                mode.set(DisplayMode.LOG);
                System.out.print(ANSI_CLEAR);
                System.out.println(ANSI_BOLD + ANSI_CYAN
                        + "--- LOG view (live PAMGuard output) ---" + ANSI_RESET);
                System.out.println(ANSI_DIM
                        + "  [:] command  [s] summary view  [t] summary text  [q] quit"
                        + ANSI_RESET);
                System.out.println();
                List<String> snap = new ArrayList<>(logBuffer);
                int from = Math.max(0, snap.size() - 40);
                for (int i = from; i < snap.size(); i++) System.out.println(snap.get(i));
                System.out.flush();
            }
        });
    }

    /**
     * Submit a render task to the single-threaded uiScheduler and block the
     * calling (input) thread until it completes.  This guarantees the render
     * happens after any in-progress scheduled refresh finishes, and before the
     * next periodic refresh starts – so there is never more than one thread
     * drawing to the terminal.
     */
    private void submitRender(Runnable task) {
        try {
            Future<?> f = uiScheduler.submit(task);
            f.get(); // wait for completion on the input thread
        } catch (Exception ignored) {}
    }

    private void quit() {
        // Switch to a clean scrolling state before the farewell message,
        // regardless of which view was active.
        submitRender(() -> {
            synchronized (renderLock) {
                System.out.print(ANSI_CLEAR);
                System.out.println(ANSI_YELLOW + "\n[UI] Quitting..." + ANSI_RESET);
                System.out.flush();
            }
        });
        running.set(false);
        watchdog.stop(true);
    }

    // ── Log buffer ────────────────────────────────────────────────────────────

    private void appendLog(String line) {
        logBuffer.add(line);
        if (logBuffer.size() > MAX_LOG_LINES) logBuffer.remove(0);
        // Only write to stdout in LOG mode.  Use renderLock so a log line
        // arriving on a capture thread can never interleave with the scheduled
        // refresh task (which also holds renderLock while drawing).
        // In SUMMARY_VIEW / SUMMARY_TEXT / COMMAND modes we stay completely
        // silent – the scheduler will display everything at the next refresh.
        if (mode.get() == DisplayMode.LOG) {
            synchronized (renderLock) {
                if (mode.get() == DisplayMode.LOG) { // re-check inside lock
                    System.out.println(line);
                    System.out.flush();
                }
            }
        }
    }

    // ── Help ─────────────────────────────────────────────────────────────────

    private void printHelp() {
        // At startup the scheduler hasn't started yet, so submit() would be
        // queued for later – that's fine.  During normal operation this ensures
        // the help text never overlaps a summary render.
        Runnable helpTask = () -> {
            synchronized (renderLock) {
                System.out.print(ANSI_CLEAR);
                System.out.println();
                System.out.println(ANSI_BOLD + "================================================================" + ANSI_RESET);
                System.out.println(ANSI_BOLD + "                    WhalePIDog  Help"                            + ANSI_RESET);
                System.out.println(ANSI_BOLD + "================================================================" + ANSI_RESET);
                System.out.println("  View switching:");
                System.out.println("    s  - Summary VIEW  (graphical: level meters, GPS, sensors...)");
                System.out.println("    t  - Summary TEXT  (raw PAMGuard text, no-scroll)");
                System.out.println("    l  - LOG view      (scrolling PAMGuard stdout/stderr)");
                System.out.println();
                System.out.println("  UDP quick-send shortcuts:");
                System.out.println("    1  - ping");
                System.out.println("    2  - Status    (updates status banner immediately)");
                System.out.println("    3  - summary   (updates summary panel immediately)");
                System.out.println("    4  - start");
                System.out.println("    5  - stop");
                System.out.println();
                System.out.println("  Command mode  ( press : ):");
                System.out.println("    Type any UDP command and press Enter.");
                System.out.println("    Known: ping  Status  summary  start  stop  Exit");
                System.out.println("    Empty line or 'back' returns to the previous view.");
                System.out.println();
                System.out.println("  q  - Quit    h  - Help");
                System.out.println(ANSI_BOLD + "================================================================" + ANSI_RESET);
                System.out.println();
                System.out.flush();
            }
        };
        if (running.get()) {
            submitRender(helpTask);
        } else {
            helpTask.run(); // startup – scheduler not yet running, call directly
        }
    }

    // ── Colour helpers ────────────────────────────────────────────────────────

    private String colourState(State s) {
        if (s == null) return "null";
        return switch (s) {
            case RUNNING          -> ANSI_GREEN   + ANSI_BOLD + s.name() + ANSI_RESET;
            case STARTING,
                 WAITING_FOR_INIT -> ANSI_YELLOW  + ANSI_BOLD + s.name() + ANSI_RESET;
            case RESTARTING       -> ANSI_MAGENTA + ANSI_BOLD + s.name() + ANSI_RESET;
            case ERROR            -> ANSI_RED     + ANSI_BOLD + s.name() + ANSI_RESET;
            default               -> s.name();
        };
    }

    private String colourPamStatus(int code) {
        String name = WatchdogController.statusName(code);
        return switch (code) {
            case PamUDP.PAM_RUNNING      -> ANSI_GREEN   + ANSI_BOLD + name + ANSI_RESET;
            case PamUDP.PAM_IDLE         -> ANSI_YELLOW  + ANSI_BOLD + name + ANSI_RESET;
            case PamUDP.PAM_STALLED      -> ANSI_RED     + ANSI_BOLD + name + ANSI_RESET;
            case PamUDP.PAM_INITIALISING -> ANSI_MAGENTA + ANSI_BOLD + name + ANSI_RESET;
            default                      -> ANSI_DIM     + name + ANSI_RESET;
        };
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    private static String formatUptime(long ms) {
        if (ms <= 0) return "0s";
        long secs = ms / 1000, mins = secs / 60, hours = mins / 60, days = hours / 24;
        if (days  > 0) return String.format("%dd %dh %dm", days, hours % 24, mins % 60);
        if (hours > 0) return String.format("%dh %dm %ds", hours, mins % 60, secs % 60);
        if (mins  > 0) return String.format("%dm %ds", mins, secs % 60);
        return secs + "s";
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}