package whalepidog.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/**
 * Handles all UDP communication with a running PAMGuard instance.
 *
 * <p>Two independent sockets are maintained internally:
 * <ul>
 *   <li><b>watchdog socket</b> – used exclusively by the periodic health-check
 *       scheduler (ping / status / summary polling).  Never blocked by a slow
 *       manual command.</li>
 *   <li><b>command socket</b> – used for on-demand manual commands sent from
 *       the UI.  A long timeout here never delays the watchdog.</li>
 * </ul>
 *
 * <p>Known PAMGuard command strings (exact case):
 * <pre>
 *   ping    Status    summary    start    stop    kill    Exit
 * </pre>
 *
 * <p>Status codes returned by {@code Status}:
 * <pre>
 *   0=IDLE  1=RUNNING  3=STALLED  4=INITIALISING
 * </pre>
 */
public class PamUDP {

    // ── Status codes ─────────────────────────────────────────────────────────
    public static final int PAM_IDLE         = 0;
    public static final int PAM_RUNNING      = 1;
    public static final int PAM_STALLED      = 3;
    public static final int PAM_INITIALISING = 4;

    // ── Command strings ───────────────────────────────────────────────────────
    public static final String CMD_PING    = "ping";
    public static final String CMD_STATUS  = "Status";
    public static final String CMD_SUMMARY = "summary";
    public static final String CMD_START   = "start";
    public static final String CMD_STOP    = "stop";
    public static final String CMD_EXIT    = "Exit";
    public static final String CMD_KILL    = "kill";

    /** All commands PAMGuard is known to understand (case-insensitive match). */
    public static final String[] ALL_KNOWN = {
        CMD_PING, CMD_STATUS, CMD_SUMMARY, CMD_START, CMD_STOP, CMD_EXIT, CMD_KILL
    };

    // ── Buffer sizes ─────────────────────────────────────────────────────────
    private static final int DEFAULT_TIMEOUT_MS    = 2000;
    private static final int SUMMARY_BUFFER_BYTES  = 8192;
    private static final int STANDARD_BUFFER_BYTES = 512;

    private final int       port;
    private final InetAddress address;

    /**
     * Dedicated socket for the watchdog scheduler (ping/status/summary polling).
     * Never touched by manual command calls.
     */
    private final DatagramSocket watchdogSocket;

    /**
     * Dedicated socket for manual commands from the UI.
     * A long timeout here does not block the watchdog socket.
     */
    private final DatagramSocket commandSocket;

    private volatile String lastError;

    public PamUDP(int port) throws SocketException {
        this.port    = port;
        this.address = InetAddress.getLoopbackAddress();
        this.watchdogSocket = new DatagramSocket();
        this.commandSocket  = new DatagramSocket();
    }

    // ── Watchdog-side helpers (used by scheduler) ─────────────────────────────

    public boolean ping(int timeoutMs) {
        String ans = send(watchdogSocket, CMD_PING, timeoutMs, STANDARD_BUFFER_BYTES);
        return CMD_PING.equals(ans);
    }

    public int getStatus(int timeoutMs) {
        String ans = send(watchdogSocket, CMD_STATUS, timeoutMs, STANDARD_BUFFER_BYTES);
        if (ans == null || ans.length() < 8) return -1;
        try {
            return Integer.parseInt(ans.substring(7).trim());
        } catch (NumberFormatException e) {
            lastError = "Bad status response: " + ans;
            return -1;
        }
    }

    public String getSummary(int timeoutMs) {
        return send(watchdogSocket, CMD_SUMMARY, timeoutMs, SUMMARY_BUFFER_BYTES);
    }

    public String sendStart(int timeoutMs) {
        return send(watchdogSocket, CMD_START, timeoutMs, STANDARD_BUFFER_BYTES);
    }

    public String sendStop(int timeoutMs) {
        return send(watchdogSocket, CMD_STOP, timeoutMs, STANDARD_BUFFER_BYTES);
    }

    public String sendExit(int timeoutMs) {
        return send(watchdogSocket, CMD_EXIT, timeoutMs, STANDARD_BUFFER_BYTES);
    }

