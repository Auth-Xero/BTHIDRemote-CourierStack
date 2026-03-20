package com.courierstack.hidremote.service

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import com.courierstack.hidremote.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HID Device Manager implementation using Android's native BluetoothHidDevice API.
 *
 * This is the default backend. It works with Android's standard Bluetooth stack
 * and does NOT require root or killing the Android BT service.
 *
 * Requires API 28+ (Android 9 Pie).
 *
 * Flow:
 * 1. [initialize] — obtains BluetoothHidDevice proxy via BluetoothProfile
 * 2. [startPairing] — registers the HID SDP record and makes device connectable
 * 3. Host pairs via Android system Bluetooth — no custom pairing logic needed
 * 4. Reports are sent via [BluetoothHidDevice.sendReport]
 */
@SuppressLint("MissingPermission")
class AndroidHidDeviceManager(private val context: Context) : IHidDeviceManager {

    companion object {
        private const val TAG = "AndroidHidMgr"
        private const val MAX_KEYS = 6
    }

    // ==================== State Flows ====================

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedHost = MutableStateFlow<HostDevice?>(null)
    override val connectedHost: StateFlow<HostDevice?> = _connectedHost.asStateFlow()

    private val _discoveredHosts = MutableStateFlow<List<HostDevice>>(emptyList())
    override val discoveredHosts: StateFlow<List<HostDevice>> = _discoveredHosts.asStateFlow()

    private val _ledState = MutableStateFlow(KeyboardLedState())
    override val ledState: StateFlow<KeyboardLedState> = _ledState.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    override val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _errorChannel = Channel<String>(Channel.BUFFERED)
    override val errors: Flow<String> = _errorChannel.receiveAsFlow()

    // ==================== Bluetooth Objects ====================

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedBtDevice: BluetoothDevice? = null

    // ==================== Input State ====================

    private val pressedKeys = mutableSetOf<Int>()
    private var currentModifiers = ModifierState()
    private var currentMouseButtons = MouseButtonState()

    // ==================== Configuration ====================

    private var deviceMode = DeviceMode.COMBO
    private var deviceName = "BT HID Remote"

    // ==================== Runtime ====================

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialized = AtomicBoolean(false)
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    // ==================== HID Descriptors ====================

