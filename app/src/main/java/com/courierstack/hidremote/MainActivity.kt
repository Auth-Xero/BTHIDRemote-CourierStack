package com.courierstack.hidremote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.courierstack.hidremote.data.ConnectionState
import com.courierstack.hidremote.data.UiEvent
import com.courierstack.hidremote.ui.screens.*
import com.courierstack.hidremote.ui.theme.BTHidRemoteTheme
import com.courierstack.hidremote.viewmodel.HidRemoteViewModel
import kotlinx.coroutines.flow.collectLatest

/**
 * Main Activity for BT HID Remote application.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: HidRemoteViewModel by viewModels()

    private val requiredPermissions = buildList {
        add(Manifest.permission.BLUETOOTH)
        add(Manifest.permission.BLUETOOTH_ADMIN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.startService()
        } else {
            Toast.makeText(
                this,
                getString(R.string.error_permissions_required),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BTHidRemoteTheme {
                HidRemoteApp(
                    viewModel = viewModel,
                    onRequestPermissions = { checkAndRequestPermissions() }
                )
            }
        }

        // Check permissions on start
        if (hasAllPermissions()) {
            viewModel.startService()
        }
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAndRequestPermissions() {
        if (hasAllPermissions()) {
            viewModel.startService()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do NOT stop the foreground service here.
        // The BT connection should survive activity destruction (rotation,
        // swipe-from-recents, etc.). The service is stopped via:
        //  - Notification "Disconnect" action
        //  - Explicit user action in the app (future "Stop" button)
        //  - Android killing the process (service is START_NOT_STICKY)
    }
}

/**
 * Navigation routes.
 */
sealed class Screen(val route: String, val title: String, val icon: @Composable () -> Unit) {
    data object Home : Screen("home", "Home", { Icon(Icons.Default.Home, contentDescription = null) })
    data object Keyboard : Screen("keyboard", "Keyboard", { Icon(Icons.Default.Keyboard, contentDescription = null) })
    data object Mouse : Screen("mouse", "Touchpad", { Icon(Icons.Default.Mouse, contentDescription = null) })
    data object Gamepad : Screen("gamepad", "Gamepad", { Icon(Icons.Default.SportsEsports, contentDescription = null) })
    data object Settings : Screen("settings", "Settings", { Icon(Icons.Default.Settings, contentDescription = null) })
}