    // ── Manual command socket (used by UI) ────────────────────────────────────

    /**
     * Send a manually-entered command on the dedicated command socket.
     * Uses a large receive buffer so even unexpected large responses are captured.
     * Completely independent from the watchdog socket – a long timeout here
     * will never delay health-check pings.
     *
     * @param command   raw command string
     * @param timeoutMs receive timeout
     * @return PAMGuard response, or {@code null} on timeout/error
     */
    public String sendManualCommand(String command, int timeoutMs) {
        int bufSize = command.equalsIgnoreCase(CMD_SUMMARY) ? SUMMARY_BUFFER_BYTES
                                                            : STANDARD_BUFFER_BYTES;
        return send(commandSocket, command, timeoutMs, bufSize);
    }

    // ── Legacy public overload (kept for callers that pass their own bufSize) ──

    /** @deprecated Use {@link #sendManualCommand} or the typed helpers. */
    public String sendCommand(String command, int timeoutMs, int maxResponseBytes) {
        return send(commandSocket, command, timeoutMs, maxResponseBytes);
    }

    public String sendCommand(String command) {
        return send(commandSocket, command, DEFAULT_TIMEOUT_MS, STANDARD_BUFFER_BYTES);
    }

    // ── Core send/receive – private, per-socket ───────────────────────────────

    /**
     * Send {@code command} on {@code sock} and wait up to {@code timeoutMs} for
     * a reply.  Each socket is only ever used by one caller at a time (the
     * watchdog scheduler is single-threaded; the UI sends one manual command at
     * a time), so no synchronization is needed between sockets.
     * We do synchronize per-socket just in case.
     */
    private String send(DatagramSocket sock, String command, int timeoutMs, int bufSize) {
        synchronized (sock) {
            // Drain any stale datagrams that arrived from a previous exchange.
            // Use a tiny timeout so we never block; discard silently.
            drainStale(sock);

            byte[]         bytes  = command.getBytes();
            DatagramPacket out    = new DatagramPacket(bytes, bytes.length, address, port);
            try {
                sock.send(out);
            } catch (IOException e) {
                lastError = "Send error: " + e.getMessage();
                return null;
            }

            byte[]         buf = new byte[bufSize];
            DatagramPacket in  = new DatagramPacket(buf, buf.length);
            try {
                sock.setSoTimeout(timeoutMs);
                sock.receive(in);
            } catch (SocketTimeoutException e) {
                lastError = "Receive timeout waiting for reply to: " + command;
                return null;
            } catch (IOException e) {
                lastError = "Receive error: " + e.getMessage();
                return null;
            }

            lastError = null;
            return new String(in.getData(), 0, in.getLength());
        }
    }

    /**
     * Drain up to 32 stale datagrams from {@code sock} without blocking.
     * Uses setSoTimeout(1) so each attempt waits at most 1 ms.
     * Stale datagrams can accumulate if a previous command received no reply
     * (the send was discarded) but PAMGuard later sent a delayed response.
     */
    private static void drainStale(DatagramSocket sock) {
        byte[]         buf = new byte[64]; // small – we're throwing these away
        DatagramPacket p   = new DatagramPacket(buf, buf.length);
        int drained = 0;
        while (drained < 32) {
            try {
                sock.setSoTimeout(1);
                sock.receive(p);
                drained++;
            } catch (IOException e) {
                return; // nothing left to drain
            }
        }
    }

    // ── Validation helper ─────────────────────────────────────────────────────

    /**
     * Return {@code true} if {@code command} is a recognised PAMGuard command.
     * Comparison is case-insensitive.
     */
    public static boolean isKnownCommand(String command) {
        if (command == null || command.isBlank()) return false;
        for (String k : ALL_KNOWN) {
            if (k.equalsIgnoreCase(command)) return true;
        }
        return false;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void close() {
        closeSocket(watchdogSocket);
        closeSocket(commandSocket);
    }

    private static void closeSocket(DatagramSocket s) {
        if (s != null && !s.isClosed()) s.close();
    }

    public String getLastError() { return lastError; }
    public int    getPort()      { return port; }
}