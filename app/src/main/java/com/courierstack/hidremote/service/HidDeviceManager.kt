package com.courierstack.hidremote.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.courierstack.core.CourierStackManager
import com.courierstack.core.IStackListener
import com.courierstack.gap.DeviceDiscovery
import com.courierstack.gap.DiscoveredDevice
import com.courierstack.gap.IDiscoveryListener
import com.courierstack.hci.HciCommands
import com.courierstack.hid.HidConstants
import com.courierstack.hid.HidDeviceProfile
import com.courierstack.hid.HidReport
import com.courierstack.hidremote.data.*
import com.courierstack.l2cap.AclConnection
import com.courierstack.l2cap.IL2capListener
import com.courierstack.l2cap.IL2capServerListener
import com.courierstack.l2cap.L2capChannel
import com.courierstack.l2cap.L2capConstants
import com.courierstack.l2cap.L2capManager
import com.courierstack.sdp.SdpDatabase
import com.courierstack.sdp.SdpManager
import com.courierstack.security.bredr.BondingInfo
import com.courierstack.security.bredr.BrEdrPairingManager
import com.courierstack.security.bredr.BrEdrPairingMode
import com.courierstack.security.bredr.IBrEdrPairingListener
import com.courierstack.security.le.ISmpListener
import com.courierstack.security.le.SmpManager
import com.courierstack.util.LogEntry
import com.courierstack.util.LogType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.courierstack.security.le.BondingInfo as SmpBondingInfo

/**
 * HID Device Manager that wraps CourierStack for HID device emulation.
 *
 * This implementation properly initializes CourierStack (following STFUinator's pattern)
 * and uses the existing HidDeviceProfile class for all HID protocol handling.
 *
 * Features:
 * - Proper HAL initialization with kill of Android Bluetooth stack
 * - L2CAP, SDP, and pairing manager setup
 * - Uses HidDeviceProfile for SDP registration and HID protocol
 * - Makes device discoverable via HCI commands
 * - Keyboard, mouse, combo, and gamepad modes
 */
class HidDeviceManager(private val context: Context) {

    companion object {
        private const val TAG = "HidDeviceManager"
        private const val MAX_KEYS = 6

        // SharedPreferences
        private const val PREFS_NAME = "hid_device_bonds"
        private const val KEY_BONDED_DEVICES = "bonded_devices"

        // Reconnection
        private const val MAX_RECONNECT_ATTEMPTS = 3

        // Scan enable values
        private const val SCAN_DISABLED = 0x00
        private const val SCAN_INQUIRY_ONLY = 0x01
        private const val SCAN_PAGE_ONLY = 0x02
        private const val SCAN_INQUIRY_AND_PAGE = 0x03

        // Class of Device values for HID
        // Format: [Minor Device Class, Major Device Class | Service Class bits, Service Class]
        // Major Device Class 0x05 = Peripheral
        // Minor: 0x40 = Keyboard, 0x80 = Mouse, 0xC0 = Combo
        private val COD_KEYBOARD = byteArrayOf(0x40, 0x25, 0x00)      // 0x002540
        private val COD_MOUSE = byteArrayOf(0x80.toByte(), 0x25, 0x00) // 0x002580
        private val COD_COMBO = byteArrayOf(0xC0.toByte(), 0x25, 0x00) // 0x0025C0
        private val COD_GAMEPAD = byteArrayOf(0x08, 0x25, 0x00)       // 0x002508
    }

