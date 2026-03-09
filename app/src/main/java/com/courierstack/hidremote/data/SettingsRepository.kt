package com.courierstack.hidremote.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hid_settings")

/**
 * Repository for persisting app settings using DataStore.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val DEVICE_MODE = stringPreferencesKey("device_mode")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
        val MOUSE_SENSITIVITY = floatPreferencesKey("mouse_sensitivity")
        val TAP_TO_CLICK = booleanPreferencesKey("tap_to_click")
        val NATURAL_SCROLLING = booleanPreferencesKey("natural_scrolling")
        val KEY_REPEAT_ENABLED = booleanPreferencesKey("key_repeat_enabled")
        val KEY_REPEAT_DELAY = longPreferencesKey("key_repeat_delay")
        val KEY_REPEAT_INTERVAL = longPreferencesKey("key_repeat_interval")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val LAST_CONNECTED_ADDRESS = stringPreferencesKey("last_connected_address")
    }

    /**
     * Flow of current settings.
     */
    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            AppSettings(
                deviceName = prefs[Keys.DEVICE_NAME] ?: "BT HID Remote",
                deviceMode = prefs[Keys.DEVICE_MODE]?.let { DeviceMode.valueOf(it) }
                    ?: DeviceMode.COMBO,
                hapticFeedback = prefs[Keys.HAPTIC_FEEDBACK] ?: true,
                mouseSensitivity = prefs[Keys.MOUSE_SENSITIVITY] ?: 1.0f,
                tapToClick = prefs[Keys.TAP_TO_CLICK] ?: true,
                naturalScrolling = prefs[Keys.NATURAL_SCROLLING] ?: false,
                keyRepeatEnabled = prefs[Keys.KEY_REPEAT_ENABLED] ?: true,
                keyRepeatDelay = prefs[Keys.KEY_REPEAT_DELAY] ?: 500L,
                keyRepeatInterval = prefs[Keys.KEY_REPEAT_INTERVAL] ?: 50L,
                autoConnect = prefs[Keys.AUTO_CONNECT] ?: false,
                lastConnectedAddress = prefs[Keys.LAST_CONNECTED_ADDRESS]
            )
        }

    suspend fun updateDeviceName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEVICE_NAME] = name
        }
    }

    suspend fun updateDeviceMode(mode: DeviceMode) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEVICE_MODE] = mode.name
        }
    }

    suspend fun updateHapticFeedback(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.HAPTIC_FEEDBACK] = enabled
        }
    }

    suspend fun updateMouseSensitivity(sensitivity: Float) {
        context.dataStore.edit { prefs ->
            prefs[Keys.MOUSE_SENSITIVITY] = sensitivity.coerceIn(0.1f, 3.0f)
        }
    }

    suspend fun updateTapToClick(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TAP_TO_CLICK] = enabled
        }
    }

    suspend fun updateNaturalScrolling(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NATURAL_SCROLLING] = enabled
        }
    }

    suspend fun updateKeyRepeat(enabled: Boolean, delay: Long? = null, interval: Long? = null) {
        context.dataStore.edit { prefs ->
            prefs[Keys.KEY_REPEAT_ENABLED] = enabled
            delay?.let { prefs[Keys.KEY_REPEAT_DELAY] = it }
            interval?.let { prefs[Keys.KEY_REPEAT_INTERVAL] = it }
        }
    }

    suspend fun updateAutoConnect(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTO_CONNECT] = enabled
        }
    }

    suspend fun updateLastConnectedAddress(address: String?) {
        context.dataStore.edit { prefs ->
            if (address != null) {
                prefs[Keys.LAST_CONNECTED_ADDRESS] = address
            } else {
                prefs.remove(Keys.LAST_CONNECTED_ADDRESS)
            }
        }
    }

    suspend fun updateSettings(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEVICE_NAME] = settings.deviceName
            prefs[Keys.DEVICE_MODE] = settings.deviceMode.name
            prefs[Keys.HAPTIC_FEEDBACK] = settings.hapticFeedback
            prefs[Keys.MOUSE_SENSITIVITY] = settings.mouseSensitivity
            prefs[Keys.TAP_TO_CLICK] = settings.tapToClick
            prefs[Keys.NATURAL_SCROLLING] = settings.naturalScrolling
            prefs[Keys.KEY_REPEAT_ENABLED] = settings.keyRepeatEnabled
            prefs[Keys.KEY_REPEAT_DELAY] = settings.keyRepeatDelay
            prefs[Keys.KEY_REPEAT_INTERVAL] = settings.keyRepeatInterval
            prefs[Keys.AUTO_CONNECT] = settings.autoConnect
            settings.lastConnectedAddress?.let { prefs[Keys.LAST_CONNECTED_ADDRESS] = it }
        }
    }
}
