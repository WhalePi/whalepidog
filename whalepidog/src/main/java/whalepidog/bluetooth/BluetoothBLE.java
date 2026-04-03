package whalepidog.bluetooth;

import whalepidog.watchdog.WatchdogController;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Bluetooth Low Energy (BLE) implementation for WhalePiDog.
 * 
 * <p>This implementation uses BLE GATT protocol via a Python-based BLE peripheral server,
 * which is compatible with both iOS and Android devices. The Python server implements the
 * Nordic UART Service (NUS), a widely-supported BLE UART profile.
 * 
 * <p>The BLE server advertises as "whalepi" (or "whalepi_<id>" if configured)
 * and can be discovered and connected to by Flutter apps on both platforms.
 * 
 * <p><b>Service UUID:</b> 6E400001-B5A3-F393-E0A9-E50E24DCCA9E (Nordic UART Service)
 * <br><b>RX Characteristic:</b> 6E400002-B5A3-F393-E0A9-E50E24DCCA9E (Write - receive commands)
 * <br><b>TX Characteristic:</b> 6E400003-B5A3-F393-E0A9-E50E24DCCA9E (Notify - send responses)
 * 
 * <p><b>Requirements:</b>
 * <ul>
 *   <li>Python 3.7+</li>
 *   <li>bluezero Python package: {@code pip3 install bluezero}</li>
 *   <li>BlueZ 5.43+ on the system</li>
 * </ul>
 * 
 * @see BluetoothInterface
 * @see BluetoothCommands
 */
public class BluetoothBLE implements BluetoothInterface {

    // Nordic UART Service UUIDs (widely supported by BLE libraries)
    private static final String SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
    private static final String RX_CHAR_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
    private static final String TX_CHAR_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";
    
    private final WatchdogController watchdog;
    private final BluetoothSettings settings;
    
    private volatile boolean serverRunning = false;
    private Thread serverThread;
    private Thread outputReaderThread;
    
    private Process pythonProcess;
    private BufferedWriter processWriter;
    private BufferedReader processReader;
    private BufferedReader processErrorReader;
    
    private volatile boolean isConnected = false;
    private final Lock connectionLock = new ReentrantLock();
    
    private final CopyOnWriteArrayList<Consumer<String>> messageListeners = new CopyOnWriteArrayList<>();
    private Consumer<String> logListener;

    /**
     * Create a new BLE server for command handling.
     * 
     * @param watchdog the watchdog controller to send commands to
     * @param settings Bluetooth configuration settings
     */
    public BluetoothBLE(WatchdogController watchdog, BluetoothSettings settings) {
        this.watchdog = watchdog;
        this.settings = settings;
    }

    @Override
    public void setLogListener(Consumer<String> listener) {
        this.logListener = listener;
    }

    @Override
    public void addMessageListener(Consumer<String> listener) {
        if (listener != null) {
            messageListeners.addIfAbsent(listener);
        }
    }

    @Override
    public void removeMessageListener(Consumer<String> listener) {
        if (listener != null) {
            messageListeners.remove(listener);
        }
    }

    @Override
    public boolean isConnected() {
        return isConnected;
    }

