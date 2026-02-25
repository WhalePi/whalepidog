package whalepidog.ui;

import whalepidog.settings.WhalePIDogSettings;
import whalepidog.ui.SummaryParser.*;
import whalepidog.watchdog.WatchdogController;
import whalepidog.watchdog.WatchdogController.State;
import whalepidog.udp.PamUDP;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders the rich "Summary View" for WhalePIDog.
 *
 * <p>Parses the PAMGuard summary XML and draws:
 * <ul>
 *   <li>Compact watchdog status banner</li>
 *   <li>Sound Acquisition – dB level meters (peak + RMS) per channel</li>
 *   <li>Sound Recorder   – recording state + remaining-disk-space bar</li>
 *   <li>GPS              – fix status, coordinates, heading</li>
 *   <li>NMEA             – latest raw sentence</li>
 *   <li>Analog Sensors   – voltage and calibrated value per sensor</li>
 *   <li>Pi Temperature   – temperature with colour-coded bar</li>
 *   <li>Unknown sections – raw key/value fallback</li>
 * </ul>
 *
 * <p>Designed to be called from the scheduled UI refresh thread so it must
 * only write to stdout and must be safe to call repeatedly.
 */
public class SummaryView {

    // ── ANSI ──────────────────────────────────────────────────────────────────
    private static final String R  = "\u001B[0m";   // reset
    private static final String B  = "\u001B[1m";   // bold
    private static final String DM = "\u001B[2m";   // dim
    private static final String GR = "\u001B[32m";  // green
    private static final String YL = "\u001B[33m";  // yellow
    private static final String CY = "\u001B[36m";  // cyan
    private static final String RD = "\u001B[31m";  // red
    private static final String MG = "\u001B[35m";  // magenta
    private static final String BL = "\u001B[34m";  // blue
    /** Move cursor to top-left WITHOUT erasing – content is overwritten in place. */
    private static final String HOME       = "\u001B[H";
    /** Erase from cursor to end of screen (clears leftover lines after shorter content). */
    private static final String ERASE_DOWN = "\u001B[J";
    /** Hide cursor during redraw to prevent visible flicker. */
    private static final String HIDE_CUR   = "\u001B[?25l";
    /** Restore cursor after redraw. */
    private static final String SHOW_CUR   = "\u001B[?25h";

    // ── Meter geometry ────────────────────────────────────────────────────────
    private static final int    METER_W  = 40;   // characters wide
    private static final double MIN_DB   = -80.0;
    private static final double MAX_DB   = 0.0;
    private static final int    DISK_W   = 30;   // chars for disk bar

    // ── Block characters ──────────────────────────────────────────────────────
    private static final String FULL  = "\u2588"; // █
    private static final String LIGHT = "\u2591"; // ░

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final WatchdogController watchdog;
    private final WhalePIDogSettings settings;