    /**
     * Combined keyboard + mouse HID report descriptor.
     *
     * Report ID 1: Keyboard (8 bytes — modifier, reserved, 6 keys)
     * Report ID 2: Mouse (4 bytes — buttons, dx, dy, wheel)
     */
    private val comboDescriptor: ByteArray
        get() {
            val kb = byteArrayOf(
                0x05.toByte(), 0x01.toByte(),       // Usage Page (Generic Desktop)
                0x09.toByte(), 0x06.toByte(),       // Usage (Keyboard)
                0xA1.toByte(), 0x01.toByte(),       // Collection (Application)
                0x85.toByte(), 0x01.toByte(),       //   Report ID (1)
                // Modifier keys (8 bits)
                0x05.toByte(), 0x07.toByte(),       //   Usage Page (Keyboard/Keypad)
                0x19.toByte(), 0xE0.toByte(),       //   Usage Minimum (Left Control)
                0x29.toByte(), 0xE7.toByte(),       //   Usage Maximum (Right GUI)
                0x15.toByte(), 0x00.toByte(),       //   Logical Minimum (0)
                0x25.toByte(), 0x01.toByte(),       //   Logical Maximum (1)
                0x75.toByte(), 0x01.toByte(),       //   Report Size (1)
                0x95.toByte(), 0x08.toByte(),       //   Report Count (8)
                0x81.toByte(), 0x02.toByte(),       //   Input (Data, Variable, Absolute)
                // Reserved byte
                0x75.toByte(), 0x08.toByte(),       //   Report Size (8)
                0x95.toByte(), 0x01.toByte(),       //   Report Count (1)
                0x81.toByte(), 0x01.toByte(),       //   Input (Constant)
                // LED output report
                0x05.toByte(), 0x08.toByte(),       //   Usage Page (LEDs)
                0x19.toByte(), 0x01.toByte(),       //   Usage Minimum (Num Lock)
                0x29.toByte(), 0x05.toByte(),       //   Usage Maximum (Kana)
                0x75.toByte(), 0x01.toByte(),       //   Report Size (1)
                0x95.toByte(), 0x05.toByte(),       //   Report Count (5)
                0x91.toByte(), 0x02.toByte(),       //   Output (Data, Variable, Absolute)
                // LED padding
                0x75.toByte(), 0x03.toByte(),       //   Report Size (3)
                0x95.toByte(), 0x01.toByte(),       //   Report Count (1)
                0x91.toByte(), 0x01.toByte(),       //   Output (Constant)
                // Key array (6 keys)
                0x05.toByte(), 0x07.toByte(),       //   Usage Page (Keyboard/Keypad)
                0x19.toByte(), 0x00.toByte(),       //   Usage Minimum (0)
                0x29.toByte(), 0xFF.toByte(),       //   Usage Maximum (255)
                0x15.toByte(), 0x00.toByte(),       //   Logical Minimum (0)
                0x26.toByte(), 0xFF.toByte(), 0x00.toByte(), // Logical Maximum (255)
                0x75.toByte(), 0x08.toByte(),       //   Report Size (8)
                0x95.toByte(), 0x06.toByte(),       //   Report Count (6)
                0x81.toByte(), 0x00.toByte(),       //   Input (Data, Array)
                0xC0.toByte()                       // End Collection
            )

            val mouse = byteArrayOf(
                0x05.toByte(), 0x01.toByte(),       // Usage Page (Generic Desktop)
                0x09.toByte(), 0x02.toByte(),       // Usage (Mouse)
                0xA1.toByte(), 0x01.toByte(),       // Collection (Application)
                0x09.toByte(), 0x01.toByte(),       //   Usage (Pointer)
                0xA1.toByte(), 0x00.toByte(),       //   Collection (Physical)
                0x85.toByte(), 0x02.toByte(),       //     Report ID (2)
                // Buttons (3)
                0x05.toByte(), 0x09.toByte(),       //     Usage Page (Buttons)
                0x19.toByte(), 0x01.toByte(),       //     Usage Minimum (1)
                0x29.toByte(), 0x03.toByte(),       //     Usage Maximum (3)
                0x15.toByte(), 0x00.toByte(),       //     Logical Minimum (0)
                0x25.toByte(), 0x01.toByte(),       //     Logical Maximum (1)
                0x75.toByte(), 0x01.toByte(),       //     Report Size (1)
                0x95.toByte(), 0x03.toByte(),       //     Report Count (3)
                0x81.toByte(), 0x02.toByte(),       //     Input (Data, Variable, Absolute)
                // Button padding
                0x75.toByte(), 0x05.toByte(),       //     Report Size (5)
                0x95.toByte(), 0x01.toByte(),       //     Report Count (1)
                0x81.toByte(), 0x01.toByte(),       //     Input (Constant)
                // X, Y movement
                0x05.toByte(), 0x01.toByte(),       //     Usage Page (Generic Desktop)
                0x09.toByte(), 0x30.toByte(),       //     Usage (X)
                0x09.toByte(), 0x31.toByte(),       //     Usage (Y)
                0x15.toByte(), 0x81.toByte(),       //     Logical Minimum (-127)
                0x25.toByte(), 0x7F.toByte(),       //     Logical Maximum (127)
                0x75.toByte(), 0x08.toByte(),       //     Report Size (8)
                0x95.toByte(), 0x02.toByte(),       //     Report Count (2)
                0x81.toByte(), 0x06.toByte(),       //     Input (Data, Variable, Relative)
                // Wheel
                0x09.toByte(), 0x38.toByte(),       //     Usage (Wheel)
                0x15.toByte(), 0x81.toByte(),       //     Logical Minimum (-127)
                0x25.toByte(), 0x7F.toByte(),       //     Logical Maximum (127)
                0x75.toByte(), 0x08.toByte(),       //     Report Size (8)
                0x95.toByte(), 0x01.toByte(),       //     Report Count (1)
                0x81.toByte(), 0x06.toByte(),       //     Input (Data, Variable, Relative)
                0xC0.toByte(),                      //   End Collection (Physical)
                0xC0.toByte()                       // End Collection (Application)
            )

            return kb + mouse
        }

