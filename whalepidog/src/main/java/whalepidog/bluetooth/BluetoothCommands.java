package whalepidog.bluetooth;

import com.fazecast.jSerialComm.SerialPort;
import whalepidog.watchdog.WatchdogController;

import java.io.File;
import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Handles Bluetooth serial communication for receiving commands.
 * 
 * <p>This class manages a Bluetooth serial connection on Linux (via rfcomm)
 * and forwards any received commands to the WatchdogController for execution.
 * Commands sent via Bluetooth are processed exactly the same way as terminal
 * or UDP commands.
 * 
 * <p>The Bluetooth server runs in a background thread and automatically handles
 * connection/disconnection cycles.
 * 
 * <p><b>Note:</b> This is the legacy Serial Bluetooth implementation (SPP).
 * For iOS compatibility, use {@link BluetoothBLE} instead.
 * 
 * @see BluetoothInterface
 * @see BluetoothBLE
 */
public class BluetoothCommands implements BluetoothInterface {

    private static final String PORT_PATH = "/dev/rfcomm0";
    
    private final WatchdogController watchdog;
    private final BluetoothSettings settings;
    
    private volatile boolean serverRunning = false;
    private Thread serverThread;
    private static Process rfcommProcess;
    
    private volatile SerialPort comPort = null;
    private final CopyOnWriteArrayList<Consumer<String>> messageListeners = new CopyOnWriteArrayList<>();
    private Consumer<String> logListener;

    /**
     * Create a new Bluetooth command handler.
     * 
     * @param watchdog the watchdog controller to send commands to
     * @param settings Bluetooth configuration settings
     */
    public BluetoothCommands(WatchdogController watchdog, BluetoothSettings settings) {
        this.watchdog = watchdog;
        this.settings = settings;
    }

    /**
     * Set a listener to receive log messages from the Bluetooth system.
     * 
     * @param listener callback for log messages
     */
    @Override
    public void setLogListener(Consumer<String> listener) {
        this.logListener = listener;
    }

    /**
     * Add a listener to be notified when messages are received via Bluetooth.
     * 
     * @param listener callback for received messages
     */
    @Override
    public void addMessageListener(Consumer<String> listener) {
        if (listener != null) {
            messageListeners.addIfAbsent(listener);
        }
    }

    /**
     * Remove a message listener.
     * 
     * @param listener the listener to remove
     */
    @Override
    public void removeMessageListener(Consumer<String> listener) {
        if (listener != null) {
            messageListeners.remove(listener);
        }
    }

    /**
     * Check if a phone/device is currently connected via Bluetooth.
     * 
     * @return true if connected, false otherwise
     */
    @Override
    public boolean isConnected() {
        return comPort != null && comPort.isOpen();
    }