    public SummaryView(WatchdogController watchdog, WhalePIDogSettings settings) {
        this.watchdog = watchdog;
        this.settings = settings;
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Render a full screen frame.  Clears the terminal, draws the banner,
     * then each data section derived from the latest PAMGuard summary.
     */
    public synchronized void render() {
        String raw = watchdog.getLastSummary();
        ParsedSummary ps = SummaryParser.parse(raw);

        StringBuilder sb = new StringBuilder(4096);

        // Move to top-left and hide the cursor while drawing so the eye never
        // sees partial state.  We do NOT erase the whole screen first; instead
        // each line overwrites whatever was there and we erase any leftover
        // content at the very end.  This eliminates the blank-flash that
        // occurred when the screen was cleared before the new frame arrived.
        sb.append(HIDE_CUR).append(HOME);

        // ── Banner ────────────────────────────────────────────────────────────
        appendBanner(sb);

        // ── Sections (each gracefully skipped if null) ────────────────────────
        if (ps.soundAcquisition != null && !ps.soundAcquisition.channels.isEmpty()) {
            appendSoundAcquisition(sb, ps.soundAcquisition);
        }

        if (ps.soundRecorder != null) {
            appendSoundRecorder(sb, ps.soundRecorder);
        }

        if (ps.gps != null) {
            appendGps(sb, ps.gps);
        }

        if (ps.nmea != null) {
            appendNmea(sb, ps.nmea);
        }

        if (ps.analogSensors != null && !ps.analogSensors.readings.isEmpty()) {
            appendAnalogSensors(sb, ps.analogSensors);
        }

        if (ps.piTemperature != null) {
            appendPiTemperature(sb, ps.piTemperature);
        }

        for (RawSection sec : ps.unknownSections) {
            appendRawSection(sb, sec);
        }

        if (ps.soundAcquisition == null && ps.soundRecorder == null && ps.gps == null
                && ps.nmea == null && ps.analogSensors == null && ps.piTemperature == null
                && ps.unknownSections.isEmpty()) {
            sb.append(YL).append("  (no summary data yet – waiting for PAMGuard)").append(R).append("\n");
        }

        // ── Footer / controls bar ─────────────────────────────────────────────
        sb.append("\n");
        sb.append(B).append(divider()).append(R).append("\n");
        sb.append("  ").append(DM)
          .append("[:] cmd  [s] Summary View  [t] Summary Text  [l] Log  [q] Quit  [h] Help")
          .append(R).append("\n");
        sb.append("  ").append(DM)
          .append("[1] ping  [2] Status  [3] summary  [4] start  [5] stop")
          .append(R).append("\n");

        // Erase any leftover lines from a previous (taller) frame, then restore cursor.
        sb.append(ERASE_DOWN).append(SHOW_CUR);

        System.out.print(sb);
        System.out.flush();
    }

    // ── Banner ────────────────────────────────────────────────────────────────

    private void appendBanner(StringBuilder sb) {
        State  s      = watchdog.getState();
        int    pst    = watchdog.getPamStatus();
        long   upMs   = System.currentTimeMillis() - watchdog.getStartTime();
        String now    = TS_FMT.format(Instant.now());

        sb.append(B).append(CY).append(divider()).append(R).append("\n");
        sb.append(B).append(CY)
          .append("  WhalePIDog – PAMGuard Summary View           ")
          .append("Time: ").append(now).append(R).append("\n");
        sb.append(B).append(CY).append(divider()).append(R).append("\n");
        sb.append("  Dog: ").append(colourState(s))
          .append("   PAM: ").append(colourPamStatus(pst))
          .append("   Up: ").append(B).append(formatUptime(upMs)).append(R)
          .append("   Restarts: ").append(watchdog.getRestartCount())
          .append("   Port: ").append(settings.getUdpPort())
          .append("\n");
        sb.append("\n");
    }

    // ── Sound Acquisition ─────────────────────────────────────────────────────

    private void appendSoundAcquisition(StringBuilder sb, SoundAcquisitionData data) {
        sb.append(B).append(CY).append("  ┌─ Sound Acquisition ").append(thin(40)).append(R).append("\n");

        for (ChannelLevel ch : data.channels) {
            String label = "Ch " + ch.index;

            // RMS meter
            sb.append(B).append(CY).append("  │ ").append(R);
            sb.append(String.format("%-5s RMS %7.1f dB  ", label, ch.rmsDb));
            sb.append(dbMeter(ch.rmsDb, METER_W));
            sb.append("\n");

            // Peak meter
            sb.append(B).append(CY).append("  │ ").append(R);
            sb.append(String.format("%-5s Pk  %7.1f dB  ", label, ch.peakDb));
            sb.append(dbMeter(ch.peakDb, METER_W));
            // Peak status badge
            sb.append("  ").append(peakBadge(ch.peakDb));
            sb.append("\n");

            sb.append(B).append(CY).append("  │").append(R).append("\n");
        }

        sb.append(B).append(CY).append("  └").append(thin(59)).append(R).append("\n\n");
    }

    // ── Sound Recorder ────────────────────────────────────────────────────────

    private void appendSoundRecorder(StringBuilder sb, SoundRecorderData data) {
        sb.append(B).append(CY).append("  ┌─ Sound Recorder ").append(thin(42)).append(R).append("\n");

        // State pill
        String stateColor = "recording".equalsIgnoreCase(data.state) ? GR : YL;
        String stateStr   = "recording".equalsIgnoreCase(data.state)
                            ? B + GR + " ● RECORDING " + R
                            : B + YL + " ○ STOPPED   " + R;

        sb.append(B).append(CY).append("  │ ").append(R)
          .append("State: ").append(stateStr)
          .append("   Button: ").append(B).append(data.button).append(R)
          .append("\n");

        // Free space bar
        double freeMb = data.freeSpaceMb;
        double freeGb = freeMb / 1024.0;
        // Assume 500 GB total for proportion (we don't have total from PAMGuard)
        // Instead just show the absolute value with a graduated colour bar scaled to 500 GB
        double maxGb  = 500.0;
        int    filled = (int) Math.min(DISK_W, (freeGb / maxGb) * DISK_W);
        String diskColor = freeGb < 10 ? RD : freeGb < 50 ? YL : GR;

        sb.append(B).append(CY).append("  │ ").append(R).append("Disk free: ");
        sb.append(diskColor).append(B);
        for (int i = 0; i < DISK_W; i++) sb.append(i < filled ? FULL : LIGHT);
        sb.append(R);
        sb.append(String.format("  %7.1f GB", freeGb));
        sb.append("\n");

        sb.append(B).append(CY).append("  └").append(thin(59)).append(R).append("\n\n");
    }

    // ── GPS ───────────────────────────────────────────────────────────────────

    private void appendGps(StringBuilder sb, GpsData data) {
        sb.append(B).append(CY).append("  ┌─ GPS ").append(thin(53)).append(R).append("\n");

        String statusColor = "ok".equalsIgnoreCase(data.status) ? GR : RD;

        sb.append(B).append(CY).append("  │ ").append(R)
          .append("Status : ").append(statusColor).append(B).append(data.status.toUpperCase()).append(R)
          .append("   Time: ").append(data.timestamp)
          .append("\n");

        sb.append(B).append(CY).append("  │ ").append(R)
          .append(String.format("Lat    : %s%.6f°", data.latitude  >= 0 ? "N " : "S ", Math.abs(data.latitude)))
          .append(String.format("   Lon: %s%.6f°",  data.longitude >= 0 ? "E " : "W ", Math.abs(data.longitude)))
          .append("\n");

        // Heading compass rose (text)
        sb.append(B).append(CY).append("  │ ").append(R)
          .append(String.format("Heading: %.1f°  %s", data.headingDeg, headingArrow(data.headingDeg)))
          .append("\n");

        sb.append(B).append(CY).append("  └").append(thin(59)).append(R).append("\n\n");
    }

    // ── NMEA ──────────────────────────────────────────────────────────────────

    private void appendNmea(StringBuilder sb, NmeaData data) {
        sb.append(B).append(CY).append("  ┌─ NMEA ").append(thin(52)).append(R).append("\n");

        String sentence = data.rawSentence;
        // Truncate if too long for display
        if (sentence != null && sentence.length() > 70) sentence = sentence.substring(0, 67) + "...";

        sb.append(B).append(CY).append("  │ ").append(R)
          .append(DM).append(sentence).append(R).append("\n");

        sb.append(B).append(CY).append("  └").append(thin(59)).append(R).append("\n\n");
    }

    // ── Analog Sensors ────────────────────────────────────────────────────────

    private void appendAnalogSensors(StringBuilder sb, AnalogSensorsData data) {
        sb.append(B).append(CY).append("  ┌─ Analog Sensors ").append(thin(42)).append(R).append("\n");

        for (SensorReading r : data.readings) {
            boolean hasCalVal = !Double.isNaN(r.calVal);
            boolean hasVolt   = !Double.isNaN(r.voltage);

            sb.append(B).append(CY).append("  │ ").append(R);
            sb.append(String.format("%-20s", r.name));
            if (hasCalVal) sb.append(String.format("  val: %8.4f", r.calVal));
            if (hasVolt)   sb.append(String.format("  V: %7.4f V", r.voltage));
            sb.append("\n");

            // Small analogue bar for calibrated value if numeric
            if (hasCalVal && !Double.isNaN(r.calVal)) {
                double norm  = Math.min(1.0, Math.max(0.0, (r.calVal + 10) / 20.0)); // -10..+10 range assumption
                int    bars  = (int) (norm * 20);
                sb.append(B).append(CY).append("  │   ").append(R);
                sb.append(CY);
                for (int i = 0; i < 20; i++) sb.append(i < bars ? FULL : LIGHT);
                sb.append(R).append("\n");
            }
        }

        sb.append(B).append(CY).append("  └").append(thin(59)).append(R).append("\n\n");
    }

    // ── Pi Temperature ────────────────────────────────────────────────────────

    private void appendPiTemperature(StringBuilder sb, PiTemperatureData data) {
        sb.append(B).append(CY).append("  ┌─ Pi Temperature ").append(thin(42)).append(R).append("\n");

        double t = data.tempCelsius;
        // Colour: <60 green, 60-75 yellow, >75 red
        String tempColor = t > 75 ? RD : t > 60 ? YL : GR;

        // Temperature bar 0-100°C
        int filled = (int) Math.min(30, (t / 100.0) * 30);
        sb.append(B).append(CY).append("  │ ").append(R);
        sb.append(tempColor).append(B);
        for (int i = 0; i < 30; i++) sb.append(i < filled ? FULL : LIGHT);
        sb.append(R);
        sb.append(tempColor).append(B).append(String.format("  %.1f °C", t)).append(R);
        // Overheat warning
        if (t > 80) sb.append("  ").append(RD).append(B).append("[ HOT! ]").append(R);
        else if (t > 70) sb.append("  ").append(YL).append("[ WARM  ]").append(R);
        sb.append("\n");

        sb.append(B).append(CY).append("  └").append(thin(59)).append(R).append("\n\n");
    }

    // ── Raw / unknown sections ─────────────────────────────────────────────────

    private void appendRawSection(StringBuilder sb, RawSection sec) {
        sb.append(B).append(CY).append("  ┌─ ").append(sec.tag).append(" ")
          .append(thin(Math.max(0, 56 - sec.tag.length()))).append(R).append("\n");

        String content = sec.content == null ? "" : sec.content.trim();
        // Truncate very long content
        if (content.length() > 120) content = content.substring(0, 117) + "...";
        sb.append(B).append(CY).append("  │ ").append(R).append(DM).append(content).append(R).append("\n");

        sb.append(B).append(CY).append("  └").append(thin(59)).append(R).append("\n\n");
    }

    // ── Drawing helpers ───────────────────────────────────────────────────────

    /**
     * Build a coloured dB level meter string of {@code width} characters.
     * Colour gradient: green → yellow → red as level rises toward 0 dB.
     */
    private static String dbMeter(double db, int width) {
        db = Math.max(MIN_DB, Math.min(MAX_DB, db));
        int filled = (int) ((db - MIN_DB) / (MAX_DB - MIN_DB) * width);
        filled = Math.max(0, Math.min(width, filled));

        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < width; i++) {
            if (i < filled) {
                // Colour gradient: low=green, -20dB=yellow, -6dB=red
                double pct = (double) i / width;
                String colour = pct > 0.93 ? RD : pct > 0.75 ? YL : GR;
                sb.append(colour).append(FULL).append(R);
            } else {
                sb.append(DM).append(LIGHT).append(R);
            }
        }
        sb.append("]");
        return sb.toString();
    }