    private val keyboardOnlyDescriptor: ByteArray
        get() {
            return byteArrayOf(
                0x05.toByte(), 0x01.toByte(),
                0x09.toByte(), 0x06.toByte(),
                0xA1.toByte(), 0x01.toByte(),
                // Modifiers
                0x05.toByte(), 0x07.toByte(),
                0x19.toByte(), 0xE0.toByte(),
                0x29.toByte(), 0xE7.toByte(),
                0x15.toByte(), 0x00.toByte(),
                0x25.toByte(), 0x01.toByte(),
                0x75.toByte(), 0x01.toByte(),
                0x95.toByte(), 0x08.toByte(),
                0x81.toByte(), 0x02.toByte(),
                // Reserved
                0x75.toByte(), 0x08.toByte(),
                0x95.toByte(), 0x01.toByte(),
                0x81.toByte(), 0x01.toByte(),
                // LEDs
                0x05.toByte(), 0x08.toByte(),
                0x19.toByte(), 0x01.toByte(),
                0x29.toByte(), 0x05.toByte(),
                0x75.toByte(), 0x01.toByte(),
                0x95.toByte(), 0x05.toByte(),
                0x91.toByte(), 0x02.toByte(),
                0x75.toByte(), 0x03.toByte(),
                0x95.toByte(), 0x01.toByte(),
                0x91.toByte(), 0x01.toByte(),
                // Keys
                0x05.toByte(), 0x07.toByte(),
                0x19.toByte(), 0x00.toByte(),
                0x29.toByte(), 0xFF.toByte(),
                0x15.toByte(), 0x00.toByte(),
                0x26.toByte(), 0xFF.toByte(), 0x00.toByte(),
                0x75.toByte(), 0x08.toByte(),
                0x95.toByte(), 0x06.toByte(),
                0x81.toByte(), 0x00.toByte(),
                0xC0.toByte()
            )
        }

    private val mouseOnlyDescriptor: ByteArray
        get() {
            return byteArrayOf(
                0x05.toByte(), 0x01.toByte(),
                0x09.toByte(), 0x02.toByte(),
                0xA1.toByte(), 0x01.toByte(),
                0x09.toByte(), 0x01.toByte(),
                0xA1.toByte(), 0x00.toByte(),
                // Buttons
                0x05.toByte(), 0x09.toByte(),
                0x19.toByte(), 0x01.toByte(),
                0x29.toByte(), 0x03.toByte(),
                0x15.toByte(), 0x00.toByte(),
                0x25.toByte(), 0x01.toByte(),
                0x75.toByte(), 0x01.toByte(),
                0x95.toByte(), 0x03.toByte(),
                0x81.toByte(), 0x02.toByte(),
                0x75.toByte(), 0x05.toByte(),
                0x95.toByte(), 0x01.toByte(),
                0x81.toByte(), 0x01.toByte(),
                // X, Y
                0x05.toByte(), 0x01.toByte(),
                0x09.toByte(), 0x30.toByte(),
                0x09.toByte(), 0x31.toByte(),
                0x15.toByte(), 0x81.toByte(),
                0x25.toByte(), 0x7F.toByte(),
                0x75.toByte(), 0x08.toByte(),
                0x95.toByte(), 0x02.toByte(),
                0x81.toByte(), 0x06.toByte(),
                // Wheel
                0x09.toByte(), 0x38.toByte(),
                0x15.toByte(), 0x81.toByte(),
                0x25.toByte(), 0x7F.toByte(),
                0x75.toByte(), 0x08.toByte(),
                0x95.toByte(), 0x01.toByte(),
                0x81.toByte(), 0x06.toByte(),
                0xC0.toByte(),
                0xC0.toByte()
            )
        }

