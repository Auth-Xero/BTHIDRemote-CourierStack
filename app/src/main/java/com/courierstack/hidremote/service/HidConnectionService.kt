package com.courierstack.hidremote.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.courierstack.hidremote.MainActivity
import com.courierstack.hidremote.R
import com.courierstack.hidremote.data.AppSettings
import com.courierstack.hidremote.data.ConnectionState
import com.courierstack.hidremote.data.HidBackendMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Foreground service to maintain HID connection when app is in background.
 *
 * Creates the appropriate [IHidDeviceManager] backend based on settings:
 * - [HidBackendMode.ANDROID_NATIVE] → [AndroidHidDeviceManager] (default)
 * - [HidBackendMode.COURIER_STACK] → [CourierHidDeviceManager] (experimental backup)
 */
class HidConnectionService : Service() {

    companion object {
        private const val TAG = "HidConnectionService"
        const val CHANNEL_ID = "hid_connection_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "BTHidRemote::ConnectionWakeLock"

        const val ACTION_START = "com.courierstack.hidremote.START"
        const val ACTION_STOP = "com.courierstack.hidremote.STOP"
        const val ACTION_DISCONNECT = "com.courierstack.hidremote.DISCONNECT"
    }

    private val binder = LocalBinder()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The active HID backend. Type is the interface so ViewModel is backend-agnostic. */
    var hidManager: IHidDeviceManager? = null
        private set

    /** Which backend mode is currently active. */
    var activeBackendMode: HidBackendMode = HidBackendMode.ANDROID_NATIVE
        private set

    private var wakeLock: PowerManager.WakeLock? = null
    private var isRunning = false

    inner class LocalBinder : Binder() {
        fun getService(): HidConnectionService = this@HidConnectionService
    }

    override fun onCreate() {
        super.onCreate()
        // Create the default backend. It will be replaced if settings specify a different one.
        hidManager = createBackend(HidBackendMode.ANDROID_NATIVE)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundService()
            ACTION_STOP -> stopService()
            ACTION_DISCONNECT -> {
                hidManager?.disconnect()
                updateNotification()
            }
            null -> {
                // Null intent means Android restarted us after process death.
                // The HAL is gone so there's nothing useful to do — stop cleanly.
                stopService()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundService() {
        if (isRunning) return
        isRunning = true

        val notification = createNotification(false, null)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()

        // Monitor connection state for notification updates
        scope.launch {
            hidManager?.connectionState?.collectLatest { state ->
                val host = hidManager?.connectedHost?.value
                updateNotification(
                    state == ConnectionState.CONNECTED,
                    host?.displayName
                )
            }
        }
    }

    private fun createNotification(
        isConnected: Boolean,
        deviceName: String?
    ): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = Intent(this, HidConnectionService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = getString(R.string.notification_title)
        val text = if (isConnected && deviceName != null) {
            getString(R.string.notification_text, deviceName)
        } else {
            getString(R.string.notification_disconnected)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .apply {
                if (isConnected) {
                    addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.action_disconnect),
                        disconnectPendingIntent
                    )
                }
            }
            .build()
    }

    private fun updateNotification(isConnected: Boolean = false, deviceName: String? = null) {
        if (!isRunning) return

        val notification = createNotification(isConnected, deviceName)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                acquire(10 * 60 * 1000L) // 10 minutes max
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun stopService() {
        isRunning = false
        hidManager?.shutdown()
        releaseWakeLock()
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * Create the correct backend implementation for the given mode.
     */
    private fun createBackend(mode: HidBackendMode): IHidDeviceManager {
        return when (mode) {
            HidBackendMode.ANDROID_NATIVE -> AndroidHidDeviceManager(applicationContext)
            HidBackendMode.COURIER_STACK -> CourierHidDeviceManager(applicationContext)
        }
    }

    /**
     * Switch the backend if the requested mode differs from the active one.
     * The old backend is shut down and a new one is created.
     *
     * This should only be called when the stack is NOT initialized (i.e., before
     * [initialize] or after [stopService]).
     */
    fun ensureBackend(mode: HidBackendMode) {
        if (mode == activeBackendMode && hidManager != null) return

        Log.i(TAG, "Switching backend: $activeBackendMode → $mode")
        hidManager?.shutdown()
        activeBackendMode = mode
        hidManager = createBackend(mode)
    }

    /**
     * Initialize the HID stack with the given settings.
     * Automatically selects the correct backend based on [AppSettings.hidBackendMode].
     */
    suspend fun initialize(settings: AppSettings): Boolean {
        ensureBackend(settings.hidBackendMode)
        return hidManager?.initialize(settings) ?: false
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        hidManager?.shutdown()
        releaseWakeLock()
        scope.cancel()
    }
}
