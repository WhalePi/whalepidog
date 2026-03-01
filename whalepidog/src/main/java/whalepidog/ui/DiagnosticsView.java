package whalepidog.ui;

import whalepidog.settings.WhalePIDogSettings;
import whalepidog.ui.SummaryParser.*;
import whalepidog.watchdog.WatchdogController;
import whalepidog.watchdog.WatchdogController.State;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Renders the "Diagnostics View" for WhalePIDog showing system diagnostics.
 * Displays PAMGuard memory usage, system memory, and CPU usage per core.
 */
public class DiagnosticsView {

    // ── ANSI codes ────────────────────────────────────────────────────────────
    private static final String R  = "\u001B[0m";   // reset
    private static final String B  = "\u001B[1m";   // bold
    private static final String DM = "\u001B[2m";   // dim
    private static final String RD = "\u001B[31m";  // red
    private static final String GR = "\u001B[32m";  // green
    private static final String YL = "\u001B[33m";  // yellow
    private static final String BL = "\u001B[34m";  // blue
    private static final String MG = "\u001B[35m";  // magenta
    private static final String CY = "\u001B[36m";  // cyan

    // Box-drawing
    private static final char FULL  = '█';
    private static final char LIGHT = '░';

    // Terminal control
    private static final String HIDE_CUR    = "\u001B[?25l";
    private static final String SHOW_CUR    = "\u001B[?25h";
    private static final String CLEAR       = "\u001B[2J\u001B[H";
    private static final String ERASE_DOWN  = "\u001B[J";

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final int BAR_WIDTH = 30;

    private final WatchdogController watchdog;
    private final WhalePIDogSettings settings;

    public DiagnosticsView(WatchdogController watchdog, WhalePIDogSettings settings) {
        this.watchdog = watchdog;
        this.settings = settings;
    }

    /**
     * Render a complete frame of the diagnostics view.
     */
    public void renderFrame(String diagnosticsXml) {
        StringBuilder sb = new StringBuilder();
        sb.append(HIDE_CUR).append(CLEAR);

        // Parse the diagnostics
        SystemDiagnosticsData data = SummaryParser.parseDiagnostics(diagnosticsXml);

        appendBanner(sb);
        sb.append("\n");

        if (data != null) {
            appendDiagnostics(sb, data);
        } else {
            sb.append(YL).append("  (no diagnostics data available)").append(R).append("\n\n");
        }

        appendFooter(sb);

        sb.append(ERASE_DOWN).append(SHOW_CUR);

        System.out.print(sb);
        System.out.flush();
    }

    private void appendBanner(StringBuilder sb) {
        State  s      = watchdog.getState();
        int    pamSt  = watchdog.getPamStatus();
        String stName = WatchdogController.statusName(pamSt);
        int    nRst   = watchdog.getRestartCount();
        long   upMs   = System.currentTimeMillis() - watchdog.getStartTime();
        String uptime = formatUptime(upMs);

        // Watchdog state pill
        String stColor = switch (s) {
            case RUNNING   -> GR;
            case STARTING, WAITING_FOR_INIT -> YL;
            case RESTARTING -> MG;
            default        -> RD;
        };
        String stPill = B + stColor + " ● " + s.name() + " " + R;

        sb.append(B).append(CY).append("╔═══════════════════════════════════════════════════════════════════╗").append(R).append("\n");
        sb.append(B).append(CY).append("║").append(R)
          .append(B).append("                   WhalePIDog - DIAGNOSTICS VIEW                  ").append(R)
          .append(B).append(CY).append("║").append(R).append("\n");
        sb.append(B).append(CY).append("╚═══════════════════════════════════════════════════════════════════╝").append(R).append("\n");

        sb.append(String.format("  Watchdog: %s   PAMGuard: %s   Restarts: %d   Uptime: %s\n",
                stPill,
                B + stName + R,
                nRst,
                uptime));
    }

