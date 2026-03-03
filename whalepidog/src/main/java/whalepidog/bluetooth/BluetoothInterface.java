package whalepidog.bluetooth;

import java.util.function.Consumer;

/**
 * Common interface for different Bluetooth implementations (Serial and BLE).
 * This allows seamless switching between Serial Bluetooth (for backward compatibility)
 * and Bluetooth Low Energy (for iOS/Android app support).
 */
public interface BluetoothInterface {
    
    /**
     * Set a listener to receive log messages from the Bluetooth system.
     * 
     * @param listener callback for log messages
     */
    void setLogListener(Consumer<String> listener);
    
    /**
     * Add a listener to be notified when messages are received via Bluetooth.
     * 
     * @param listener callback for received messages
     */
    void addMessageListener(Consumer<String> listener);
    
    /**
     * Remove a message listener.
     * 
     * @param listener the listener to remove
     */
    void removeMessageListener(Consumer<String> listener);
    
    /**
     * Check if a device is currently connected via Bluetooth.
     * 
     * @return true if connected, false otherwise
     */
    boolean isConnected();
    
    /**
     * Start the Bluetooth server in a background thread.
     * The server will run until {@link #stop()} is called.
     */
    void start();
    
    /**
     * Stop the Bluetooth server and clean up resources.
     */
    void stop();
}