    @Override
    public synchronized void start() {
        if (serverRunning) {
            log("BLE server already running");
            return;
        }
        
        serverRunning = true;
        serverThread = new Thread(this::runServer, "ble-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    @Override
    public synchronized void stop() {
        if (!serverRunning) return;
        
        serverRunning = false;
        if (serverThread != null) {
            serverThread.interrupt();
        }
        
        cleanup();
    }

    @Override
    public void sendCopyProgress(String message) {
        if (!isConnected || processWriter == null) return;
        connectionLock.lock();
        try {
            sendToPython("COPY: " + message);
        } catch (Exception e) {
            logErr("Failed to send copy progress: " + e.getMessage());
        } finally {
            connectionLock.unlock();
        }
    }

    // ── BLE Server ───────────────────────────────────────────────────────────

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_RETRY_DELAY_MS = 5000;

    private void runServer() {
        int attempt = 0;
        while (serverRunning && attempt < MAX_RETRIES) {
            attempt++;
            try {
                log("=== Starting BLE server (attempt " + attempt + "/" + MAX_RETRIES + ") ===");

                if (!startPythonBLEServer()) {
                    logErr("Failed to start Python BLE server");
                    if (attempt < MAX_RETRIES && serverRunning) {
                        long delay = INITIAL_RETRY_DELAY_MS * attempt;
                        log("Retrying in " + (delay / 1000) + " seconds...");
                        Thread.sleep(delay);
                        continue;
                    }
                    return;
                }

                // Start thread to read from Python process
                outputReaderThread = new Thread(this::readFromPythonProcess, "ble-reader");
                outputReaderThread.setDaemon(true);
                outputReaderThread.start();

                // Monitor process
                while (serverRunning && pythonProcess != null && pythonProcess.isAlive()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }

                if (pythonProcess != null && !pythonProcess.isAlive()) {
                    int exitCode = pythonProcess.exitValue();
                    logErr("Python BLE server exited with code: " + exitCode);
                    cleanup();
                    if (exitCode != 0 && attempt < MAX_RETRIES && serverRunning) {
                        long delay = INITIAL_RETRY_DELAY_MS * attempt;
                        log("Retrying in " + (delay / 1000) + " seconds...");
                        Thread.sleep(delay);
                        continue;
                    }
                }
                break; // exit retry loop on clean exit or after exhausting retries

            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                logErr("BLE server error: " + e.getMessage());
                e.printStackTrace();
                cleanup();
                if (attempt < MAX_RETRIES && serverRunning) {
                    try {
                        long delay = INITIAL_RETRY_DELAY_MS * attempt;
                        log("Retrying in " + (delay / 1000) + " seconds...");
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        break;
                    }
                } else {
                    break;
                }
            }
        }
        cleanup();
        log("=== BLE server stopped ===");
    }

    /**
     * Start the Python-based BLE peripheral server.
     * The Python script handles all BLE GATT operations and communicates
     * with this Java class via stdin/stdout.
     */
    private boolean startPythonBLEServer() {
        try {
            log("Starting Python BLE peripheral server...");
            
            // Build device name
            String deviceName = "whalepi";
            String identification = settings.getIdentification();
            if (identification != null && !identification.trim().isEmpty()) {
                deviceName = "whalepi_" + identification.trim();
            }
            log("Device name: " + deviceName);
            
            // Find the Python script
            // First check if it's in the JAR resources
            InputStream scriptStream = getClass().getResourceAsStream("/ble_server.py");
            File scriptFile;
            
            if (scriptStream != null) {
                // Extract from JAR to temp file
                scriptFile = File.createTempFile("ble_server", ".py");
                scriptFile.deleteOnExit();
                
                try (FileOutputStream fos = new FileOutputStream(scriptFile)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = scriptStream.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }
                scriptStream.close();
                
                // Make executable
                scriptFile.setExecutable(true);
                log("Extracted BLE server script to: " + scriptFile.getAbsolutePath());
            } else {
                // Try to find in local resources directory
                scriptFile = new File("src/main/resources/ble_server.py");
                if (!scriptFile.exists()) {
                    logErr("BLE server script not found");
                    logErr("Please ensure ble_server.py is in the JAR or at: " + scriptFile.getAbsolutePath());
                    return false;
                }
            }
            
            // Build command
            List<String> command = new ArrayList<>();
            command.add("python3");
            command.add(scriptFile.getAbsolutePath());
            command.add("--name");
            command.add(deviceName);
            
            if (settings.isVerbose()) {
                command.add("--verbose");
            }
            
            log("Starting: " + String.join(" ", command));
            
            // Start process
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            pythonProcess = pb.start();
            
            // Get streams
            processWriter = new BufferedWriter(new OutputStreamWriter(
                pythonProcess.getOutputStream(), StandardCharsets.UTF_8));
            processReader = new BufferedReader(new InputStreamReader(
                pythonProcess.getInputStream(), StandardCharsets.UTF_8));
            processErrorReader = new BufferedReader(new InputStreamReader(
                pythonProcess.getErrorStream(), StandardCharsets.UTF_8));
            
            // Start stderr reader thread
            Thread stderrThread = new Thread(this::readStderr, "ble-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();
            
            log("Python BLE server started");
            return true;
            
        } catch (Exception e) {
            logErr("Failed to start Python BLE server: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Read output from the Python BLE server process.
     * Messages from the BLE client come in as "RX:command" lines.
     */
    private void readFromPythonProcess() {
        try {
            String line;
            while (serverRunning && (line = processReader.readLine()) != null) {
                if (line.startsWith("RX:")) {
                    // Command received from BLE client
                    String command = line.substring(3).trim();
                    handleReceivedData(command);
                } else if (line.startsWith("CONNECTED")) {
                    isConnected = true;
                    log("BLE client connected");
                } else if (line.startsWith("DISCONNECTED")) {
                    isConnected = false;
                    log("BLE client disconnected");
                } else {
                    // Other output from Python script
                    log("Python: " + line);
                }
            }
        } catch (IOException e) {
            if (serverRunning) {
                logErr("Error reading from Python process: " + e.getMessage());
            }
        }
    }

    /**
     * Read stderr from Python process for logging.
     */
    private void readStderr() {
        try {
            String line;
            while ((line = processErrorReader.readLine()) != null) {
                log(line);
            }
        } catch (IOException e) {
            // Ignore - process probably terminated
        }
    }

    // ── Command handling ─────────────────────────────────────────────────────

    /**
     * Called when data is received from the BLE client via Python process.
     */
    private void handleReceivedData(String command) {
        try {
            if (command.isEmpty()) return;
            
            log("Received command: '" + command + "'");
            
            // Notify listeners
            for (Consumer<String> listener : messageListeners) {
                try {
                    listener.accept(command);
                } catch (Exception e) {
                    logErr("Error in message listener: " + e.getMessage());
                }
            }
            
            // Process command
            handleCommand(command);
            
        } catch (Exception e) {
            logErr("Error handling received data: " + e.getMessage());
        }
    }

    private void handleCommand(String command) {
        try {
            log("Processing command: '" + command + "'");
            
            // Handle copydata commands locally (not a PAMGuard UDP command)
            if (command.toLowerCase().startsWith("copydata")) {
                String response = CopyDataHandler.handle(command, watchdog, this::sendCopyProgress);
                sendResponse(command, response);
                return;
            }

            // Handle deletewav commands locally (not a PAMGuard UDP command)
            if (command.toLowerCase().startsWith("deletewav")) {
                String response = DeleteDataHandler.handleDeleteWav(command, watchdog, this::sendCopyProgress);
                sendResponse(command, response);
                return;
            }

            // Handle deletedatabase commands locally (not a PAMGuard UDP command)
            if (command.toLowerCase().startsWith("deletedatabase")) {
                String response = DeleteDataHandler.handleDeleteDatabase(command, watchdog, this::sendCopyProgress);
                sendResponse(command, response);
                return;
            }

            String response;
            if (command.equalsIgnoreCase("status")) {
                // For Bluetooth, return an XML status message with watchdog info
                response = watchdog.getBluetoothStatusXml(5000);
                log("Built XML status response for BLE client");
            } else {
                // Send to watchdog controller
                response = watchdog.sendCommandAndUpdate(command, 5000);
            }
            
            log("Response from PAMGuard: " + (response != null ? "'" + response + "'" : "null"));
            
            // Send response back via BLE
            sendResponse(command, response);
            
        } catch (Exception e) {
            logErr("Error handling command '" + command + "': " + e.getMessage());
            e.printStackTrace();
            sendResponse(command, "ERROR: " + e.getMessage());
        }
    }

    private void sendResponse(String command, String response) {
        connectionLock.lock();
        try {
            if (processWriter == null) {
                logErr("Cannot send response - process not running");
                return;
            }
            
            // Send ACK
            sendToPython("ACK: " + command);
            log("Sent ACK: " + command);
            
            // Send response data if available.
            // The response (e.g. from "summary") can be multi-line.
            // We must send each line as a separate TX: message because the
            // Python stdin reader uses readline() — any embedded newlines
            // would break the framing and cause subsequent lines to be lost.
            if (response != null && !response.isEmpty()) {
                String[] lines = response.split("\\r?\\n");
                for (String line : lines) {
                    if (!line.isEmpty()) {
                        sendToPython("RPLY: " + line);
                    }
                }
                log("Sent RPLY: " + lines.length + " line(s) for '" + command + "'");
            }
            
        } catch (Exception e) {
            logErr("Failed to send response: " + e.getMessage());
        } finally {
            connectionLock.unlock();
        }
    }

    /**
     * Send a single line of data to the Python BLE server to be transmitted
     * to the client as a BLE notification.
     * 
     * <p>The data must NOT contain newlines — each call writes exactly one
     * {@code TX:<data>\n} frame that the Python stdin_reader can parse.
     */
    private void sendToPython(String data) {
        try {
            if (processWriter != null) {
                processWriter.write("TX:" + data + "\n");
                processWriter.flush();
            }
        } catch (IOException e) {
            logErr("Error sending to Python process: " + e.getMessage());
        }
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    private void cleanup() {
        try {
            // Signal Python process to shutdown
            if (processWriter != null) {
                try {
                    processWriter.write("SHUTDOWN\n");
                    processWriter.flush();
                    processWriter.close();
                } catch (IOException e) {
                    // Ignore
                }
                processWriter = null;
            }
            
            // Wait for process to exit gracefully
            if (pythonProcess != null) {
                try {
                    pythonProcess.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    // Ignore
                }
                
                // Force kill if still running
                if (pythonProcess.isAlive()) {
                    pythonProcess.destroyForcibly();
                }
                
                pythonProcess = null;
            }
            
            // Close streams
            if (processReader != null) {
                try { processReader.close(); } catch (IOException e) { /* ignore */ }
                processReader = null;
            }
            if (processErrorReader != null) {
                try { processErrorReader.close(); } catch (IOException e) { /* ignore */ }
                processErrorReader = null;
            }
            
            isConnected = false;
            
        } catch (Exception e) {
            logErr("Cleanup error: " + e.getMessage());
        }
    }

    // ── Logging ──────────────────────────────────────────────────────────────

    private void log(String msg) {
        String fullMsg = "[BLE] " + msg;
        if (logListener != null) {
            logListener.accept(fullMsg);
        } else if (settings.isVerbose()) {
            System.out.println(fullMsg);
        }
    }

    private void logErr(String msg) {
        String fullMsg = "[BLE ERROR] " + msg;
        if (logListener != null) {
            logListener.accept(fullMsg);
        } else {
            System.err.println(fullMsg);
        }
    }
}