    private val gamepadDescriptor: ByteArray
        get() {
            return byteArrayOf(
                0x05.toByte(), 0x01.toByte(),       // Usage Page (Generic Desktop)
                0x09.toByte(), 0x05.toByte(),       // Usage (Gamepad)
                0xA1.toByte(), 0x01.toByte(),       // Collection (Application)
                0x85.toByte(), 0x03.toByte(),       //   Report ID (3)
                // 16 buttons
                0x05.toByte(), 0x09.toByte(),       //   Usage Page (Buttons)
                0x19.toByte(), 0x01.toByte(),       //   Usage Minimum (1)
                0x29.toByte(), 0x10.toByte(),       //   Usage Maximum (16)
                0x15.toByte(), 0x00.toByte(),       //   Logical Minimum (0)
                0x25.toByte(), 0x01.toByte(),       //   Logical Maximum (1)
                0x75.toByte(), 0x01.toByte(),       //   Report Size (1)
                0x95.toByte(), 0x10.toByte(),       //   Report Count (16)
                0x81.toByte(), 0x02.toByte(),       //   Input (Data, Variable, Absolute)
                // 4 axes (LX, LY, RX, RY) 0-255
                0x05.toByte(), 0x01.toByte(),       //   Usage Page (Generic Desktop)
                0x09.toByte(), 0x30.toByte(),       //   Usage (X)
                0x09.toByte(), 0x31.toByte(),       //   Usage (Y)
                0x09.toByte(), 0x32.toByte(),       //   Usage (Z)
                0x09.toByte(), 0x35.toByte(),       //   Usage (Rz)
                0x15.toByte(), 0x00.toByte(),       //   Logical Minimum (0)
                0x26.toByte(), 0xFF.toByte(), 0x00.toByte(), // Logical Maximum (255)
                0x75.toByte(), 0x08.toByte(),       //   Report Size (8)
                0x95.toByte(), 0x04.toByte(),       //   Report Count (4)
                0x81.toByte(), 0x02.toByte(),       //   Input (Data, Variable, Absolute)
                // 2 triggers 0-255
                0x05.toByte(), 0x02.toByte(),       //   Usage Page (Simulation)
                0x09.toByte(), 0xC5.toByte(),       //   Usage (Brake)
                0x09.toByte(), 0xC4.toByte(),       //   Usage (Accelerator)
                0x15.toByte(), 0x00.toByte(),
                0x26.toByte(), 0xFF.toByte(), 0x00.toByte(),
                0x75.toByte(), 0x08.toByte(),
                0x95.toByte(), 0x02.toByte(),
                0x81.toByte(), 0x02.toByte(),
                // D-pad (hat switch) 4 bits + 4 padding
                0x05.toByte(), 0x01.toByte(),       //   Usage Page (Generic Desktop)
                0x09.toByte(), 0x39.toByte(),       //   Usage (Hat Switch)
                0x15.toByte(), 0x00.toByte(),
                0x25.toByte(), 0x07.toByte(),
                0x35.toByte(), 0x00.toByte(),       //   Physical Minimum (0)
                0x46.toByte(), 0x3B.toByte(), 0x01.toByte(), // Physical Maximum (315)
                0x65.toByte(), 0x14.toByte(),       //   Unit (degrees)
                0x75.toByte(), 0x04.toByte(),       //   Report Size (4)
                0x95.toByte(), 0x01.toByte(),       //   Report Count (1)
                0x81.toByte(), 0x42.toByte(),       //   Input (Data, Variable, Absolute, Null State)
                // Padding
                0x75.toByte(), 0x04.toByte(),
                0x95.toByte(), 0x01.toByte(),
                0x81.toByte(), 0x01.toByte(),       //   Input (Constant)
                0xC0.toByte()                       // End Collection
            )
        }