private val bottomNavItems = listOf(
    Screen.Home,
    Screen.Keyboard,
    Screen.Mouse,
    Screen.Gamepad,
    Screen.Settings
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HidRemoteApp(
    viewModel: HidRemoteViewModel,
    onRequestPermissions: () -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val snackbarHostState = remember { SnackbarHostState() }

    // Collect states
    val uiState by viewModel.uiState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedHost by viewModel.connectedHost.collectAsState()
    val modifierState by viewModel.modifierState.collectAsState()
    val ledState by viewModel.ledState.collectAsState()
    val isInitialized by viewModel.isInitialized.collectAsState()
    val bondedDevices by viewModel.bondedDevices.collectAsState()

    // Handle UI events
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is UiEvent.ShowToast -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is UiEvent.ShowError -> {
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Long
                    )
                }
                is UiEvent.NavigateToSettings -> {
                    navController.navigate(Screen.Settings.route)
                }
                is UiEvent.RequestPermissions -> {
                    onRequestPermissions()
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (currentDestination?.route) {
                            Screen.Home.route -> "BT HID Remote"
                            Screen.Keyboard.route -> "Keyboard"
                            Screen.Mouse.route -> "Touchpad"
                            Screen.Gamepad.route -> "Gamepad"
                            Screen.Settings.route -> "Settings"
                            else -> "BT HID Remote"
                        }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    ConnectionIndicator(connectionState = connectionState)
                }
            )
        },
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEach { screen ->
                    NavigationBarItem(
                        icon = screen.icon,
                        label = { Text(screen.title) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    connectionState = connectionState,
                    connectedHost = connectedHost,
                    deviceMode = settings.deviceMode,
                    deviceName = settings.deviceName,
                    isInitializing = uiState.isInitializing,
                    isInitialized = isInitialized,
                    bondedDevices = bondedDevices,
                    onInitialize = {
                        onRequestPermissions()
                        viewModel.initialize()
                    },
                    onStartPairing = { viewModel.startPairing() },
                    onStopPairing = { viewModel.stopPairing() },
                    onDisconnect = { viewModel.disconnect() },
                    onConnectToBonded = { viewModel.connectToBondedDevice(it) },
                    onRemoveBond = { viewModel.removeBond(it) },
                    onNavigateToKeyboard = {
                        navController.navigate(Screen.Keyboard.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToMouse = {
                        navController.navigate(Screen.Mouse.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToGamepad = {
                        navController.navigate(Screen.Gamepad.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onStopService = { viewModel.stopService() }
                )
            }

            composable(Screen.Keyboard.route) {
                KeyboardScreen(
                    connectionState = connectionState,
                    modifierState = modifierState,
                    ledState = ledState,
                    onKeyPress = { viewModel.pressKey(it) },
                    onKeyRelease = { viewModel.releaseKey(it) },
                    onModifierToggle = { viewModel.toggleModifier(it) },
                    onTypeText = { viewModel.typeText(it) },
                    onReleaseAllKeys = { viewModel.releaseAllKeys() },
                    onTypeKey = { viewModel.typeKey(it) },
                    onTypeKeyWithModifiers = { keyCode, mods ->
                        viewModel.typeKeyWithModifiers(keyCode, mods)
                    }
                )
            }

            composable(Screen.Mouse.route) {
                MouseScreen(
                    connectionState = connectionState,
                    sensitivity = settings.mouseSensitivity,
                    tapToClick = settings.tapToClick,
                    naturalScrolling = settings.naturalScrolling,
                    onMove = { dx, dy -> viewModel.moveMouse(dx, dy, settings.mouseSensitivity) },
                    onScroll = { viewModel.scroll(it, settings.naturalScrolling) },
                    onTap = { viewModel.tapClick() },
                    onButtonPress = { viewModel.pressMouseButton(it) },
                    onButtonRelease = { viewModel.releaseMouseButton(it) },
                    onButtonClick = { viewModel.clickMouseButton(it) }
                )
            }

            composable(Screen.Gamepad.route) {
                GamepadScreen(
                    connectionState = connectionState,
                    onSendGamepad = { buttons, lx, ly, rx, ry, lt, rt, dpad ->
                        viewModel.sendGamepad(buttons, lx, ly, rx, ry, lt, rt, dpad)
                    }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    settings = settings,
                    onUpdateDeviceName = { viewModel.updateDeviceName(it) },
                    onUpdateDeviceMode = { viewModel.updateDeviceMode(it) },
                    onUpdateHapticFeedback = { viewModel.updateHapticFeedback(it) },
                    onUpdateMouseSensitivity = { viewModel.updateMouseSensitivity(it) },
                    onUpdateTapToClick = { viewModel.updateTapToClick(it) },
                    onUpdateNaturalScrolling = { viewModel.updateNaturalScrolling(it) },
                    onUpdateAutoConnect = { viewModel.updateAutoConnect(it) },
                    onStopService = { viewModel.stopService() }
                )
            }
        }
    }
}

@Composable
private fun ConnectionIndicator(connectionState: ConnectionState) {
    val (icon, tint) = when (connectionState) {
        ConnectionState.CONNECTED -> Icons.Default.Bluetooth to MaterialTheme.colorScheme.onPrimary
        ConnectionState.CONNECTING, ConnectionState.SCANNING -> Icons.Default.BluetoothSearching to MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
        else -> Icons.Default.BluetoothDisabled to MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
    }

    Icon(
        imageVector = icon,
        contentDescription = "Connection status",
        tint = tint,
        modifier = Modifier.padding(end = 16.dp)
    )
}