    /**
     * Start the Bluetooth server in a background thread.
     * The server will run until {@link #stop()} is called.
     */
    @Override
    public synchronized void start() {
        if (serverRunning) {
            log("Bluetooth server already running");
            return;
        }
        
        serverRunning = true;
        serverThread = new Thread(this::runServer, "bluetooth-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    /**
     * Stop the Bluetooth server and clean up resources.
     */
    @Override
    public synchronized void stop() {
        if (!serverRunning) return;
        
        serverRunning = false;
        if (serverThread != null) {
            serverThread.interrupt();
        }
        
        try {
            if (comPort != null && comPort.isOpen()) {
                comPort.closePort();
            }
        } catch (Exception e) {
            logErr("Error closing port: " + e.getMessage());
        }
        
        if (rfcommProcess != null) {
            rfcommProcess.destroy();
            rfcommProcess = null;
        }
    }

    // ── Server loop ──────────────────────────────────────────────────────────

    private void runServer() {
        try {
            log("=== Starting Bluetooth server ===");
            
            try {
                setupBluetooth();
            } catch (Exception e) {
                logErr("Failed to setup Bluetooth: " + e.getMessage());
                return;
            }

            File portFile = new File(PORT_PATH);

            while (serverRunning) {
                log("Waiting for Bluetooth connection on " + PORT_PATH + "...");

                // Wait for the port to appear (device connects)
                while (serverRunning && !portFile.exists()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }

                if (!serverRunning) break;

                log("Bluetooth device connected!");

                try {
                    comPort = SerialPort.getCommPort(PORT_PATH);
                    comPort.setBaudRate(115200);
                    handleSession(comPort);
                    comPort = null;

                    // Wait for disconnection
                    int attempts = 0;
                    while (portFile.exists() && attempts < 20) {
                        Thread.sleep(500);
                        attempts++;
                    }
                } catch (Exception e) {
                    logErr("Error during session: " + e.getMessage());
                    comPort = null;
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            logErr("Bluetooth server error: " + e.getMessage());
        } finally {
            if (rfcommProcess != null) {
                rfcommProcess.destroy();
            }
            log("=== Bluetooth server stopped ===");
        }
    }

    // ── Session handling ─────────────────────────────────────────────────────

    private void handleSession(SerialPort comPort) {
    	if (!comPort.openPort()) {
    		logErr("Failed to open port " + PORT_PATH);
    		return;
    	}

    	log("Bluetooth session started");

    	int heartbeatCounter = 0;
    	while (serverRunning) {

    		try (Scanner scanner = new Scanner(comPort.getInputStream())) {

    			// Read commands from Bluetooth
    			while (scanner.hasNextLine()) {
    				String line = scanner.nextLine();

    				// Clean up the command - remove all whitespace including CR/LF
    				String cleanLine = line.replaceAll("[\\r\\n\\s]+$", "").trim();

    				if (!cleanLine.isEmpty()) {
    					log("Received raw: '" + line + "' (bytes: " + line.length() + ")");
    					log("Cleaned to: '" + cleanLine + "' (bytes: " + cleanLine.length() + ")");

    					// Notify listeners
    					for (Consumer<String> listener : messageListeners) {
    						try {
    							listener.accept(cleanLine);
    						} catch (Exception e) {
    							logErr("Error in message listener: " + e.getMessage());
    						}
    					}
    					
    					// Process the command
    					handleCommand(cleanLine);
    				}
    			}

    			// Check if still connected
    			if (!new File(PORT_PATH).exists()) {
    				log("Bluetooth device disconnected");
    				break;
    			}

    			// Send periodic heartbeat
    			if (heartbeatCounter++ % 500 == 0) {
    				//log("Heartbeat - connection is alive");
    				try {
    					comPort.getOutputStream().write("\n".getBytes());
    				} catch (Exception e) {
    					log("Heartbeat failed - connection lost");
    					break;
    				}
    			}

    			try {
    				Thread.sleep(10);
    			} catch (InterruptedException e) {
    				break;
    			}
    		}
    		catch (Exception e) {
    			logErr("Session error: " + e.getMessage());
    		} 

    	}
    	comPort.closePort();
    	log("Bluetooth session ended");

    }

    // ── Command handling ─────────────────────────────────────────────────────

    private void handleCommand(String command) {
        try {
            log("Processing command: '" + command + "' (length=" + command.length() + ")");
            
            // Send the command to the watchdog controller
            String response = watchdog.sendCommandAndUpdate(command, 5000);
            
            log("Response from PAMGuard: " + (response != null ? "'" + response + "'" : "null"));
            
            // Send response back via Bluetooth
            sendResponse(command, response);
        } catch (Exception e) {
            logErr("Error handling command '" + command + "': " + e.getMessage());
            e.printStackTrace();
            sendResponse(command, "ERROR: " + e.getMessage());
        }
    }

    private void sendResponse(String command, String response) {
        if (comPort == null || !comPort.isOpen()) {
            logErr("Cannot send response - port not open");
            return;
        }
        
        try {
            String msg = "ACK: " + command + "\r\n";
            comPort.getOutputStream().write(msg.getBytes());
            comPort.getOutputStream().flush();
            log("Sent ACK: " + command);
            
            if (response != null && !response.isEmpty()) {
                // The response (e.g. from "summary") can be multi-line.
                // Send each line individually with its own \r\n terminator
                // and flush after each to ensure the serial terminal app
                // receives every line.
                String[] lines = response.split("\\r?\\n");
                for (String line : lines) {
                    if (!line.isEmpty()) {
                        String reply = "RPLY: " + line + "\r\n";
                        comPort.getOutputStream().write(reply.getBytes());
                        comPort.getOutputStream().flush();
                    }
                }
                log("Sent RPLY: " + lines.length + " line(s) for '" + command + "'");
            } else {
                log("No response data to send");
            }
        } catch (Exception e) {
            logErr("Failed to send response: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ── Bluetooth setup (Linux-specific) ─────────────────────────────────────

    private void setupBluetooth() throws IOException, InterruptedException {
        log("Setting up Bluetooth...");
        
        // Build the device name from the identification tag
        String deviceName = "whalepi";
        String identification = settings.getIdentification();
        if (identification != null && !identification.trim().isEmpty()) {
            deviceName = "whalepi_" + identification.trim();
        }
        log("Setting Bluetooth device name to: " + deviceName);
        
        // Set the Bluetooth system alias (device name)
        String[] nameCommand = {"sudo", "bluetoothctl", "system-alias", deviceName};
        Process nameProcess = Runtime.getRuntime().exec(nameCommand);
        nameProcess.waitFor();
        
        String[] commands = {
            "sudo bluetoothctl power on",
            "sudo bluetoothctl pairable on",
            "sudo sdptool add SP",
            "sudo chmod 777 /var/run/sdp"
        };

        // Optionally enable discoverable mode for pairing
        if (settings.isBluetoothPairing()) {
            log("Enabling Bluetooth discoverable mode for pairing");
            Runtime.getRuntime().exec("sudo bluetoothctl discoverable on").waitFor();
        }

        for (String cmd : commands) {
            Runtime.getRuntime().exec(cmd).waitFor();
        }

        // Start rfcomm listener on channel 1
        ProcessBuilder pb = new ProcessBuilder("sudo", "rfcomm", "watch", "hci0", "1");
        rfcommProcess = pb.start();
        
        log("Bluetooth setup complete");
    }

    // ── Logging ──────────────────────────────────────────────────────────────

    private void log(String msg) {
        String fullMsg = "[Bluetooth] " + msg;
        if (logListener != null) {
            logListener.accept(fullMsg);
        } else if (settings.isVerbose()) {
            System.out.println(fullMsg);
        }
    }

    private void logErr(String msg) {
        String fullMsg = "[Bluetooth ERROR] " + msg;
        if (logListener != null) {
            logListener.accept(fullMsg);
        } else {
            System.err.println(fullMsg);
        }
    }
}