    // ==================== BluetoothHidDevice Callback ====================

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            log(TAG, "App status changed: registered=$registered, device=${pluggedDevice?.address}", LogLevel.INFO)
            if (registered) {
                log(TAG, "HID SDP record registered with Android stack", LogLevel.INFO)
            } else {
                log(TAG, "HID SDP record unregistered", LogLevel.WARNING)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            val address = device?.address ?: "unknown"
            val stateName = when (state) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                else -> "UNKNOWN($state)"
            }
            log(TAG, "Connection state changed: $address -> $stateName", LogLevel.INFO)

            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedBtDevice = device
                    _connectedHost.value = HostDevice(
                        address = address,
                        name = device?.name,
                        isConnected = true
                    )
                    _connectionState.value = ConnectionState.CONNECTED
                    log(TAG, "Connected to ${device?.name ?: address}", LogLevel.INFO)
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    _connectionState.value = ConnectionState.CONNECTING
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedBtDevice?.address == address) {
                        connectedBtDevice = null
                        _connectedHost.value = null
                        _connectionState.value = ConnectionState.DISCONNECTED
                        clearInputState()
                        log(TAG, "Disconnected from $address", LogLevel.INFO)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    // Transient, just log
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            log(TAG, "onGetReport: type=$type, id=$id", LogLevel.DEBUG)
            val hid = hidDevice ?: return
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT.toByte()) {
                when (id.toInt()) {
                    keyboardReportId -> {
                        hid.replyReport(device, type, id, buildCurrentKeyboardReport())
                    }
                    mouseReportId -> {
                        hid.replyReport(device, type, id, ByteArray(4))
                    }
                    else -> {
                        hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                    }
                }
            }
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            log(TAG, "onSetReport: type=$type, id=$id, dataLen=${data?.size}", LogLevel.DEBUG)
            // Accept silently
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS.toByte())
        }

        override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {
            log(TAG, "onSetProtocol: $protocol", LogLevel.DEBUG)
        }

        override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {
            // Output reports (e.g. keyboard LEDs) arrive here
            if (data != null && data.isNotEmpty()) {
                _ledState.value = KeyboardLedState.fromByte(data[0].toInt() and 0xFF)
            }
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            log(TAG, "Virtual cable unplug from ${device?.address}", LogLevel.INFO)
            disconnect()
        }
    }

    // ==================== Profile Proxy Listener ====================

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as? BluetoothHidDevice
                log(TAG, "BluetoothHidDevice proxy obtained", LogLevel.INFO)
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                log(TAG, "BluetoothHidDevice proxy lost", LogLevel.WARNING)
            }
        }
    }

    // ==================== Initialization ====================

    override suspend fun initialize(settings: AppSettings): Boolean = withContext(Dispatchers.IO) {
        if (initialized.get()) {
            log(TAG, "Already initialized", LogLevel.WARNING)
            return@withContext true
        }

        _connectionState.value = ConnectionState.INITIALIZING
        deviceMode = settings.deviceMode
        deviceName = settings.deviceName

        try {
            log(TAG, "Initializing Android HID backend...", LogLevel.INFO)

            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: throw Exception("BluetoothManager not available")

            bluetoothAdapter = bluetoothManager.adapter
                ?: throw Exception("Bluetooth adapter not available")

            if (!bluetoothAdapter!!.isEnabled) {
                throw Exception("Bluetooth is not enabled")
            }

            // Request the HID Device profile proxy
            val proxyReady = CompletableDeferred<Boolean>()

            val proxyListener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hidDevice = proxy as? BluetoothHidDevice
                        log(TAG, "BluetoothHidDevice proxy connected", LogLevel.INFO)
                        proxyReady.complete(hidDevice != null)
                    }
                }
                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hidDevice = null
                        log(TAG, "BluetoothHidDevice proxy disconnected", LogLevel.WARNING)
                    }
                }
            }

            val requested = bluetoothAdapter!!.getProfileProxy(
                context,
                proxyListener,
                BluetoothProfile.HID_DEVICE
            )

            if (!requested) {
                throw Exception("Failed to request HID_DEVICE profile proxy")
            }

            // Wait for proxy with timeout
            val gotProxy = withTimeoutOrNull(10_000L) { proxyReady.await() } ?: false
            if (!gotProxy || hidDevice == null) {
                throw Exception("BluetoothHidDevice proxy not available (timeout)")
            }

            // Register the HID application with appropriate descriptor
            val registered = registerHidApp()
            if (!registered) {
                throw Exception("Failed to register HID application")
            }

            initialized.set(true)
            _connectionState.value = ConnectionState.DISCONNECTED

            log(TAG, "Android HID backend initialized successfully", LogLevel.INFO)
            true

        } catch (e: Exception) {
            log(TAG, "Initialization failed: ${e.message}", LogLevel.ERROR)
            _connectionState.value = ConnectionState.ERROR
            _errorChannel.send("Android HID init failed: ${e.message}")
            false
        }
    }

    /**
     * Register the HID application (SDP record) with the Android Bluetooth stack.
     */
    private fun registerHidApp(): Boolean {
        val hid = hidDevice ?: return false

        val descriptor = when (deviceMode) {
            DeviceMode.KEYBOARD -> keyboardOnlyDescriptor
            DeviceMode.MOUSE -> mouseOnlyDescriptor
            DeviceMode.COMBO -> comboDescriptor
            DeviceMode.GAMEPAD -> gamepadDescriptor
        }

        val sdpRecord = BluetoothHidDeviceAppSdpSettings(
            deviceName,
            "HID Remote",
            "CourierStack",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            descriptor
        )

        val qosOut = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,   // token rate
            9,     // token bucket size
            0,     // peak bandwidth
            11250, // latency (11.25ms)
            BluetoothHidDeviceAppQosSettings.MAX
        )

        return hid.registerApp(sdpRecord, null, qosOut, callbackExecutor, hidCallback)
    }

    override fun isInitialized(): Boolean = initialized.get()

    override fun isConnected(): Boolean =
        _connectionState.value == ConnectionState.CONNECTED && connectedBtDevice != null

    // ==================== Pairing / Discovery ====================

    override fun startPairing(): Boolean {
        if (!initialized.get()) {
            log(TAG, "Cannot start pairing — not initialized", LogLevel.ERROR)
            return false
        }
        if (_connectionState.value == ConnectionState.CONNECTED) {
            log(TAG, "Already connected — disconnect first", LogLevel.WARNING)
            return false
        }

        // With the Android stack, the device is already connectable after registerApp().
        // We just update our state to indicate we're waiting for a connection.
        _connectionState.value = ConnectionState.SCANNING
        log(TAG, "Pairing mode active — device is discoverable as '$deviceName'", LogLevel.INFO)
        log(TAG, "Go to host Bluetooth settings and pair with '$deviceName'", LogLevel.INFO)
        return true
    }

    override fun stopPairing() {
        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.DISCONNECTED
            log(TAG, "Pairing mode stopped", LogLevel.INFO)
        }
    }

    // ==================== Scanning ====================

    override fun startScanning() {
        // Android native mode doesn't do active scanning — we wait for hosts
        startPairing()
    }

    override fun stopScanning() {
        stopPairing()
    }

    // ==================== Connection ====================

    override fun connectToHost(host: HostDevice) {
        startPairing()
        log(TAG, "Ready for connection from ${host.displayName}", LogLevel.INFO)
    }

    override fun connectToBondedDevice(address: String): Boolean {
        if (!initialized.get()) return false

        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return false
        val hid = hidDevice ?: return false

        _connectionState.value = ConnectionState.CONNECTING
        log(TAG, "Initiating connection to bonded device $address", LogLevel.INFO)

        return try {
            hid.connect(device)
        } catch (e: Exception) {
            log(TAG, "Failed to connect: ${e.message}", LogLevel.ERROR)
            _connectionState.value = ConnectionState.DISCONNECTED
            false
        }
    }

    override fun connectToAnyBondedDevice(): Boolean {
        val bonded = getBondedDevices()
        if (bonded.isEmpty()) return false
        return connectToBondedDevice(bonded.first())
    }

    override fun disconnect() {
        val device = connectedBtDevice
        if (device != null) {
            hidDevice?.disconnect(device)
        }
        connectedBtDevice = null
        _connectedHost.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
        clearInputState()
    }

    // ==================== Bonded Devices ====================

    override fun getBondedDevices(): List<String> {
        return try {
            bluetoothAdapter?.bondedDevices
                ?.map { it.address }
                ?: emptyList()
        } catch (e: SecurityException) {
            log(TAG, "Cannot access bonded devices: ${e.message}", LogLevel.WARNING)
            emptyList()
        }
    }

    override fun removeBond(address: String) {
        try {
            val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
            // Use reflection to call removeBond() — not a public API but widely used
            val method = device.javaClass.getMethod("removeBond")
            method.invoke(device)
            log(TAG, "Removed bond for $address", LogLevel.INFO)
        } catch (e: Exception) {
            log(TAG, "Failed to remove bond for $address: ${e.message}", LogLevel.WARNING)
        }
    }

    override fun clearAllBonds() {
        getBondedDevices().forEach { removeBond(it) }
    }

    // ==================== Report ID Helpers ====================

    private val keyboardReportId: Int
        get() = if (deviceMode == DeviceMode.COMBO) 1 else 0

    private val mouseReportId: Int
        get() = if (deviceMode == DeviceMode.COMBO) 2 else 0

    private val gamepadReportId: Int
        get() = 3

    private fun canSendKeyboard(): Boolean =
        deviceMode == DeviceMode.KEYBOARD || deviceMode == DeviceMode.COMBO

    private fun canSendMouse(): Boolean =
        deviceMode == DeviceMode.MOUSE || deviceMode == DeviceMode.COMBO

    private fun canSendGamepad(): Boolean =
        deviceMode == DeviceMode.GAMEPAD

    // ==================== Keyboard Input ====================

    override fun pressKey(keyCode: Int, modifiers: ModifierState) {
        if (!isConnected() || !canSendKeyboard()) return
        currentModifiers = modifiers
        if (pressedKeys.size < MAX_KEYS) pressedKeys.add(keyCode)
        sendKeyboardReport()
    }

    override fun releaseKey(keyCode: Int) {
        if (!isConnected() || !canSendKeyboard()) return
        pressedKeys.remove(keyCode)
        sendKeyboardReport()
    }

    override fun typeKey(keyCode: Int, modifiers: ModifierState) {
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

    override fun typeKeyWithModifiers(keyCode: Int, modifiers: ModifierState) {
        if (!isConnected() || !canSendKeyboard()) return
        scope.launch {
            val savedModifiers = currentModifiers
            val savedKeys = pressedKeys.toSet()
            currentModifiers = modifiers
            pressedKeys.clear()
            pressedKeys.add(keyCode)
            sendKeyboardReport()
            delay(50)
            pressedKeys.clear()
            currentModifiers = ModifierState()
            sendKeyboardReport()
            currentModifiers = savedModifiers
            pressedKeys.addAll(savedKeys)
            if (savedKeys.isNotEmpty() || savedModifiers.isAnyActive()) {
                sendKeyboardReport()
            }
        }
    }

    override fun typeText(text: String, delayMs: Long) {
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

    override fun releaseAllKeys() {
        pressedKeys.clear()
        currentModifiers = ModifierState()
        if (isConnected() && canSendKeyboard()) sendKeyboardReport()
    }

    override fun setModifiers(modifiers: ModifierState) {
        currentModifiers = modifiers
        if (isConnected() && canSendKeyboard()) sendKeyboardReport()
    }

    private fun sendKeyboardReport() {
        val device = connectedBtDevice ?: return
        val hid = hidDevice ?: return

        val data = ByteArray(8)
        data[0] = currentModifiers.toByte().toByte()
        data[1] = 0 // Reserved
        pressedKeys.take(6).forEachIndexed { i, key -> data[2 + i] = key.toByte() }

        val id = keyboardReportId
        hid.sendReport(device, id, data)
    }

    private fun buildCurrentKeyboardReport(): ByteArray {
        val data = ByteArray(8)
        data[0] = currentModifiers.toByte().toByte()
        pressedKeys.take(6).forEachIndexed { i, key -> data[2 + i] = key.toByte() }
        return data
    }

    // ==================== Mouse Input ====================

    override fun moveMouse(dx: Int, dy: Int) {
        if (!isConnected() || !canSendMouse()) return
        sendMouseReport(currentMouseButtons.toByte(), dx, dy, 0)
    }

    override fun scroll(amount: Int) {
        if (!isConnected() || !canSendMouse()) return
        sendMouseReport(currentMouseButtons.toByte(), 0, 0, amount)
    }

    override fun pressMouseButton(button: Int) {
        if (!isConnected() || !canSendMouse()) return
        currentMouseButtons = when (button) {
            MouseButtons.LEFT -> currentMouseButtons.copy(left = true)
            MouseButtons.RIGHT -> currentMouseButtons.copy(right = true)
            MouseButtons.MIDDLE -> currentMouseButtons.copy(middle = true)
            else -> currentMouseButtons
        }
        sendMouseReport(currentMouseButtons.toByte(), 0, 0, 0)
    }

    override fun releaseMouseButton(button: Int) {
        if (!isConnected() || !canSendMouse()) return
        currentMouseButtons = when (button) {
            MouseButtons.LEFT -> currentMouseButtons.copy(left = false)
            MouseButtons.RIGHT -> currentMouseButtons.copy(right = false)
            MouseButtons.MIDDLE -> currentMouseButtons.copy(middle = false)
            else -> currentMouseButtons
        }
        sendMouseReport(currentMouseButtons.toByte(), 0, 0, 0)
    }

    override fun clickMouseButton(button: Int) {
        if (!isConnected() || !canSendMouse()) return
        pressMouseButton(button)
        scope.launch {
            delay(50)
            releaseMouseButton(button)
        }
    }

    private fun sendMouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        val device = connectedBtDevice ?: return
        val hid = hidDevice ?: return

        val data = ByteArray(4)
        data[0] = buttons.toByte()
        data[1] = dx.coerceIn(-127, 127).toByte()
        data[2] = dy.coerceIn(-127, 127).toByte()
        data[3] = wheel.coerceIn(-127, 127).toByte()

        hid.sendReport(device, mouseReportId, data)
    }

    // ==================== Gamepad Input ====================

    override fun sendGamepad(
        buttons: Int, leftX: Int, leftY: Int,
        rightX: Int, rightY: Int,
        leftTrigger: Int, rightTrigger: Int, dpad: Int
    ) {
        if (!isConnected() || !canSendGamepad()) return
        val device = connectedBtDevice ?: return
        val hid = hidDevice ?: return

        val data = ByteArray(9)
        data[0] = (buttons and 0xFF).toByte()
        data[1] = ((buttons shr 8) and 0xFF).toByte()
        data[2] = leftX.coerceIn(0, 255).toByte()
        data[3] = leftY.coerceIn(0, 255).toByte()
        data[4] = rightX.coerceIn(0, 255).toByte()
        data[5] = rightY.coerceIn(0, 255).toByte()
        data[6] = leftTrigger.coerceIn(0, 255).toByte()
        data[7] = rightTrigger.coerceIn(0, 255).toByte()
        data[8] = (dpad.coerceIn(0, 0x0F) and 0x0F).toByte()

        hid.sendReport(device, gamepadReportId, data)
    }

    // ==================== Configuration ====================

    override fun updateConfiguration(settings: AppSettings) {
        val modeChanged = deviceMode != settings.deviceMode
        val nameChanged = deviceName != settings.deviceName

        deviceMode = settings.deviceMode
        deviceName = settings.deviceName

        // Re-register if mode or name changed and we're not connected
        if (initialized.get() && _connectionState.value == ConnectionState.DISCONNECTED
            && (modeChanged || nameChanged)
        ) {
            hidDevice?.unregisterApp()
            registerHidApp()
            log(TAG, "Re-registered HID app: $deviceName ($deviceMode)", LogLevel.INFO)
        }
    }

    // ==================== Shutdown ====================

    override fun shutdown() {
        log(TAG, "Shutting down Android HID backend", LogLevel.INFO)
        disconnect()

        try {
            hidDevice?.unregisterApp()
        } catch (e: Exception) {
            log(TAG, "Error unregistering app: ${e.message}", LogLevel.WARNING)
        }

        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        hidDevice = null
        bluetoothAdapter = null

        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        initialized.set(false)

        log(TAG, "Shutdown complete", LogLevel.INFO)
    }

    // ==================== Utility ====================

    private fun clearInputState() {
        pressedKeys.clear()
        currentModifiers = ModifierState()
        currentMouseButtons = MouseButtonState()
    }

    private fun log(tag: String, message: String, level: LogLevel) {
        val entry = LogEntry(tag = tag, message = message, level = level)
        _logs.value = (_logs.value + entry).takeLast(500)
        when (level) {
            LogLevel.ERROR -> Log.e(tag, message)
            LogLevel.WARNING -> Log.w(tag, message)
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
        }
    }
}
