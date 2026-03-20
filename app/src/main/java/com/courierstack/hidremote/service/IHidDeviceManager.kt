package com.courierstack.hidremote.service

import com.courierstack.hidremote.data.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for HID Device Manager implementations.
 *
 * Two concrete implementations exist:
 * - [AndroidHidDeviceManager]: Uses Android's native BluetoothHidDevice API (default).
 * - [CourierHidDeviceManager]: Uses the CourierStack low-level Bluetooth library (experimental backup).
 */
interface IHidDeviceManager {

    // ==================== Observable State ====================

    val connectionState: StateFlow<ConnectionState>
    val connectedHost: StateFlow<HostDevice?>
    val discoveredHosts: StateFlow<List<HostDevice>>
    val ledState: StateFlow<KeyboardLedState>
    val logs: StateFlow<List<LogEntry>>
    val errors: Flow<String>

    // ==================== Lifecycle ====================

    /**
     * Initialize the Bluetooth HID stack.
     * @return true if initialized successfully (or already was)
     */
    suspend fun initialize(settings: AppSettings): Boolean

    /** Whether the stack has been initialized. */
    fun isInitialized(): Boolean

    /** Whether a host is currently connected. */
    fun isConnected(): Boolean

    /** Shut down the HID stack and release all resources. */
    fun shutdown()

    /** Update configuration (device name, mode, etc.) while running. */
    fun updateConfiguration(settings: AppSettings)

    // ==================== Pairing / Discovery ====================

    /**
     * Start pairing mode — make device discoverable so hosts can find and pair with us.
     * @return true if pairing started, false if not initialized or already connected
     */
    fun startPairing(): Boolean

    /** Stop pairing mode — hide from discovery. */
    fun stopPairing()

    /** Alias for [startPairing]. */
    fun startAdvertising() { startPairing() }

    /** Alias for [stopPairing]. */
    fun stopAdvertising() { stopPairing() }

    // ==================== Scanning ====================

    /** Start scanning for nearby Bluetooth devices. */
    fun startScanning()

    /** Stop scanning. */
    fun stopScanning()

    // ==================== Connection ====================

    /** Prepare for connection from the given host. */
    fun connectToHost(host: HostDevice)

    /**
     * Reconnect to a previously bonded device by address.
     * @return true if reconnection attempt started
     */
    fun connectToBondedDevice(address: String): Boolean

    /** Attempt to connect to any previously bonded device. */
    fun connectToAnyBondedDevice(): Boolean

    /** Disconnect from the current host. */
    fun disconnect()

    // ==================== Bonded Devices ====================

    /** Get list of bonded device addresses. */
    fun getBondedDevices(): List<String>

    /** Remove bond for a specific device. */
    fun removeBond(address: String)

    /** Clear all stored bonds. */
    fun clearAllBonds()

    // ==================== Keyboard Input ====================

    fun pressKey(keyCode: Int, modifiers: ModifierState = ModifierState())
    fun releaseKey(keyCode: Int)
    fun typeKey(keyCode: Int, modifiers: ModifierState = ModifierState())
    fun typeKeyWithModifiers(keyCode: Int, modifiers: ModifierState)
    fun typeText(text: String, delayMs: Long = 30)
    fun releaseAllKeys()
    fun setModifiers(modifiers: ModifierState)

    // ==================== Mouse Input ====================

    fun moveMouse(dx: Int, dy: Int)
    fun scroll(amount: Int)
    fun pressMouseButton(button: Int)
    fun releaseMouseButton(button: Int)
    fun clickMouseButton(button: Int)

    // ==================== Gamepad Input ====================

    fun sendGamepad(
        buttons: Int = 0,
        leftX: Int = 128,
        leftY: Int = 128,
        rightX: Int = 128,
        rightY: Int = 128,
        leftTrigger: Int = 0,
        rightTrigger: Int = 0,
        dpad: Int = 0x0F
    )
}