    // ==================== File Logger ====================
    private var logFile: File? = null
    private var logWriter: PrintWriter? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private fun initFileLogger() {
        try {
            val logsDir = File(context.getExternalFilesDir(null), "logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            logFile = File(logsDir, "hid_debug.log")
            logWriter = PrintWriter(FileWriter(logFile, true), true)

            fileLog("========================================")
            fileLog("HID Device Manager Debug Log Started")
            fileLog("Timestamp: ${dateFormat.format(Date())}")
            fileLog("========================================")

            Log.i(TAG, "File logging initialized: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize file logging: ${e.message}")
        }
    }

    private fun fileLog(message: String) {
        try {
            val timestamp = dateFormat.format(Date())
            val logLine = "$timestamp $message"
            logWriter?.println(logLine)
            logWriter?.flush()
        } catch (e: Exception) {
            // Ignore logging errors
        }
    }

    private fun fileLog(tag: String, message: String, level: String = "I") {
        fileLog("$level/$tag: $message")
    }

    private fun closeFileLogger() {
        try {
            fileLog("========================================")
            fileLog("Log session ended")
            fileLog("========================================")
            logWriter?.close()
            logWriter = null
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Get the path to the current log file (for sharing/viewing)
     */
    fun getLogFilePath(): String? = logFile?.absolutePath

    // SharedPreferences for bonding persistence
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ==================== CourierStack Components ====================
    private var courierStack: CourierStackManager? = null
    private var l2capManager: L2capManager? = null
    private var sdpManager: SdpManager? = null
    private var sdpDatabase: SdpDatabase? = null
    private var pairingManager: BrEdrPairingManager? = null
    private var smpManager: SmpManager? = null
    private var scannerManager: DeviceDiscovery? = null

    // HID Device Profile - handles all HID protocol details
    private var hidDeviceProfile: HidDeviceProfile? = null

    // ==================== State ====================
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedHost = MutableStateFlow<HostDevice?>(null)
    val connectedHost: StateFlow<HostDevice?> = _connectedHost.asStateFlow()

    private val _discoveredHosts = MutableStateFlow<List<HostDevice>>(emptyList())
    val discoveredHosts: StateFlow<List<HostDevice>> = _discoveredHosts.asStateFlow()

    private val _ledState = MutableStateFlow(KeyboardLedState())
    val ledState: StateFlow<KeyboardLedState> = _ledState.asStateFlow()

    private val _logs = MutableStateFlow<List<com.courierstack.hidremote.data.LogEntry>>(emptyList())
    val logs: StateFlow<List<com.courierstack.hidremote.data.LogEntry>> = _logs.asStateFlow()

    private val _errorChannel = Channel<String>(Channel.BUFFERED)
    val errors: Flow<String> = _errorChannel.receiveAsFlow()

    // ==================== Input State ====================
    private val pressedKeys = mutableSetOf<Int>()
    private var currentModifiers = ModifierState()
    private var currentMouseButtons = MouseButtonState()

    // ==================== Configuration ====================
    private var deviceMode = DeviceMode.COMBO
    private var deviceName = "BT HID Remote"

    // ==================== Runtime State ====================
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialized = AtomicBoolean(false)

    // Current host connection from HidDeviceProfile
    private var currentHostConnection: HidDeviceProfile.HostConnection? = null

    // Discovered devices map
    private val discoveredDevices = ConcurrentHashMap<String, DiscoveredDevice>()

    // ==================== NEW: Track pairing state separately from HID connection ====================
    // Windows disconnects after SDP discovery during pairing - this is normal!
    // We need to track that we're waiting for the host to reconnect with HID channels
    private val isPairingMode = AtomicBoolean(false)
    private val isWaitingForHostConnection = AtomicBoolean(false)
    private val userDisconnecting = AtomicBoolean(false)
    private var lastPairedAddress: String? = null

    // ==================== Listeners ====================

    /** Raw HCI event listener to capture Link_Key_Notification directly */
    private val rawEventListener = L2capManager.IRawEventListener { event ->
        handleRawHciEvent(event)
    }

    /**
     * Handle raw HCI events - specifically Link_Key_Notification (0x18)
     * This is a fallback in case BondingInfo doesn't contain the link key.
     */
    private fun handleRawHciEvent(event: ByteArray) {
        if (event.size < 2) return
        val eventCode = event[0].toInt() and 0xFF

        if (eventCode == 0x18) { // Link_Key_Notification
            handleLinkKeyNotification(event)
        }
    }

    /**
     * Handle HCI Link_Key_Notification event (0x18).
     *
     * Event format:
     *   [0] Event code (0x18)
     *   [1] Parameter length (23)
     *   [2-7] BD_ADDR (6 bytes)
     *   [8-23] Link_Key (16 bytes)
     *   [24] Key_Type (1 byte)
     */
    private fun handleLinkKeyNotification(event: ByteArray) {
        if (event.size < 25) {
            log(TAG, "Link_Key_Notification too short: ${event.size}", LogLevel.WARNING)
            return
        }

        val bdAddr = ByteArray(6)
        System.arraycopy(event, 2, bdAddr, 0, 6)
        val addrStr = formatAddress(bdAddr)

        val linkKey = ByteArray(16)
        System.arraycopy(event, 8, linkKey, 0, 16)

        val keyType = event[24].toInt() and 0xFF

        log(TAG, "Captured Link Key from HCI for $addrStr (type: $keyType)", LogLevel.INFO)

        // Save directly from HCI event
        saveDirectLinkKey(bdAddr, linkKey, keyType)
    }

    /**
     * Save link key captured directly from HCI event.
     */
    private fun saveDirectLinkKey(address: ByteArray, linkKey: ByteArray, keyType: Int) {
        try {
            val addrStr = formatAddress(address)

            // Load existing bonds
            val bonds = loadAllBonds().toMutableMap()

            // Add/update this bond
            bonds[addrStr] = SavedBond(addrStr, linkKey, keyType)

            // Save to preferences as JSON
            val jsonArray = JSONArray()
            for ((addr, bond) in bonds) {
                val obj = JSONObject().apply {
                    put("address", addr)
                    put("linkKey", Base64.encodeToString(bond.linkKey, Base64.NO_WRAP))
                    put("linkKeyType", bond.linkKeyType)
                }
                jsonArray.put(obj)
            }

            prefs.edit().putString(KEY_BONDED_DEVICES, jsonArray.toString()).apply()
            log(TAG, "Link key saved for $addrStr from HCI event", LogLevel.INFO)

        } catch (e: Exception) {
            log(TAG, "Failed to save link key: ${e.message}", LogLevel.ERROR)
        }
    }

    private val l2capListener = object : IL2capListener {
        override fun onConnectionComplete(connection: AclConnection) {
            fileLog(TAG, "ACL connected: ${connection.getFormattedAddress()}")
            log(TAG, "ACL connected: ${connection.getFormattedAddress()}", LogLevel.INFO)
        }
        override fun onDisconnectionComplete(handle: Int, reason: Int) {
            fileLog(TAG, "ACL disconnected: handle=0x${Integer.toHexString(handle)}, reason=0x${Integer.toHexString(reason)}")
            log(TAG, "ACL disconnected: reason=0x${Integer.toHexString(reason)}", LogLevel.INFO)

            // Don't touch state if we're already connected via HID profile.
            // ACL disconnect events can arrive for stale handles or intermediate
            // connections (SDP, etc.) even while HID channels are alive.
            if (_connectionState.value == ConnectionState.CONNECTED) {
                fileLog(TAG, "Ignoring ACL disconnect — HID profile still connected")
                return
            }

            // Reason 0x13 = Remote User Terminated - normal for Windows after SDP
            if (isPairingMode.get()) {
                fileLog(TAG, "ACL dropped during pairing/reconnect - re-enabling scan")

                courierStack?.hciManager?.let { hci ->
                    try {
                        hci.sendCommand(HciCommands.writeScanEnable(SCAN_INQUIRY_AND_PAGE))
                    } catch (e: Exception) {
                        fileLog(TAG, "Failed to re-enable scan: ${e.message}", "E")
                    }
                }

                // Only set CONNECTING if not already CONNECTED
                // (avoid overwriting CONNECTED that may have been set concurrently)
                _connectionState.update { current ->
                    if (current != ConnectionState.CONNECTED) ConnectionState.CONNECTING
                    else current
                }
            }
        }
        override fun onChannelOpened(channel: L2capChannel) {
            fileLog(TAG, "L2CAP channel opened: localCid=0x${Integer.toHexString(channel.localCid)}, psm=0x${Integer.toHexString(channel.psm)}")
        }
        override fun onChannelClosed(channel: L2capChannel) {
            fileLog(TAG, "L2CAP channel closed: localCid=0x${Integer.toHexString(channel.localCid)}")
        }
        override fun onDataReceived(channel: L2capChannel, data: ByteArray) {
            fileLog(TAG, "L2CAP data received: localCid=0x${Integer.toHexString(channel.localCid)}, len=${data.size}")
        }
        override fun onConnectionRequest(handle: Int, psm: Int, sourceCid: Int) {
            fileLog(TAG, "L2CAP connection request: handle=0x${Integer.toHexString(handle)}, psm=0x${Integer.toHexString(psm)}, srcCid=0x${Integer.toHexString(sourceCid)}")

            // Log when we receive HID channel connection requests - this means host is connecting!
            when (psm) {
                L2capConstants.PSM_HID_CONTROL -> {
                    fileLog(TAG, ">>> HID Control channel connection request!")
                    log(TAG, "Host connecting to HID Control channel", LogLevel.INFO)
                }
                L2capConstants.PSM_HID_INTERRUPT -> {
                    fileLog(TAG, ">>> HID Interrupt channel connection request!")
                    log(TAG, "Host connecting to HID Interrupt channel", LogLevel.INFO)
                }
            }
        }
        override fun onError(message: String) {
            fileLog(TAG, "L2CAP error: $message", "E")
            log(TAG, "L2CAP error: $message", LogLevel.ERROR)
        }
        override fun onMessage(message: String) {
            fileLog(TAG, "L2CAP: $message")
        }
    }

    private val scanListener = object : IDiscoveryListener {
        override fun onDeviceFound(device: DiscoveredDevice) {
            fileLog(TAG, "Device found: ${device.address}")
            discoveredDevices[device.address] = device
            updateDiscoveredHostsList()
        }
        override fun onScanStateChanged(scanning: Boolean) {
            fileLog(TAG, "Scan state changed: scanning=$scanning")
            if (scanning) {
                _connectionState.value = ConnectionState.SCANNING
            } else if (_connectionState.value == ConnectionState.SCANNING) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
        override fun onScanComplete() {
            fileLog(TAG, "Scan complete")
        }
        override fun onError(message: String) {
            fileLog(TAG, "Scan error: $message", "E")
            log(TAG, "Scan error: $message", LogLevel.ERROR)
        }
    }

    private val pairingListener = object : IBrEdrPairingListener {
        override fun onPairingStarted(handle: Int, address: ByteArray) {
            val addrStr = formatAddress(address)
            fileLog(TAG, "Pairing started: $addrStr")
            log(TAG, "Pairing started: $addrStr", LogLevel.INFO)
            isPairingMode.set(true)
            lastPairedAddress = addrStr
        }
        override fun onIoCapabilityRequest(handle: Int, address: ByteArray) {
            fileLog(TAG, "IO Capability Request: ${formatAddress(address)}")
        }
        override fun onNumericComparison(handle: Int, address: ByteArray, value: Int) {
            fileLog(TAG, "Numeric comparison: $value - auto-accepting")
            log(TAG, "Numeric comparison: $value - auto-accepting", LogLevel.INFO)
            pairingManager?.confirmNumericComparison(handle, true)
        }
        override fun onPasskeyRequest(handle: Int, address: ByteArray, display: Boolean, passkey: Int) {
            fileLog(TAG, "Passkey request: ${formatAddress(address)}, passkey=$passkey, display=$display")
            log(TAG, "Passkey: $passkey", LogLevel.INFO)
        }
        override fun onPairingComplete(handle: Int, address: ByteArray, success: Boolean, info: BondingInfo?) {
            val addrStr = formatAddress(address)
            fileLog(TAG, "========================================")
            fileLog(TAG, "PAIRING COMPLETE: success=$success for $addrStr")
            fileLog(TAG, "========================================")
            log(TAG, "Pairing complete: $success for $addrStr", LogLevel.INFO)

            if (success) {
                if (info != null) {
                    fileLog(TAG, "Saving bonding info...")
                    saveBondingInfo(address, info)
                }

                // Keep isPairingMode = true until Windows disconnects
                // Windows will disconnect after SDP, then we stay connectable
                lastPairedAddress = addrStr
                log(TAG, "Paired with $addrStr - waiting for HID connection", LogLevel.INFO)
            } else {
                isPairingMode.set(false)
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
        override fun onPairingFailed(handle: Int, address: ByteArray, errorCode: Int, reason: String) {
            fileLog(TAG, "Pairing FAILED: ${formatAddress(address)}, errorCode=$errorCode, reason=$reason", "E")
            log(TAG, "Pairing failed: $reason", LogLevel.ERROR)
            isPairingMode.set(false)
            isWaitingForHostConnection.set(false)
        }
        override fun onError(message: String) {
            fileLog(TAG, "Pairing error: $message", "E")
        }
        override fun onMessage(message: String) {
            fileLog(TAG, "Pairing: $message")
        }
    }

    private val smpListener = object : ISmpListener {
        override fun onPairingStarted(handle: Int, address: ByteArray) {
            fileLog(TAG, "SMP pairing started: ${formatAddress(address)}")
        }
        override fun onPairingRequest(handle: Int, address: ByteArray, ioCap: Int, authReq: Int, sc: Boolean) {
            fileLog(TAG, "SMP pairing request: ${formatAddress(address)}, ioCap=$ioCap, authReq=$authReq, sc=$sc")
        }
        override fun onPairingComplete(handle: Int, address: ByteArray, success: Boolean, info: SmpBondingInfo?) {
            fileLog(TAG, "SMP pairing complete: ${formatAddress(address)}, success=$success")
        }
        override fun onPairingFailed(handle: Int, address: ByteArray, errorCode: Int, reason: String) {
            fileLog(TAG, "SMP pairing failed: ${formatAddress(address)}, errorCode=$errorCode, reason=$reason", "E")
        }
        override fun onPasskeyRequired(handle: Int, address: ByteArray, display: Boolean, passkey: Int) {
            fileLog(TAG, "SMP passkey required: ${formatAddress(address)}")
        }
        override fun onNumericComparisonRequired(handle: Int, address: ByteArray, numericValue: Int) {
            fileLog(TAG, "SMP numeric comparison: ${formatAddress(address)}, value=$numericValue - auto-accepting")
            smpManager?.confirmNumericComparison(handle, true)
        }
        override fun onEncryptionChanged(handle: Int, encrypted: Boolean) {
            fileLog(TAG, "SMP encryption changed: handle=0x${Integer.toHexString(handle)}, encrypted=$encrypted")
        }
        override fun onError(message: String) {
            fileLog(TAG, "SMP error: $message", "E")
        }
        override fun onMessage(message: String) {
            fileLog(TAG, "SMP: $message")
        }
    }

    /** HID Device Profile listener - handles host connections and reports */
    private val hidProfileListener = object : HidDeviceProfile.Listener {
        override fun onHostConnected(host: HidDeviceProfile.HostConnection) {
            fileLog(TAG, "========================================")
            fileLog(TAG, ">>> HID HOST CONNECTED: ${host.addressString}")
            fileLog(TAG, ">>> Control + Interrupt channels established!")
            fileLog(TAG, "========================================")
            log(TAG, "Connected to ${host.addressString}", LogLevel.INFO)

            currentHostConnection = host
            _connectedHost.value = HostDevice(
                address = host.addressString,
                name = host.addressString,
                isConnected = true
            )
            _connectionState.value = ConnectionState.CONNECTED

            // Clear pairing flags
            isPairingMode.set(false)
            isWaitingForHostConnection.set(false)
        }

        override fun onHostDisconnected(host: HidDeviceProfile.HostConnection, reason: Int) {
            fileLog(TAG, "HID Host disconnected: ${host.addressString}, reason=0x${Integer.toHexString(reason)}")
            log(TAG, "Disconnected from ${host.addressString}", LogLevel.INFO)

            if (currentHostConnection?.addressString == host.addressString) {
                val disconnectedAddr = host.addressString
                currentHostConnection = null
                _connectedHost.value = null
                clearInputState()

                // Re-enable scan for reconnection. Async — we're on the
                // L2CAP event thread so sendCommandSync deadlocks here.
                courierStack?.hciManager?.let { hci ->
                    try {
                        hci.sendCommand(HciCommands.writeScanEnable(SCAN_INQUIRY_AND_PAGE))
                    } catch (e: Exception) {
                        fileLog(TAG, "Failed to re-enable scan: ${e.message}", "E")
                    }
                }

                // If this wasn't a user-initiated disconnect and we have a bond,
                // attempt automatic reconnection.
                val hasBond = loadAllBonds().containsKey(disconnectedAddr)

                if (!userDisconnecting.get() && hasBond) {
                    _connectionState.value = ConnectionState.CONNECTING
                    isPairingMode.set(true) // needed so retry loop doesn't abort
                    lastPairedAddress = disconnectedAddr
                    log(TAG, "Host dropped — auto-reconnecting...", LogLevel.INFO)
                    scope.launch {
                        delay(1000) // brief pause before retry
                        if (_connectionState.value != ConnectionState.CONNECTED) {
                            restoreLinkKeyForDevice(disconnectedAddr)
                            initiateConnectionToHost(disconnectedAddr)
                        }
                    }
                } else {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    log(TAG, "Disconnected", LogLevel.INFO)
                }
            }
        }

        override fun onOutputReport(host: HidDeviceProfile.HostConnection, report: HidReport) {
            // Handle keyboard LED state from output report
            if (report.type == HidConstants.REPORT_TYPE_OUTPUT && report.length >= 1) {
                val ledByte = report.getByte(0)
                _ledState.value = KeyboardLedState.fromByte(ledByte)
            }
        }

        override fun onGetFeatureReport(host: HidDeviceProfile.HostConnection, reportId: Int): ByteArray? {
            return buildCurrentKeyboardReport()
        }

        override fun onProtocolChanged(protocol: Int) {}
        override fun onSuspend() {}
        override fun onExitSuspend() {}

        override fun onVirtualCableUnplug(host: HidDeviceProfile.HostConnection) {
            disconnect()
        }

        override fun onError(message: String) {
            log(TAG, "HID error: $message", LogLevel.ERROR)
            scope.launch { _errorChannel.send(message) }
        }

        override fun onMessage(message: String) {
            fileLog(TAG, "HID: $message")
        }
    }

    private val stackListener = object : IStackListener {
        override fun onInitialized(success: Boolean) {}
        override fun onLog(entry: LogEntry) {}
        override fun onError(message: String) {
            log(TAG, "Stack error: $message", LogLevel.ERROR)
            scope.launch { _errorChannel.send(message) }
        }
        override fun onStateChanged(state: CourierStackManager.State) {}
        override fun onShutdown() {
            handleDisconnection()
        }
    }

    // ==================== Initialization ====================

    /**
     * Initialize the Bluetooth stack following STFUinator's pattern.
     *
     * IMPORTANT: This can only be called ONCE. If already initialized, returns true
     * without reinitializing. To make the device visible for pairing after init,
     * call startPairing().
     *
     * @return true if initialized (or already was), false on error
     */
    suspend fun initialize(settings: AppSettings): Boolean = withContext(Dispatchers.IO) {
        // Initialize file logging first
        initFileLogger()
        fileLog(TAG, "========== INITIALIZATION STARTED ==========")

        // Strict check - never reinitialize
        if (initialized.get()) {
            fileLog(TAG, "Already initialized - use startPairing() to make device discoverable", "W")
            log(TAG, "Already initialized - use startPairing() to make device discoverable", LogLevel.WARNING)
            return@withContext true
        }

        _connectionState.value = ConnectionState.INITIALIZING
        deviceMode = settings.deviceMode
        deviceName = settings.deviceName

        fileLog(TAG, "Device mode: $deviceMode, name: $deviceName")

        try {
            fileLog(TAG, "Step 1: Getting CourierStack singleton...")
            log(TAG, "Initializing CourierStack...", LogLevel.INFO)

            // Step 1: Get CourierStack singleton
            courierStack = CourierStackManager.getInstance()
            courierStack?.addStackListener(stackListener)
            fileLog(TAG, "CourierStack singleton obtained")

            // Step 2: Initialize with HAL kill (like STFUinator)
            fileLog(TAG, "Step 2: Initializing HAL (with kill)...")
            val initLatch = CountDownLatch(1)
            var initSuccess = false
            var initError: String? = null

            courierStack?.initializeWithHalKill(context) { success, error ->
                initSuccess = success
                initError = error
                fileLog(TAG, "HAL init callback: success=$success, error=$error")
                initLatch.countDown()
            }

            fileLog(TAG, "Waiting for HAL initialization (30s timeout)...")
            if (!initLatch.await(30, TimeUnit.SECONDS) || !initSuccess) {
                fileLog(TAG, "HAL initialization FAILED: ${initError ?: "timeout"}", "E")
                throw Exception(initError ?: "HAL initialization failed/timeout")
            }

            fileLog(TAG, "HAL initialized successfully")
            log(TAG, "HAL initialized successfully", LogLevel.INFO)

            // Restore stored link keys for reconnection to previously paired devices
            fileLog(TAG, "Restoring stored link keys...")
            restoreStoredLinkKeys()

            // Step 3: Initialize L2CAP
            fileLog(TAG, "Step 3: Initializing L2CAP...")
            val hciManager = courierStack?.hciManager
            if (hciManager == null) {
                fileLog(TAG, "HCI manager is NULL!", "E")
                throw Exception("HCI manager not available")
            }
            fileLog(TAG, "HCI manager obtained")

            l2capManager = L2capManager(context, l2capListener, hciManager)
            val l2capResult = l2capManager!!.initialize()
            fileLog(TAG, "L2CAP initialize() returned: $l2capResult")
            if (!l2capResult) {
                throw Exception("L2CAP initialization failed")
            }

            // Register raw HCI event listener to capture Link Key Notifications
            l2capManager!!.addRawEventListener(rawEventListener)
            fileLog(TAG, "Raw HCI event listener registered")

            // Step 4: Initialize device discovery
            fileLog(TAG, "Step 4: Initializing device discovery...")
            scannerManager = DeviceDiscovery(l2capManager!!.hciManager)
            scannerManager!!.addListener(scanListener)
            fileLog(TAG, "Device discovery initialized")

            // Step 5: Initialize pairing managers
            fileLog(TAG, "Step 5: Initializing pairing managers...")
            pairingManager = BrEdrPairingManager(l2capManager!!, pairingListener).apply {
                initialize()
            }
            fileLog(TAG, "BR/EDR pairing manager initialized")

            smpManager = SmpManager(l2capManager!!, smpListener).apply {
                initialize()
            }
            fileLog(TAG, "SMP manager initialized")

            courierStack?.getLocalAddress()?.let {
                smpManager!!.setLocalAddress(it, 0)
                fileLog(TAG, "Local address set for SMP: ${formatAddress(it)}")
            }

            pairingManager!!.setSmpManager(smpManager!!)
            pairingManager!!.setDefaultPairingMode(BrEdrPairingMode.JUST_WORKS)
            pairingManager!!.setAutoAccept(true)
            pairingManager!!.enableSsp()
            fileLog(TAG, "Pairing configured: JUST_WORKS, auto-accept, SSP enabled")

            // Step 6: Initialize SDP Manager and start server
            // CRITICAL: SDP server must be running before device becomes discoverable
            fileLog(TAG, "========================================")
            fileLog(TAG, "Step 6: Initializing SDP Manager (CRITICAL)")
            fileLog(TAG, "========================================")

            val sdpListener = object : SdpManager.ISdpListener {
                override fun onMessage(message: String) {
                    fileLog(TAG, "[SDP] $message")
                    Log.i(TAG, "[SDP] $message")
                    log(TAG, "SDP: $message", LogLevel.INFO)
                }
                override fun onError(message: String) {
                    fileLog(TAG, "[SDP ERROR] $message", "E")
                    Log.e(TAG, "[SDP ERROR] $message")
                    log(TAG, "SDP Error: $message", LogLevel.ERROR)
                }
            }

            fileLog(TAG, "Creating SdpManager instance...")
            sdpManager = SdpManager(l2capManager!!, sdpListener)
            fileLog(TAG, "SdpManager created: ${sdpManager != null}")

            fileLog(TAG, "Calling sdpManager.initialize()...")
            val sdpInitResult = sdpManager!!.initialize()
            fileLog(TAG, "sdpManager.initialize() returned: $sdpInitResult")

            if (!sdpInitResult) {
                fileLog(TAG, "WARNING: SDP Manager initialize() returned false!", "W")
            }

            fileLog(TAG, "Calling sdpManager.startServer()...")
            sdpManager!!.startServer()
            fileLog(TAG, "sdpManager.startServer() completed")

            fileLog(TAG, "Getting SDP database...")
            sdpDatabase = sdpManager!!.database
            fileLog(TAG, "SDP database obtained: ${sdpDatabase != null}")

            if (sdpDatabase == null) {
                fileLog(TAG, "CRITICAL: SDP database is NULL!", "E")
                throw Exception("SDP database is null after initialization!")
            }

            val initialServiceCount = sdpDatabase!!.serviceCount
            fileLog(TAG, "Initial SDP service count: $initialServiceCount")

            // Step 7: Create and start HID Device Profile
            fileLog(TAG, "========================================")
            fileLog(TAG, "Step 7: Initializing HID Profile")
            fileLog(TAG, "========================================")
            initializeHidProfile()

            // Verify HID service was registered
            val finalServiceCount = sdpDatabase!!.serviceCount
            fileLog(TAG, "Final SDP service count: $finalServiceCount")
            fileLog(TAG, "Expected: 2 services (SDP server + HID)")
            if (finalServiceCount < 2) {
                fileLog(TAG, "WARNING: Service count is less than expected!", "W")
            }

            // NOTE: We do NOT auto-enable discoverability here.
            // User must explicitly call startPairing() to make device visible.

            initialized.set(true)
            _connectionState.value = ConnectionState.DISCONNECTED

            fileLog(TAG, "========================================")
            fileLog(TAG, "INITIALIZATION COMPLETED SUCCESSFULLY")
            fileLog(TAG, "========================================")
            fileLog(TAG, "Log file: ${logFile?.absolutePath}")
            log(TAG, "HID Device Manager initialized! Call startPairing() to make device discoverable.", LogLevel.INFO)
            true

        } catch (e: Exception) {
            fileLog(TAG, "========================================", "E")
            fileLog(TAG, "INITIALIZATION FAILED: ${e.message}", "E")
            fileLog(TAG, "Stack trace:", "E")
            e.stackTrace.take(10).forEach {
                fileLog(TAG, "  at $it", "E")
            }
            fileLog(TAG, "========================================", "E")
            log(TAG, "Initialization failed: ${e.message}", LogLevel.ERROR)
            _connectionState.value = ConnectionState.ERROR
            _errorChannel.send("Initialization failed: ${e.message}")
            false
        }
    }

    private fun initializeHidProfile() {
        fileLog(TAG, "initializeHidProfile() called")

        val l2cap = l2capManager
        if (l2cap == null) {
            fileLog(TAG, "CRITICAL: l2capManager is NULL!", "E")
            return
        }

        val sdp = sdpDatabase
        if (sdp == null) {
            fileLog(TAG, "CRITICAL: sdpDatabase is NULL!", "E")
            return
        }

        fileLog(TAG, "Creating HidDeviceProfile instance...")

        // Create HID Device Profile with CourierStack managers
        hidDeviceProfile = HidDeviceProfile(l2cap, sdp)
        fileLog(TAG, "HidDeviceProfile created: ${hidDeviceProfile != null}")

        // Configure based on device mode using built-in configurations
        fileLog(TAG, "Building config for mode: $deviceMode")
        val config = when (deviceMode) {
            DeviceMode.KEYBOARD -> HidDeviceProfile.Config.keyboard()
            DeviceMode.MOUSE -> HidDeviceProfile.Config.mouse()
            DeviceMode.COMBO -> HidDeviceProfile.Config.keyboardMouse()
            DeviceMode.GAMEPAD -> HidDeviceProfile.Config.gamepad()
        }.deviceName(deviceName).build()

        fileLog(TAG, "Calling hidDeviceProfile.configure()...")
        hidDeviceProfile!!.configure(config)

        fileLog(TAG, "Setting HID profile listener...")
        hidDeviceProfile!!.setListener(hidProfileListener)

        fileLog(TAG, "Calling hidDeviceProfile.start()...")
        hidDeviceProfile!!.start()

        // Check SDP service count after HID profile starts
        val serviceCount = sdpDatabase?.serviceCount ?: 0
        fileLog(TAG, "HID profile started successfully")
        fileLog(TAG, "SDP service count after HID start: $serviceCount")
        log(TAG, "HID profile started: $deviceName ($deviceMode)", LogLevel.INFO)
    }

    /**
     * Make the device discoverable and connectable via HCI commands.
     * This is essential for hosts (like Windows) to find and connect to us.
     */
    private fun makeDeviceDiscoverable() {
        val hciManager = courierStack?.hciManager ?: return

        try {
            // 1. Set local name
            log(TAG, "Setting local name: $deviceName", LogLevel.INFO)
            val nameCmd = HciCommands.writeLocalName(deviceName)
            hciManager.sendCommandSync(nameCmd)

            // 2. Set Class of Device for HID peripheral
            val codBytes = when (deviceMode) {
                DeviceMode.KEYBOARD -> COD_KEYBOARD
                DeviceMode.MOUSE -> COD_MOUSE
                DeviceMode.COMBO -> COD_COMBO
                DeviceMode.GAMEPAD -> COD_GAMEPAD
            }
            log(TAG, "Setting Class of Device: ${codBytes.joinToString(" ") { "%02X".format(it) }}", LogLevel.INFO)
            val codCmd = HciCommands.buildCommand(0x0C24, codBytes) // Write_Class_of_Device
            hciManager.sendCommandSync(codCmd)

            // 3. Enable both inquiry scan and page scan (makes device discoverable AND connectable)
            log(TAG, "Enabling inquiry + page scan", LogLevel.INFO)
            val scanCmd = HciCommands.writeScanEnable(SCAN_INQUIRY_AND_PAGE)
            hciManager.sendCommandSync(scanCmd)

            // 4. Set inquiry mode to EIR (Extended Inquiry Response) for better discovery
            log(TAG, "Setting inquiry mode to EIR", LogLevel.INFO)
            val inquiryModeCmd = HciCommands.writeInquiryMode(2) // 2 = EIR
            hciManager.sendCommandSync(inquiryModeCmd)

            log(TAG, "Device is now discoverable as '$deviceName'", LogLevel.INFO)

        } catch (e: Exception) {
            log(TAG, "Failed to make device discoverable: ${e.message}", LogLevel.ERROR)
        }
    }

    /**
     * Make device connectable but NOT discoverable (PAGE SCAN only).
     * This is used after pairing so the host can reconnect without the device showing in scan.
     */
    private fun makeDeviceConnectable() {
        val hciManager = courierStack?.hciManager ?: return

        try {
            fileLog(TAG, "Enabling PAGE SCAN only (connectable, not discoverable)")
            val scanCmd = HciCommands.writeScanEnable(SCAN_PAGE_ONLY)
            hciManager.sendCommandSync(scanCmd)
            log(TAG, "Device is now connectable (waiting for host)", LogLevel.INFO)
        } catch (e: Exception) {
            fileLog(TAG, "Failed to enable page scan: ${e.message}", "E")
        }
    }

    /**
     * Disable all scanning (device not discoverable or connectable).
     */
    private fun disableScan() {
        val hciManager = courierStack?.hciManager ?: return

        try {
            val scanCmd = HciCommands.writeScanEnable(SCAN_DISABLED)
            hciManager.sendCommandSync(scanCmd)
            fileLog(TAG, "Scanning disabled")
        } catch (e: Exception) {
            fileLog(TAG, "Failed to disable scan: ${e.message}", "E")
        }
    }

    // ==================== Pairing / Advertising ====================

    /**
     * Start pairing mode - makes the device discoverable so hosts can find and pair with us.
     *
     * Call this after initialize() to make the device visible in Bluetooth settings
     * on Windows, macOS, Linux, etc.
     *
     * @return true if pairing mode started, false if not initialized
     */
    fun startPairing(): Boolean {
        fileLog(TAG, "========================================")
        fileLog(TAG, "startPairing() called")
        fileLog(TAG, "========================================")

        if (!initialized.get()) {
            fileLog(TAG, "ERROR: Not initialized!", "E")
            log(TAG, "Cannot start pairing - not initialized. Call initialize() first.", LogLevel.ERROR)
            return false
        }

        if (_connectionState.value == ConnectionState.CONNECTED) {
            fileLog(TAG, "Already connected - disconnect first", "W")
            log(TAG, "Already connected - disconnect first to start pairing", LogLevel.WARNING)
            return false
        }

        // Verify SDP server is running before making device discoverable
        fileLog(TAG, "Verifying SDP server state...")
        if (sdpManager == null) {
            fileLog(TAG, "CRITICAL: sdpManager is NULL!", "E")
            log(TAG, "WARNING: SDP server not available - reconnection will fail!", LogLevel.WARNING)
        } else {
            fileLog(TAG, "sdpManager is present: true")
            val serviceCount = sdpDatabase?.serviceCount ?: 0
            fileLog(TAG, "SDP service count: $serviceCount")
            if (serviceCount < 2) {
                fileLog(TAG, "WARNING: Expected at least 2 services (SDP + HID), got $serviceCount", "W")
            }
        }

        // Verify HID profile is running
        if (hidDeviceProfile == null) {
            fileLog(TAG, "CRITICAL: hidDeviceProfile is NULL!", "E")
            log(TAG, "WARNING: HID profile not initialized!", LogLevel.WARNING)
        } else {
            fileLog(TAG, "hidDeviceProfile is present: true")
        }

        fileLog(TAG, "Making device discoverable...")
        isPairingMode.set(true)
        makeDeviceDiscoverable()
        _connectionState.value = ConnectionState.SCANNING
        fileLog(TAG, "Pairing mode STARTED - device is now discoverable as '$deviceName'")
        log(TAG, "Pairing mode started - '$deviceName' is now discoverable!", LogLevel.INFO)
        return true
    }

    /**
     * Stop pairing mode - disable discovery.
     */
    fun stopPairing() {
        if (!initialized.get()) return

        isPairingMode.set(false)

        courierStack?.hciManager?.let { hci ->
            try {
                hci.sendCommand(HciCommands.writeScanEnable(SCAN_DISABLED))
                log(TAG, "Pairing stopped", LogLevel.INFO)
            } catch (e: Exception) {
                log(TAG, "Failed to stop pairing: ${e.message}", LogLevel.WARNING)
            }
        }

        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    /**
     * Alias for startPairing() - makes device visible for host connections.
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

    // ==================== Scanning (for finding other devices) ====================

    /**
     * Start scanning for nearby Bluetooth devices.
     * Also enables pairing mode so hosts can find us while we scan.
     *
     * Note: For HID device role, we typically wait for hosts to connect to us,
     * but this can be useful to see what's around.
     */
    fun startScanning() {
        if (!initialized.get()) {
            log(TAG, "Cannot scan - not initialized", LogLevel.WARNING)
            return
        }

        // Also make ourselves discoverable while scanning
        makeDeviceDiscoverable()

        discoveredDevices.clear()
        _discoveredHosts.value = emptyList()

        // Do active scanning to find nearby devices
        scannerManager?.startInquiry()
        scannerManager?.startLeScan()

        _connectionState.value = ConnectionState.SCANNING
        log(TAG, "Scanning for devices and advertising...", LogLevel.INFO)
    }

    fun stopScanning() {
        scannerManager?.stopAllScans()
        stopPairing()
        log(TAG, "Scanning stopped", LogLevel.INFO)
    }

    private fun updateDiscoveredHostsList() {
        _discoveredHosts.value = discoveredDevices.values.map { d ->
            HostDevice(address = d.address, name = d.name, rssi = d.rssi)
        }.sortedByDescending { it.rssi }
    }

    // ==================== Connection ====================

    fun connectToHost(host: HostDevice) {
        // For HID Device role, hosts connect to us - we just make ourselves connectable
        startAdvertising()
        log(TAG, "Ready for connection from ${host.displayName}", LogLevel.INFO)
    }

    /**
     * Prepare for reconnection to a bonded device.
     *
     * For HID, the HOST (Windows) connects to US, not vice versa.
     * This just makes the device connectable so Windows can reconnect.
     *
     * @param address Bluetooth address in "XX:XX:XX:XX:XX:XX" format
     * @return true if device is ready for connection
     */
    fun connectToBondedDevice(address: String): Boolean {
        fileLog(TAG, "========================================")
        fileLog(TAG, "connectToBondedDevice() called for: $address")
        fileLog(TAG, "========================================")

        if (!initialized.get()) {
            fileLog(TAG, "ERROR: Not initialized!", "E")
            log(TAG, "Cannot connect - not initialized", LogLevel.ERROR)
            return false
        }

        if (_connectionState.value == ConnectionState.CONNECTED) {
            fileLog(TAG, "Already connected", "W")
            log(TAG, "Already connected", LogLevel.WARNING)
            return true
        }

        // Verify the device is bonded
        val bonds = loadAllBonds()
        if (!bonds.containsKey(address)) {
            fileLog(TAG, "No saved bond for $address", "W")
            log(TAG, "No bond found for $address - please pair first", LogLevel.WARNING)
            return false
        }

        fileLog(TAG, "Found saved bond for $address")

        // Set flags so we handle any disconnects properly
        isPairingMode.set(true)
        lastPairedAddress = address

        _connectionState.value = ConnectionState.CONNECTING

        // Run the actual reconnection work on IO to avoid blocking the caller.
        // makeDeviceDiscoverable() and the HCI commands use sendCommandSync().
        scope.launch {
            try {
                // Step 1: Re-write the stored link key to the controller.
                // After an ACL drop the controller *might* have evicted it.
                restoreLinkKeyForDevice(address)

                // Step 2: Make ourselves connectable + discoverable (passive path).
                // Uses sendCommandSync — safe on IO scope.
                makeDeviceDiscoverable()

                // Step 3: Actively initiate an ACL connection to the host.
                // Most hosts (Windows especially) expect the HID device to
                // reconnect, not the other way round.
                initiateConnectionToHost(address)

                log(TAG, "Reconnecting to $address...", LogLevel.INFO)

            } catch (e: Exception) {
                fileLog(TAG, "Reconnection attempt error: ${e.message}", "E")
                log(TAG, "Reconnection error: ${e.message}", LogLevel.ERROR)
            }
        }

        return true
    }

    /**
     * Re-write a single device's stored link key to the BT controller.
     * Called before reconnection to ensure the controller can authenticate.
     */
    private fun restoreLinkKeyForDevice(address: String) {
        val hciManager = courierStack?.hciManager ?: return
        val bond = loadAllBonds()[address] ?: return

        try {
            val addr = parseAddress(address)
            // HCI Write_Stored_Link_Key (0x0C11)
            val params = ByteArray(1 + 6 + 16)
            params[0] = 1 // num_keys
            System.arraycopy(addr, 0, params, 1, 6)
            System.arraycopy(bond.linkKey, 0, params, 7, 16)

            val cmd = HciCommands.buildCommand(0x0C11, params)
            hciManager.sendCommandSync(cmd) // sync OK — we're on IO scope
            fileLog(TAG, "Restored link key for $address before reconnect")
        } catch (e: Exception) {
            fileLog(TAG, "Failed to restore link key: ${e.message}", "E")
        }
    }

    /**
     * Send HCI Create_Connection to the host to initiate the ACL link.
     *
     * Once the ACL is up, the host (Windows/macOS/Linux) will see our SDP
     * record advertising HID with VirtualCable + ReconnectInitiate and will
     * open the HID Control + Interrupt L2CAP channels to us. Our server
     * listeners accept them, and the connection is established.
     *
     * If the first attempt page-times-out, we retry up to [MAX_RECONNECT_ATTEMPTS].
     */
    private suspend fun initiateConnectionToHost(address: String) {
        val hciManager = courierStack?.hciManager ?: return

        for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
            if (_connectionState.value == ConnectionState.CONNECTED) {
                fileLog(TAG, "Already connected during reconnect — aborting retries")
                return
            }
            if (!isPairingMode.get()) {
                fileLog(TAG, "Pairing mode cleared — aborting reconnect retries")
                return
            }

            try {
                val addr = parseAddress(address)

                // HCI Create_Connection (OGF 0x01, OCF 0x0005 → opcode 0x0405)
                val params = ByteArray(13)
                System.arraycopy(addr, 0, params, 0, 6) // BD_ADDR (LE byte order)
                params[6] = 0x18.toByte()   // Packet_Type low  (DM1|DH1)
                params[7] = 0xCC.toByte()   // Packet_Type high (DM3|DH3|DM5|DH5)
                params[8] = 0x02            // Page_Scan_Rep_Mode R2 (longer timeout)
                params[9] = 0x00            // Reserved
                params[10] = 0x00           // Clock_Offset low
                params[11] = 0x00           // Clock_Offset high
                params[12] = 0x01           // Allow_Role_Switch

                val cmd = HciCommands.buildCommand(0x0405, params)
                hciManager.sendCommand(cmd) // async — result comes via event callback

                fileLog(TAG, "Create_Connection attempt $attempt/$MAX_RECONNECT_ATTEMPTS to $address")
                log(TAG, "Connection attempt $attempt to $address", LogLevel.INFO)

                // Poll for connection with shorter intervals instead of one long delay.
                // This catches the CONNECTED state faster and also detects if the
                // HID profile has connected hosts even if our callback was missed.
                for (tick in 0 until 10) { // 10 × 500ms = 5s per attempt
                    delay(500)
                    // Check 1: did the callback already set CONNECTED?
                    if (_connectionState.value == ConnectionState.CONNECTED) {
                        fileLog(TAG, "Connected (detected via state)")
                        return
                    }
                    // Check 2: does the HID profile have connected hosts?
                    // (safety net if the callback's state update was overwritten)
                    syncConnectionStateFromProfile()
                    if (_connectionState.value == ConnectionState.CONNECTED) {
                        fileLog(TAG, "Connected (detected via profile sync)")
                        return
                    }
                }

            } catch (e: Exception) {
                fileLog(TAG, "Create_Connection attempt $attempt failed: ${e.message}", "E")
            }
        }

        // Final sync after all retries
        syncConnectionStateFromProfile()

        if (_connectionState.value != ConnectionState.CONNECTED) {
            fileLog(TAG, "Active reconnect timed out — waiting in passive mode")
            log(TAG, "Waiting for host to connect...", LogLevel.INFO)
        }
    }

    /**
     * Check HidDeviceProfile for connected hosts and sync our state if needed.
     * This is a safety net — if the onHostConnected callback fired but its
     * state update was overwritten by a concurrent ACL disconnect event,
     * this method detects the actual connection and corrects the state.
     */
    private fun syncConnectionStateFromProfile() {
        val profile = hidDeviceProfile ?: return
        val connectedHosts = profile.connectedHosts

        if (connectedHosts.isNotEmpty() && _connectionState.value != ConnectionState.CONNECTED) {
            val host = connectedHosts[0]
            fileLog(TAG, "State sync: HID profile has connected host ${host.addressString} but state was ${_connectionState.value}")
            log(TAG, "Connected to ${host.addressString}", LogLevel.INFO)

            currentHostConnection = host
            _connectedHost.value = HostDevice(
                address = host.addressString,
                name = host.addressString,
                isConnected = true
            )
            _connectionState.value = ConnectionState.CONNECTED
            isPairingMode.set(false)
            isWaitingForHostConnection.set(false)
        }
    }

    /**
     * Connect to any previously bonded device.
     *
     * Attempts to connect to the first available bonded device.
     * Useful for auto-reconnection after app restart.
     *
     * @return true if connection attempt started, false if no bonds or error
     */
    fun connectToAnyBondedDevice(): Boolean {
        val bonds = getBondedDevices()
        if (bonds.isEmpty()) {
            log(TAG, "No bonded devices to connect to", LogLevel.INFO)
            return false
        }

        // Try the first bonded device
        val address = bonds.first()
        log(TAG, "Attempting to connect to bonded device: $address", LogLevel.INFO)
        return connectToBondedDevice(address)
    }

    fun disconnect() {
        // Set flag BEFORE sending HCI disconnect so the onHostDisconnected
        // callback knows not to auto-reconnect.
        userDisconnecting.set(true)
        isPairingMode.set(false) // also clears reconnect retry loop
        currentHostConnection?.let { hidDeviceProfile?.disconnectHost(it) }
        handleDisconnection()
        userDisconnecting.set(false)
    }

    private fun handleDisconnection() {
        currentHostConnection = null
        _connectedHost.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
        clearInputState()
    }

    // ==================== Report ID Helpers ====================
    // COMBO mode uses Report ID 1 for keyboard, Report ID 2 for mouse, Report ID 3 for gamepad.
    // Non-combo modes use no report IDs (0).

    private val keyboardReportId: Int
        get() = if (deviceMode == DeviceMode.COMBO) 1 else 0

    private val mouseReportId: Int
        get() = if (deviceMode == DeviceMode.COMBO) 2 else 0

    private val gamepadReportId: Int
        get() = 3 // Gamepad always uses report ID 3

    private fun canSendKeyboard(): Boolean =
        deviceMode == DeviceMode.KEYBOARD || deviceMode == DeviceMode.COMBO

    private fun canSendMouse(): Boolean =
        deviceMode == DeviceMode.MOUSE || deviceMode == DeviceMode.COMBO

    private fun canSendGamepad(): Boolean =
        deviceMode == DeviceMode.GAMEPAD

    // ==================== Keyboard Input ====================

    fun pressKey(keyCode: Int, modifiers: ModifierState = currentModifiers) {
        if (!isConnected() || !canSendKeyboard()) return
        currentModifiers = modifiers
        if (pressedKeys.size < MAX_KEYS) pressedKeys.add(keyCode)
        sendKeyboardReport()
    }

    fun releaseKey(keyCode: Int) {
        if (!isConnected() || !canSendKeyboard()) return
        pressedKeys.remove(keyCode)
        sendKeyboardReport()
    }

    fun typeKey(keyCode: Int, modifiers: ModifierState = ModifierState()) {
        if (!isConnected() || !canSendKeyboard()) return
        scope.launch {
            currentModifiers = modifiers
            pressedKeys.add(keyCode)
            sendKeyboardReport()
            delay(50)
            pressedKeys.remove(keyCode)
            currentModifiers = ModifierState()
            sendKeyboardReport()
        }
    }

    /**
     * Atomically send a key press with specific modifiers, then release.
     * Used for shortcuts like Ctrl+C where modifier and key must be in the same report.
     */
    fun typeKeyWithModifiers(keyCode: Int, modifiers: ModifierState) {
        if (!isConnected() || !canSendKeyboard()) return
        scope.launch {
            // Send press report with modifiers + key
            val savedModifiers = currentModifiers
            val savedKeys = pressedKeys.toSet()
            currentModifiers = modifiers
            pressedKeys.clear()
            pressedKeys.add(keyCode)
            sendKeyboardReport()
            delay(50)
            // Send release report
            pressedKeys.clear()
            currentModifiers = ModifierState()
            sendKeyboardReport()
            // Restore previous state
            currentModifiers = savedModifiers
            pressedKeys.addAll(savedKeys)
            if (savedKeys.isNotEmpty() || savedModifiers.isAnyActive()) {
                sendKeyboardReport()
            }
        }
    }

    fun typeText(text: String, delayMs: Long = 30) {
        if (!isConnected() || !canSendKeyboard()) return
        scope.launch {
            for (char in text) {
                HidKeyCodes.charToKeyCode(char)?.let { (keyCode, modByte) ->
                    val mods = ModifierState(leftShift = (modByte and HidKeyCodes.MOD_LEFT_SHIFT) != 0)
                    typeKey(keyCode, mods)
                    delay(delayMs)
                }
            }
        }
    }

    fun releaseAllKeys() {
        pressedKeys.clear()
        currentModifiers = ModifierState()
        if (isConnected() && canSendKeyboard()) sendKeyboardReport()
    }

    fun setModifiers(modifiers: ModifierState) {
        currentModifiers = modifiers
        if (isConnected() && canSendKeyboard()) sendKeyboardReport()
    }

    private fun sendKeyboardReport() {
        val host = currentHostConnection ?: return
        val profile = hidDeviceProfile ?: return

        val data = ByteArray(8)
        data[0] = currentModifiers.toByte().toByte()
        data[1] = 0 // Reserved
        pressedKeys.take(6).forEachIndexed { i, key -> data[2 + i] = key.toByte() }

        val reportId = keyboardReportId
        val report = if (reportId != 0) {
            HidReport.input(reportId, data)
        } else {
            HidReport.input(data)
        }
        profile.sendInputReport(host, report)
    }

    private fun buildCurrentKeyboardReport(): ByteArray {
        val data = ByteArray(8)
        data[0] = currentModifiers.toByte().toByte()
        pressedKeys.take(6).forEachIndexed { i, key -> data[2 + i] = key.toByte() }
        return data
    }

    // ==================== Mouse Input ====================

    fun moveMouse(dx: Int, dy: Int) {
        if (!isConnected() || !canSendMouse()) return
        sendMouseReport(currentMouseButtons.toByte(), dx, dy, 0)
    }

    fun scroll(amount: Int) {
        if (!isConnected() || !canSendMouse()) return
        sendMouseReport(currentMouseButtons.toByte(), 0, 0, amount)
    }

    fun pressMouseButton(button: Int) {
        if (!isConnected() || !canSendMouse()) return
        currentMouseButtons = when (button) {
            MouseButtons.LEFT -> currentMouseButtons.copy(left = true)
            MouseButtons.RIGHT -> currentMouseButtons.copy(right = true)
            MouseButtons.MIDDLE -> currentMouseButtons.copy(middle = true)
            else -> currentMouseButtons
        }
        sendMouseReport(currentMouseButtons.toByte(), 0, 0, 0)
    }

    fun releaseMouseButton(button: Int) {
        if (!isConnected() || !canSendMouse()) return
        currentMouseButtons = when (button) {
            MouseButtons.LEFT -> currentMouseButtons.copy(left = false)
            MouseButtons.RIGHT -> currentMouseButtons.copy(right = false)
            MouseButtons.MIDDLE -> currentMouseButtons.copy(middle = false)
            else -> currentMouseButtons
        }
        sendMouseReport(currentMouseButtons.toByte(), 0, 0, 0)
    }

    fun clickMouseButton(button: Int) {
        if (!isConnected() || !canSendMouse()) return
        pressMouseButton(button)
        scope.launch {
            delay(50)
            releaseMouseButton(button)
        }
    }

    private fun sendMouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        val host = currentHostConnection ?: return
        val profile = hidDeviceProfile ?: return

        val data = ByteArray(4)
        data[0] = buttons.toByte()
        data[1] = dx.coerceIn(-127, 127).toByte()
        data[2] = dy.coerceIn(-127, 127).toByte()
        data[3] = wheel.coerceIn(-127, 127).toByte()

        val reportId = mouseReportId
        val report = if (reportId != 0) {
            HidReport.input(reportId, data)
        } else {
            HidReport.input(data)
        }
        profile.sendInputReport(host, report)
    }

    // ==================== Gamepad Input ====================

    /**
     * Send a gamepad report (Report ID 3).
     *
     * @param buttons 16-bit button bitmask
     * @param leftX left stick X (0-255, center 128)
     * @param leftY left stick Y (0-255, center 128)
     * @param rightX right stick X (0-255, center 128)
     * @param rightY right stick Y (0-255, center 128)
     * @param leftTrigger left trigger (0-255)
     * @param rightTrigger right trigger (0-255)
     * @param dpad hat switch (0-7, or 0x0F for center/neutral)
     */
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
        if (!isConnected() || !canSendGamepad()) return
        val host = currentHostConnection ?: return
        val profile = hidDeviceProfile ?: return

        // 9 bytes: 2 buttons + 2 sticks (4 bytes) + 2 triggers + 1 dpad (4 bits + 4 pad)
        val data = ByteArray(9)
        data[0] = (buttons and 0xFF).toByte()           // Buttons low byte
        data[1] = ((buttons shr 8) and 0xFF).toByte()   // Buttons high byte
        data[2] = leftX.coerceIn(0, 255).toByte()
        data[3] = leftY.coerceIn(0, 255).toByte()
        data[4] = rightX.coerceIn(0, 255).toByte()
        data[5] = rightY.coerceIn(0, 255).toByte()
        data[6] = leftTrigger.coerceIn(0, 255).toByte()
        data[7] = rightTrigger.coerceIn(0, 255).toByte()
        // D-pad in lower 4 bits, upper 4 bits padding (0)
        data[8] = (dpad.coerceIn(0, 0x0F) and 0x0F).toByte()

        val report = HidReport.input(gamepadReportId, data)
        profile.sendInputReport(host, report)
    }

    // ==================== Utility ====================

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED &&
            currentHostConnection?.isConnected == true

    fun isInitialized(): Boolean = initialized.get()

    private fun clearInputState() {
        pressedKeys.clear()
        currentModifiers = ModifierState()
        currentMouseButtons = MouseButtonState()
    }

    fun updateConfiguration(settings: AppSettings) {
        val modeChanged = deviceMode != settings.deviceMode
        val nameChanged = deviceName != settings.deviceName

        deviceMode = settings.deviceMode
        deviceName = settings.deviceName

        // Only restart HID profile if mode or name changed, and only when disconnected
        if (initialized.get() && _connectionState.value == ConnectionState.DISCONNECTED
            && (modeChanged || nameChanged)) {
            hidDeviceProfile?.stop()
            initializeHidProfile()
            // Only update HCI name/CoD — do NOT enable scanning here.
            // User must explicitly call startPairing() to advertise.
            courierStack?.hciManager?.let { hci ->
                try {
                    hci.sendCommandSync(HciCommands.writeLocalName(deviceName))
                    val codBytes = when (deviceMode) {
                        DeviceMode.KEYBOARD -> COD_KEYBOARD
                        DeviceMode.MOUSE -> COD_MOUSE
                        DeviceMode.COMBO -> COD_COMBO
                        DeviceMode.GAMEPAD -> COD_GAMEPAD
                    }
                    hci.sendCommandSync(HciCommands.buildCommand(0x0C24, codBytes))
                } catch (e: Exception) {
                    fileLog(TAG, "Failed to update HCI config: ${e.message}", "E")
                }
            }
        }
    }

    private fun log(tag: String, message: String, level: LogLevel) {
        val entry = com.courierstack.hidremote.data.LogEntry(tag = tag, message = message, level = level)
        _logs.value = (_logs.value + entry).takeLast(500)
        when (level) {
            LogLevel.ERROR -> Log.e(tag, message)
            LogLevel.WARNING -> Log.w(tag, message)
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
        }
    }

    private fun formatAddress(addr: ByteArray): String {
        return String.format(
            "%02X:%02X:%02X:%02X:%02X:%02X",
            addr[5].toInt() and 0xFF, addr[4].toInt() and 0xFF,
            addr[3].toInt() and 0xFF, addr[2].toInt() and 0xFF,
            addr[1].toInt() and 0xFF, addr[0].toInt() and 0xFF
        )
    }

    // ==================== Bonding Persistence ====================

    /**
     * Saved bonding information for reconnection
     */
    private data class SavedBond(
        val address: String,
        val linkKey: ByteArray,
        val linkKeyType: Int
    )

    /**
     * Save bonding info to SharedPreferences for persistence across restarts.
     */
    private fun saveBondingInfo(address: ByteArray, info: BondingInfo) {
        try {
            val addrStr = formatAddress(address)
            val linkKey = info.linkKey

            if (linkKey == null || linkKey.size != 16) {
                log(TAG, "No valid link key in BondingInfo for $addrStr", LogLevel.WARNING)
                return
            }

            val linkKeyType = info.linkKeyType

            log(TAG, "Saving bond for $addrStr (key type: $linkKeyType, key len: ${linkKey.size})", LogLevel.INFO)

            // Load existing bonds
            val bonds = loadAllBonds().toMutableMap()

            // Add/update this bond
            bonds[addrStr] = SavedBond(addrStr, linkKey, linkKeyType)

            // Save to preferences as JSON
            val jsonArray = JSONArray()
            for ((addr, bond) in bonds) {
                val obj = JSONObject().apply {
                    put("address", addr)
                    put("linkKey", Base64.encodeToString(bond.linkKey, Base64.NO_WRAP))
                    put("linkKeyType", bond.linkKeyType)
                }
                jsonArray.put(obj)
            }

            prefs.edit().putString(KEY_BONDED_DEVICES, jsonArray.toString()).apply()
            log(TAG, "Bond saved successfully for $addrStr", LogLevel.INFO)

        } catch (e: Exception) {
            log(TAG, "Failed to save bonding info: ${e.message}", LogLevel.ERROR)
        }
    }

    /**
     * Load all saved bonds from SharedPreferences.
     */
    private fun loadAllBonds(): Map<String, SavedBond> {
        val bonds = mutableMapOf<String, SavedBond>()
        try {
            val json = prefs.getString(KEY_BONDED_DEVICES, null) ?: return bonds
            val jsonArray = JSONArray(json)

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val addr = obj.getString("address")
                val linkKey = Base64.decode(obj.getString("linkKey"), Base64.NO_WRAP)
                val linkKeyType = obj.getInt("linkKeyType")
                bonds[addr] = SavedBond(addr, linkKey, linkKeyType)
            }
        } catch (e: Exception) {
            log(TAG, "Failed to load bonds: ${e.message}", LogLevel.WARNING)
        }
        return bonds
    }

    /**
     * Parse address string to byte array (little-endian for HCI).
     */
    private fun parseAddress(addrStr: String): ByteArray {
        val parts = addrStr.split(":")
        require(parts.size == 6) { "Invalid address format" }
        return byteArrayOf(
            parts[5].toInt(16).toByte(),
            parts[4].toInt(16).toByte(),
            parts[3].toInt(16).toByte(),
            parts[2].toInt(16).toByte(),
            parts[1].toInt(16).toByte(),
            parts[0].toInt(16).toByte()
        )
    }

    /**
     * Restore stored link keys to the Bluetooth controller.
     * This should be called during initialization to enable reconnection.
     */
    private fun restoreStoredLinkKeys() {
        val hciManager = courierStack?.hciManager ?: return
        val bonds = loadAllBonds()

        if (bonds.isEmpty()) {
            log(TAG, "No stored bonds to restore", LogLevel.DEBUG)
            return
        }

        log(TAG, "Restoring ${bonds.size} stored link key(s)", LogLevel.INFO)

        for ((addrStr, bond) in bonds) {
            try {
                val addr = parseAddress(addrStr)

                // Build HCI Write_Stored_Link_Key command (0x0C11)
                // Format: Num_Keys_To_Write (1) + [BD_ADDR (6) + Link_Key (16)] per key
                val params = ByteArray(1 + 6 + 16)
                params[0] = 1  // num_keys = 1
                System.arraycopy(addr, 0, params, 1, 6)
                System.arraycopy(bond.linkKey, 0, params, 7, 16)

                val cmd = HciCommands.buildCommand(0x0C11, params)
                hciManager.sendCommand(cmd)

                log(TAG, "Restored link key for $addrStr", LogLevel.INFO)
            } catch (e: Exception) {
                log(TAG, "Failed to restore link key for $addrStr: ${e.message}", LogLevel.WARNING)
            }
        }
    }

    /**
     * Clear all stored bonds (useful for debugging or unpairing all devices).
     */
    fun clearAllBonds() {
        prefs.edit().remove(KEY_BONDED_DEVICES).apply()
        log(TAG, "All stored bonds cleared", LogLevel.INFO)
    }

    /**
     * Remove bond for a specific device.
     */
    fun removeBond(address: String) {
        val bonds = loadAllBonds().toMutableMap()
        if (bonds.remove(address) != null) {
            val jsonArray = JSONArray()
            for ((addr, bond) in bonds) {
                val obj = JSONObject().apply {
                    put("address", addr)
                    put("linkKey", Base64.encodeToString(bond.linkKey, Base64.NO_WRAP))
                    put("linkKeyType", bond.linkKeyType)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString(KEY_BONDED_DEVICES, jsonArray.toString()).apply()
            log(TAG, "Removed bond for $address", LogLevel.INFO)
        }
    }

    /**
     * Get list of bonded device addresses.
     */
    fun getBondedDevices(): List<String> {
        return loadAllBonds().keys.toList()
    }

    fun shutdown() {
        fileLog(TAG, "========================================")
        fileLog(TAG, "SHUTDOWN STARTED")
        fileLog(TAG, "========================================")
        log(TAG, "Shutting down", LogLevel.INFO)

        // Stop advertising before shutdown
        stopPairing()

        disconnect()
        hidDeviceProfile?.close()
        hidDeviceProfile = null
        sdpManager?.shutdown()
        sdpManager = null
        sdpDatabase = null
        smpManager?.close()
        smpManager = null
        pairingManager?.close()
        pairingManager = null
        scannerManager?.stopAllScans()
        scannerManager = null

        // Remove raw event listener before closing L2CAP
        l2capManager?.removeRawEventListener(rawEventListener)
        l2capManager?.close()
        l2capManager = null

        courierStack?.shutdown()
        courierStack?.removeStackListener(stackListener)
        courierStack = null

        scope.cancel()
        // Recreate scope so initialize() can launch coroutines again
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        initialized.set(false)

        fileLog(TAG, "Shutdown complete")
        closeFileLogger()
    }
}