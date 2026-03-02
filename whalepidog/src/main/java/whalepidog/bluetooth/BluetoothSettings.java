package whalepidog.bluetooth;

/**
 * Settings for Bluetooth communication with WhalePIDog.
 * These settings control whether Bluetooth pairing is enabled and other Bluetooth-related options.
 */
public class BluetoothSettings {
    
    /**
     * Enable or disable Bluetooth functionality.
     * Default: false (disabled).
     */
    private boolean bluetoothEnabled = false;
    
    /**
     * Enable or disable Bluetooth pairing when WhalePIDog starts.
     * Default: true (enabled).
     */
    private boolean bluetoothPairing = true;
    
    /**
     * Enable verbose logging for Bluetooth operations.
     * Default: false.
     */
    private boolean verbose = false;

    // ── Getters / Setters ────────────────────────────────────────────────────

    public boolean isBluetoothEnabled() {
        return bluetoothEnabled;
    }

    public void setBluetoothEnabled(boolean bluetoothEnabled) {
        this.bluetoothEnabled = bluetoothEnabled;
    }

    public boolean isBluetoothPairing() {
        return bluetoothPairing;
    }

    public void setBluetoothPairing(boolean bluetoothPairing) {
        this.bluetoothPairing = bluetoothPairing;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    @Override
    public String toString() {
        return "BluetoothSettings{" +
                "bluetoothEnabled=" + bluetoothEnabled +
                ", bluetoothPairing=" + bluetoothPairing +
                ", verbose=" + verbose +
                '}';
    }
}
