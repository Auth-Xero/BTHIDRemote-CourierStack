package com.courierstack.hidremote.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.courierstack.hidremote.data.*
import com.courierstack.hidremote.service.HidConnectionService
import com.courierstack.hidremote.service.HidDeviceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Main ViewModel for the HID Remote application.
 *
 * Manages connection to HidConnectionService and provides UI state.
 * All state flows are properly exposed and synchronized across screens.
 */
class HidRemoteViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)

    // Service connection
    private var hidService: HidConnectionService? = null
    private var hidManager: HidDeviceManager? = null
    private var serviceBound = false

    // UI State
    private val _uiState = MutableStateFlow(HidUiState())
    val uiState: StateFlow<HidUiState> = _uiState.asStateFlow()

    // Settings
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppSettings()
        )

    // Connection state - use internal MutableStateFlows that get updated from hidManager
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedHost = MutableStateFlow<HostDevice?>(null)
    val connectedHost: StateFlow<HostDevice?> = _connectedHost.asStateFlow()

    private val _discoveredHosts = MutableStateFlow<List<HostDevice>>(emptyList())
    val discoveredHosts: StateFlow<List<HostDevice>> = _discoveredHosts.asStateFlow()

    private val _ledState = MutableStateFlow(KeyboardLedState())
    val ledState: StateFlow<KeyboardLedState> = _ledState.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    // Bonded devices list
    private val _bondedDevices = MutableStateFlow<List<String>>(emptyList())
    val bondedDevices: StateFlow<List<String>> = _bondedDevices.asStateFlow()

    /** Whether the Bluetooth stack has been initialized */
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    // Events
    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    // Modifier state for keyboard
    private val _modifierState = MutableStateFlow(ModifierState())
    val modifierState: StateFlow<ModifierState> = _modifierState.asStateFlow()

    // Vibrator for haptic feedback
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = application.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        application.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    // Job for HidManager state observation — cancel on rebind to avoid stale collectors
    private var observationJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as HidConnectionService.LocalBinder
            hidService = binder.getService()
            hidManager = hidService?.hidManager
            serviceBound = true

            // Cancel any old observation jobs (e.g., if service reconnected)
            observationJob?.cancel()

            // Start observing hidManager state
            observationJob = viewModelScope.launch {
                observeHidManagerState()
            }

            _uiState.update { it.copy(isServiceBound = true) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            observationJob?.cancel()
            observationJob = null
            hidService = null
            hidManager = null
            serviceBound = false
            _uiState.update { it.copy(isServiceBound = false) }
            _isInitialized.value = false
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    init {
        bindService()

        // Observe settings changes — run on IO to avoid blocking Main with
        // updateConfiguration() which can call sync HCI commands
        viewModelScope.launch(Dispatchers.IO) {
            settings.collect { newSettings ->
                hidManager?.updateConfiguration(newSettings)
            }
        }
    }

    /**
     * Observe state flows from HidDeviceManager and propagate to our exposed flows.
     * This ensures state is synchronized across all screens.
     *
     * Runs inside a coroutineScope so all child collectors cancel together
     * when the parent observationJob is cancelled (e.g., on service rebind).
     */
    private suspend fun observeHidManagerState() {
        val manager = hidManager ?: return

        // Snapshot the initialized/bonded state immediately
        _isInitialized.value = manager.isInitialized()
        refreshBondedDevices()

        coroutineScope {
            // Observe connection state
            launch {
                manager.connectionState.collect { state ->
                    _connectionState.value = state
                    // Refresh bonded devices after state changes (new bond after pairing, etc.)
                    _bondedDevices.value = manager.getBondedDevices()
                }
            }

            // Observe connected host
            launch {
                manager.connectedHost.collect { host ->
                    _connectedHost.value = host
                }
            }

            // Observe discovered hosts
            launch {
                manager.discoveredHosts.collect { hosts ->
                    _discoveredHosts.value = hosts
                }
            }

            // Observe LED state
            launch {
                manager.ledState.collect { led ->
                    _ledState.value = led
                }
            }

            // Observe logs
            launch {
                manager.logs.collect { logList ->
                    _logs.value = logList
                }
            }

            // Observe errors
            launch {
                manager.errors.collect { error ->
                    _events.send(UiEvent.ShowError(error))
                }
            }
        }
    }

    private fun bindService() {
        val context = getApplication<Application>()
        val intent = Intent(context, HidConnectionService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // ==================== Initialization ====================

    /**
     * Initialize the Bluetooth stack. Can only be done once per session.
     * After initialization, call startPairing() to make device discoverable.
     */
    fun initialize() {
        viewModelScope.launch {
            // Check if service is bound
            if (hidService == null) {
                _events.send(UiEvent.ShowError("Service not ready — please wait a moment and try again"))
                return@launch
            }

            // Check if already initialized
            if (hidManager?.isInitialized() == true) {
                _events.send(UiEvent.ShowToast("Already initialized - use Start Pairing"))
                return@launch
            }

            _uiState.update { it.copy(isInitializing = true) }

            val currentSettings = settings.first()
            val success = hidService?.initialize(currentSettings) ?: false

            _uiState.update { it.copy(isInitializing = false) }

            if (success) {
                _isInitialized.value = true
                refreshBondedDevices()

                // Auto-connect if enabled and we have a last connected device
                if (currentSettings.autoConnect && currentSettings.lastConnectedAddress != null) {
                    val bonded = hidManager?.getBondedDevices() ?: emptyList()
                    if (bonded.contains(currentSettings.lastConnectedAddress)) {
                        // Start pairing mode to allow reconnection
                        startPairing()
                    }
                }
            } else {
                _events.send(UiEvent.ShowError("Failed to initialize Bluetooth stack"))
            }
        }
    }

    fun startService() {
        val context = getApplication<Application>()
        val intent = Intent(context, HidConnectionService::class.java).apply {
            action = HidConnectionService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopService() {
        // Reset all UI state immediately so the HomeScreen returns to "Initialize"
        _isInitialized.value = false
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedHost.value = null
        _modifierState.value = ModifierState()
        _ledState.value = KeyboardLedState()
        _bondedDevices.value = emptyList()
        _discoveredHosts.value = emptyList()
        _uiState.update { it.copy(isInitializing = false) }

        val context = getApplication<Application>()
        val intent = Intent(context, HidConnectionService::class.java).apply {
            action = HidConnectionService.ACTION_STOP
        }
        context.startService(intent)
    }

    // ==================== Bonded Devices ====================

    /**
     * Refresh the list of bonded devices from HidDeviceManager.
     */
    fun refreshBondedDevices() {
        _bondedDevices.value = hidManager?.getBondedDevices() ?: emptyList()
    }

    /**
     * Remove a bond for a specific device.
     */
    fun removeBond(address: String) {
        hidManager?.removeBond(address)
        refreshBondedDevices()
    }

    /**
     * Clear all stored bonds.
     */
    fun clearAllBonds() {
        hidManager?.clearAllBonds()
        refreshBondedDevices()
    }

    // ==================== Pairing ====================

    /**
     * Start pairing mode - makes the device discoverable for hosts.
     * Must be initialized first.
     */
    fun startPairing() {
        if (hidManager?.isInitialized() != true) {
            viewModelScope.launch {
                _events.send(UiEvent.ShowError("Initialize first before starting pairing"))
            }
            return
        }

        val success = hidManager?.startPairing() ?: false
        if (!success) {
            viewModelScope.launch {
                _events.send(UiEvent.ShowError("Failed to start pairing mode"))
            }
        }
    }

    /**
     * Stop pairing mode - hides device from discovery.
     */
    fun stopPairing() {
        hidManager?.stopPairing()
    }

    /**
     * Alias for startPairing().
     */
    fun startAdvertising() {
        startPairing()
    }

    /**
     * Alias for stopPairing().
     */
    fun stopAdvertising() {
        stopPairing()
    }

    // ==================== Scanning & Connection ====================

    fun startScanning() {
        hidManager?.startScanning()
    }

    fun stopScanning() {
        hidManager?.stopScanning()
    }

    /**
     * Connect to a host - for HID device role, this makes us discoverable
     * and waits for the host to connect to us.
     */
    fun connectToHost(host: HostDevice) {
        viewModelScope.launch {
            hidManager?.connectToHost(host)
            settingsRepository.updateLastConnectedAddress(host.address)
        }
    }

    /**
     * Connect to a previously bonded device by address.
     * Initiates active reconnection (HCI Create_Connection) plus passive mode.
     */
    fun connectToBondedDevice(address: String) {
        if (hidManager?.isInitialized() != true) {
            viewModelScope.launch {
                _events.send(UiEvent.ShowError("Initialize first"))
            }
            return
        }

        // connectToBondedDevice returns immediately — heavy work runs on IO internally
        val success = hidManager?.connectToBondedDevice(address) ?: false

        viewModelScope.launch {
            if (success) {
                settingsRepository.updateLastConnectedAddress(address)
                _events.send(UiEvent.ShowToast("Reconnecting to $address..."))
            } else {
                _events.send(UiEvent.ShowError("Failed - try pairing again"))
            }
        }
    }

    fun disconnect() {
        hidManager?.disconnect()
    }

    // ==================== Keyboard Input ====================

    fun pressKey(keyCode: Int) {
        hidManager?.pressKey(keyCode, _modifierState.value)
        hapticFeedback()
    }

    fun releaseKey(keyCode: Int) {
        hidManager?.releaseKey(keyCode)
    }

    fun typeKey(keyCode: Int) {
        hidManager?.typeKey(keyCode, _modifierState.value)
        hapticFeedback()
    }

    /**
     * Type a key with specific modifiers atomically (for shortcuts like Ctrl+C).
     * This ensures the modifier and key are sent together in one report,
     * avoiding race conditions from toggling modifiers separately.
     */
    fun typeKeyWithModifiers(keyCode: Int, modifiers: ModifierState) {
        hidManager?.typeKeyWithModifiers(keyCode, modifiers)
        hapticFeedback()
    }

    fun typeText(text: String) {
        hidManager?.typeText(text)
    }

    fun releaseAllKeys() {
        hidManager?.releaseAllKeys()
        _modifierState.value = ModifierState()
    }

    fun toggleModifier(modifier: String) {
        _modifierState.update { state ->
            when (modifier) {
                "ctrl" -> state.copy(leftCtrl = !state.leftCtrl)
                "shift" -> state.copy(leftShift = !state.leftShift)
                "alt" -> state.copy(leftAlt = !state.leftAlt)
                "gui" -> state.copy(leftGui = !state.leftGui)
                else -> state
            }
        }
        hidManager?.setModifiers(_modifierState.value)
        hapticFeedback()
    }

    fun setModifierPressed(modifier: String, pressed: Boolean) {
        _modifierState.update { state ->
            when (modifier) {
                "ctrl" -> state.copy(leftCtrl = pressed)
                "shift" -> state.copy(leftShift = pressed)
                "alt" -> state.copy(leftAlt = pressed)
                "gui" -> state.copy(leftGui = pressed)
                else -> state
            }
        }
        hidManager?.setModifiers(_modifierState.value)
        if (pressed) hapticFeedback()
    }

    // ==================== Mouse Input ====================

    fun moveMouse(dx: Float, dy: Float, sensitivity: Float = 1f) {
        val scaledDx = (dx * sensitivity).toInt()
        val scaledDy = (dy * sensitivity).toInt()
        hidManager?.moveMouse(scaledDx, scaledDy)
    }

    fun scroll(amount: Float, natural: Boolean = false) {
        val direction = if (natural) -1 else 1
        hidManager?.scroll((amount * direction).toInt())
    }

    fun pressMouseButton(button: Int) {
        hidManager?.pressMouseButton(button)
        hapticFeedback()
    }

    fun releaseMouseButton(button: Int) {
        hidManager?.releaseMouseButton(button)
    }

    fun clickMouseButton(button: Int) {
        hidManager?.clickMouseButton(button)
        hapticFeedback()
    }

    fun tapClick() {
        viewModelScope.launch {
            if (settings.first().tapToClick) {
                clickMouseButton(MouseButtons.LEFT)
            }
        }
    }

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
    ) {
        hidManager?.sendGamepad(buttons, leftX, leftY, rightX, rightY, leftTrigger, rightTrigger, dpad)
    }

    // ==================== Settings ====================

    fun updateDeviceName(name: String) {
        viewModelScope.launch {
            settingsRepository.updateDeviceName(name)
        }
    }

    fun updateDeviceMode(mode: DeviceMode) {
        viewModelScope.launch {
            settingsRepository.updateDeviceMode(mode)
        }
    }

    fun updateHapticFeedback(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateHapticFeedback(enabled)
        }
    }

    fun updateMouseSensitivity(sensitivity: Float) {
        viewModelScope.launch {
            settingsRepository.updateMouseSensitivity(sensitivity)
        }
    }

    fun updateTapToClick(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateTapToClick(enabled)
        }
    }

    fun updateNaturalScrolling(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateNaturalScrolling(enabled)
        }
    }

    fun updateAutoConnect(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAutoConnect(enabled)
        }
    }

    // ==================== Haptic Feedback ====================

    private fun hapticFeedback() {
        viewModelScope.launch {
            if (settings.first().hapticFeedback) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(10)
                }
            }
        }
    }

    // ==================== Cleanup ====================

    override fun onCleared() {
        super.onCleared()
        if (serviceBound) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}

/**
 * UI State for the HID Remote app.
 */
data class HidUiState(
    val isServiceBound: Boolean = false,
    val isInitializing: Boolean = false,
    val selectedTab: Int = 0
)