    /** Badge showing signal quality. */
    private static String peakBadge(double peakDb) {
        if (peakDb > -1)  return RD + B + "[CLIP]" + R;
        if (peakDb > -6)  return YL + B + "[HIGH]" + R;
        if (peakDb > -20) return GR + B + "[ OK ]" + R;
        return DM + "[LOW ]" + R;
    }

    /** Return an 8-direction compass arrow for the heading. */
    private static String headingArrow(double deg) {
        // normalise
        deg = ((deg % 360) + 360) % 360;
        String[] arrows = { "↑ N", "↗ NE", "→ E", "↘ SE", "↓ S", "↙ SW", "← W", "↖ NW" };
        int idx = (int) ((deg + 22.5) / 45) % 8;
        return arrows[idx];
    }

    /** A horizontal rule made of thin-line chars. */
    private static String divider() {
        return "─".repeat(70);
    }

    /** A run of thin horizontal lines for box drawing. */
    private static String thin(int n) {
        if (n <= 0) return "";
        return "─".repeat(n);
    }

    // ── State colour helpers (mirror TerminalUI) ──────────────────────────────

    private static String colourState(State s) {
        if (s == null) return "null";
        return switch (s) {
            case RUNNING          -> GR + B + s.name() + R;
            case STARTING,
                 WAITING_FOR_INIT -> YL + B + s.name() + R;
            case RESTARTING       -> MG + B + s.name() + R;
            case ERROR            -> RD + B + s.name() + R;
            default               -> s.name();
        };
    }

    private static String colourPamStatus(int code) {
        String name = WatchdogController.statusName(code);
        return switch (code) {
            case PamUDP.PAM_RUNNING      -> GR + B + name + R;
            case PamUDP.PAM_IDLE         -> YL + B + name + R;
            case PamUDP.PAM_STALLED      -> RD + B + name + R;
            case PamUDP.PAM_INITIALISING -> MG + B + name + R;
            default                      -> DM + name + R;
        };
    }

    private static String formatUptime(long ms) {
        if (ms <= 0) return "0s";
        long secs = ms / 1000, mins = secs / 60, hours = mins / 60, days = hours / 24;
        if (days  > 0) return String.format("%dd %dh %dm", days, hours % 24, mins % 60);
        if (hours > 0) return String.format("%dh %dm %ds", hours, mins % 60, secs % 60);
        if (mins  > 0) return String.format("%dm %ds", mins, secs % 60);
        return secs + "s";
    }
}
