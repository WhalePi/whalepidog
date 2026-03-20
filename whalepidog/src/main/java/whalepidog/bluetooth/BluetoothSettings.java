package whalepidog.bluetooth;

/**
 * Settings for Bluetooth communication with WhalePIDog.
 * These settings control whether Bluetooth pairing is enabled and other Bluetooth-related options.
 */
public class BluetoothSettings {
    
    /**
     * Bluetooth mode selection.
     */
    public enum BluetoothMode {
        /** Legacy Serial Bluetooth (SPP) - works with Serial Bluetooth Terminal apps */
        SERIAL,
        /** Bluetooth Low Energy - works with iOS and Android apps */
        BLE
    }
    
    /**
     * Enable or disable Bluetooth functionality.
     * Default: false (disabled).
     */
    private boolean bluetoothEnabled = false;
    
    /**
     * Bluetooth mode: SERIAL (legacy) or BLE (iOS/Android compatible).
     * Default: BLE (for cross-platform compatibility).
     */
    private BluetoothMode bluetoothMode = BluetoothMode.BLE;
    
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
    
    /**
     * Identification tag to append to the Bluetooth device name.
     * If set, the device will appear as "whalepi_<identification>" when pairing.
     * Example: if identification = "X12", the device name will be "whalepi_X12"
     * Default: empty string (device name will be "whalepi")
     */
    private String identification = "";

    // ── Getters / Setters ────────────────────────────────────────────────────

    public boolean isBluetoothEnabled() {
        return bluetoothEnabled;
    }

    public void setBluetoothEnabled(boolean bluetoothEnabled) {
        this.bluetoothEnabled = bluetoothEnabled;
    }

    public BluetoothMode getBluetoothMode() {
        return bluetoothMode;
    }

    public void setBluetoothMode(String string) {
    	switch (string.toUpperCase()) {
			case "SERIAL":
				this.bluetoothMode = BluetoothMode.SERIAL;
				break;
			case "BLE":
				this.bluetoothMode = BluetoothMode.BLE;
				break;
			default:
				throw new IllegalArgumentException("Invalid Bluetooth mode: " + string);
		}
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

    public String getIdentification() {
        return identification;
    }

    public void setIdentification(String identification) {
        this.identification = identification;
    }

    @Override
    public String toString() {
        return "BluetoothSettings{" +
                "bluetoothEnabled=" + bluetoothEnabled +
                ", bluetoothMode=" + bluetoothMode +
                ", bluetoothPairing=" + bluetoothPairing +
                ", verbose=" + verbose +
                ", identification='" + identification + '\'' +
                '}';
    }
}
