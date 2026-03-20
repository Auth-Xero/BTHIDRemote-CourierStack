package com.courierstack.hidremote.data

import androidx.compose.runtime.Immutable

/**
 * Device mode for HID emulation.
 */
enum class DeviceMode(val displayName: String) {
    KEYBOARD("Keyboard"),
    MOUSE("Mouse"),
    COMBO("Keyboard + Mouse"),
    GAMEPAD("Gamepad")
}

/**
 * Bluetooth HID backend implementation to use.
 *
 * ANDROID_NATIVE uses Android's built-in BluetoothHidDevice API (default, no root needed).
 * COURIER_STACK uses the CourierStack low-level library (experimental backup, requires root).
 */
enum class HidBackendMode(val displayName: String) {
    ANDROID_NATIVE("Android Native (Default)"),
    COURIER_STACK("CourierStack (Experimental)")
}

/**
 * Connection state of the HID device.
 */
enum class ConnectionState {
    DISCONNECTED,
    INITIALIZING,
    SCANNING,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Represents a discovered Bluetooth host device.
 */
@Immutable
data class HostDevice(
    val address: String,
    val name: String?,
    val rssi: Int = 0,
    val isPaired: Boolean = false,
    val isConnected: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis()
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: address
}

/**
 * Keyboard modifier keys state.
 */
@Immutable
data class ModifierState(
    val leftCtrl: Boolean = false,
    val leftShift: Boolean = false,
    val leftAlt: Boolean = false,
    val leftGui: Boolean = false,
    val rightCtrl: Boolean = false,
    val rightShift: Boolean = false,
    val rightAlt: Boolean = false,
    val rightGui: Boolean = false
) {
    fun toByte(): Int {
        var result = 0
        if (leftCtrl) result = result or 0x01
        if (leftShift) result = result or 0x02
        if (leftAlt) result = result or 0x04
        if (leftGui) result = result or 0x08
        if (rightCtrl) result = result or 0x10
        if (rightShift) result = result or 0x20
        if (rightAlt) result = result or 0x40
        if (rightGui) result = result or 0x80
        return result
    }

    fun isAnyActive(): Boolean = leftCtrl || leftShift || leftAlt || leftGui ||
            rightCtrl || rightShift || rightAlt || rightGui
}

/**
 * Mouse button state.
 */
@Immutable
data class MouseButtonState(
    val left: Boolean = false,
    val right: Boolean = false,
    val middle: Boolean = false
) {
    fun toByte(): Int {
        var result = 0
        if (left) result = result or 0x01
        if (right) result = result or 0x02
        if (middle) result = result or 0x04
        return result
    }
}

/**
 * Application settings.
 */
@Immutable
data class AppSettings(
    val deviceName: String = "BT HID Remote",
    val deviceMode: DeviceMode = DeviceMode.COMBO,
    val hidBackendMode: HidBackendMode = HidBackendMode.ANDROID_NATIVE,
    val hapticFeedback: Boolean = true,
    val mouseSensitivity: Float = 1.0f,
    val tapToClick: Boolean = true,
    val naturalScrolling: Boolean = false,
    val keyRepeatEnabled: Boolean = true,
    val keyRepeatDelay: Long = 500L,
    val keyRepeatInterval: Long = 50L,
    val autoConnect: Boolean = false,
    val lastConnectedAddress: String? = null
)

/**
 * Keyboard LED state received from host.
 */
@Immutable
data class KeyboardLedState(
    val numLock: Boolean = false,
    val capsLock: Boolean = false,
    val scrollLock: Boolean = false,
    val compose: Boolean = false,
    val kana: Boolean = false
) {
    companion object {
        fun fromByte(value: Int): KeyboardLedState {
            return KeyboardLedState(
                numLock = (value and 0x01) != 0,
                capsLock = (value and 0x02) != 0,
                scrollLock = (value and 0x04) != 0,
                compose = (value and 0x08) != 0,
                kana = (value and 0x10) != 0
            )
        }
    }
}

/**
 * UI event for one-time notifications.
 */
sealed class UiEvent {
    data class ShowToast(val message: String) : UiEvent()
    data class ShowError(val message: String) : UiEvent()
    data object NavigateToSettings : UiEvent()
    data object RequestPermissions : UiEvent()
}

/**
 * Log entry for debugging.
 */
@Immutable
data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val message: String,
    val level: LogLevel = LogLevel.INFO
)

enum class LogLevel {
    DEBUG, INFO, WARNING, ERROR
}