    private void appendDiagnostics(StringBuilder sb, SystemDiagnosticsData data) {
        sb.append(B).append(CY).append("  ┌─ System Diagnostics ").append(thin(45)).append(R).append("\n");

        // PAMGuard Memory
        long pamUsedMB = data.pamguardMemoryUsedMB;
        long pamMaxMB = data.pamguardMemoryMaxMB;
        if (pamMaxMB > 0) {
            double pamPercent = (100.0 * pamUsedMB) / pamMaxMB;
            String memColor = pamPercent > 90 ? RD : pamPercent > 75 ? YL : GR;
            int filled = (int) Math.min(BAR_WIDTH, (pamPercent / 100.0) * BAR_WIDTH);
            
            sb.append(B).append(CY).append("  │ ").append(R).append("PAMGuard Memory: ");
            sb.append(memColor).append(B);
            for (int i = 0; i < BAR_WIDTH; i++) sb.append(i < filled ? FULL : LIGHT);
            sb.append(R);
            sb.append(String.format("  %d / %d MB  (%.1f%%)", pamUsedMB, pamMaxMB, pamPercent));
            sb.append("\n");
        }

        // System Memory
        long sysUsedMB = data.systemMemoryUsedMB;
        long sysTotalMB = data.systemMemoryTotalMB;
        if (sysTotalMB > 0) {
            double sysPercent = (100.0 * sysUsedMB) / sysTotalMB;
            String memColor = sysPercent > 90 ? RD : sysPercent > 75 ? YL : GR;
            int filled = (int) Math.min(BAR_WIDTH, (sysPercent / 100.0) * BAR_WIDTH);
            
            sb.append(B).append(CY).append("  │ ").append(R).append("System Memory:   ");
            sb.append(memColor).append(B);
            for (int i = 0; i < BAR_WIDTH; i++) sb.append(i < filled ? FULL : LIGHT);
            sb.append(R);
            sb.append(String.format("  %d / %d MB  (%.1f%%)", sysUsedMB, sysTotalMB, sysPercent));
            sb.append("\n");
        }

        // CPU Cores
        if (!data.cpuCores.isEmpty()) {
            sb.append(B).append(CY).append("  │").append(R).append("\n");
            sb.append(B).append(CY).append("  │ ").append(R).append("CPU Usage:").append("\n");
            for (CpuCore core : data.cpuCores) {
                double usage = core.usagePercent;
                String cpuColor = usage > 90 ? RD : usage > 75 ? YL : GR;
                int filled = (int) Math.min(BAR_WIDTH, (usage / 100.0) * BAR_WIDTH);
                
                sb.append(B).append(CY).append("  │ ").append(R)
                  .append(String.format("   Core %d:        ", core.index));
                sb.append(cpuColor).append(B);
                for (int i = 0; i < BAR_WIDTH; i++) sb.append(i < filled ? FULL : LIGHT);
                sb.append(R);
                sb.append(String.format("  %.1f%%", usage));
                sb.append("\n");
            }
        }

        sb.append(B).append(CY).append("  └").append(thin(68)).append(R).append("\n\n");
    }

    private void appendFooter(StringBuilder sb) {
        sb.append(B).append(divider()).append(R).append("\n");
        sb.append("  ").append(DM)
          .append("[:] cmd  [s] Summary View  [t] Summary Text  [l] Log  [d] Diagnostics  [q] Quit")
          .append(R).append("\n");
        sb.append("  ").append(DM)
          .append("[1] ping  [2] Status  [3] summary  [4] start  [5] stop")
          .append(R).append("\n");
    }

    private static String formatUptime(long ms) {
        long sec = ms / 1000;
        long min = sec / 60;
        long hr  = min / 60;
        long day = hr / 24;
        if (day > 0) return String.format("%dd %02d:%02d:%02d", day, hr % 24, min % 60, sec % 60);
        return String.format("%02d:%02d:%02d", hr, min % 60, sec % 60);
    }

    private static String divider() {
        return "─".repeat(70);
    }

    private static String thin(int count) {
        return "─".repeat(count);
    }
}
