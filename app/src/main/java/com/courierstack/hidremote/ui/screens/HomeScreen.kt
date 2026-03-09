package com.courierstack.hidremote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.courierstack.hidremote.data.ConnectionState
import com.courierstack.hidremote.data.DeviceMode
import com.courierstack.hidremote.data.HostDevice
import com.courierstack.hidremote.ui.theme.*

/**
 * Home screen showing connection status, bonded devices, and quick actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    connectionState: ConnectionState,
    connectedHost: HostDevice?,
    deviceMode: DeviceMode,
    isInitializing: Boolean,
    isInitialized: Boolean,
    onInitialize: () -> Unit,
    onStartPairing: () -> Unit,
    onStopPairing: () -> Unit,
    onDisconnect: () -> Unit,
    onNavigateToKeyboard: () -> Unit,
    onNavigateToMouse: () -> Unit,
    onNavigateToGamepad: () -> Unit = {},
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    // Optional parameters for enhanced features
    deviceName: String = "BT HID Remote",
    bondedDevices: List<String> = emptyList(),
    onConnectToBonded: (String) -> Unit = {},
    onRemoveBond: (String) -> Unit = {},
    onStopService: () -> Unit = {}
) {
    val config = LocalConfiguration.current
    val isSmall = config.screenWidthDp < 360 || config.screenHeightDp < 640
    val edgePad = if (isSmall) 10.dp else 16.dp
    val gap = if (isSmall) 10.dp else 16.dp

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(edgePad),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        // Status card
        item {
            ConnectionStatusCard(
                connectionState = connectionState,
                connectedHost = connectedHost,
                deviceMode = deviceMode,
                deviceName = deviceName,
                isInitialized = isInitialized
            )
        }

        // Main action section
        item {
            ActionSection(
                connectionState = connectionState,
                isInitializing = isInitializing,
                isInitialized = isInitialized,
                deviceName = deviceName,
                onInitialize = onInitialize,
                onStartPairing = onStartPairing,
                onStopPairing = onStopPairing,
                onDisconnect = onDisconnect
            )
        }

        // Bonded devices section
        if (isInitialized && connectionState != ConnectionState.CONNECTED && bondedDevices.isNotEmpty()) {
            item {
                Text(
                    text = "Previously Paired Devices",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            items(bondedDevices) { address ->
                BondedDeviceCard(
                    address = address,
                    isPairing = connectionState == ConnectionState.SCANNING,
                    onConnect = { onConnectToBonded(address) },
                    onRemove = { onRemoveBond(address) }
                )
            }
        }

        // Quick access buttons (only when connected)
        if (connectionState == ConnectionState.CONNECTED) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Quick Access",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    QuickAccessCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Keyboard,
                        title = "Keyboard",
                        onClick = onNavigateToKeyboard
                    )
                    QuickAccessCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Mouse,
                        title = "Touchpad",
                        onClick = onNavigateToMouse
                    )
                    QuickAccessCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.SportsEsports,
                        title = "Gamepad",
                        onClick = onNavigateToGamepad
                    )
                }
            }
        }

        // Settings button
        item {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onNavigateToSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Settings")
            }
        }

        // Stop service button (show when initialized but not connected)
        if (isInitialized && connectionState != ConnectionState.CONNECTED) {
            item {
                OutlinedButton(
                    onClick = onStopService,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop Bluetooth Stack")
                }
            }
        }
    }
}

@Composable
private fun ActionSection(
    connectionState: ConnectionState,
    isInitializing: Boolean,
    isInitialized: Boolean,
    deviceName: String,
    onInitialize: () -> Unit,
    onStartPairing: () -> Unit,
    onStopPairing: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isSmall = LocalConfiguration.current.let { it.screenWidthDp < 360 || it.screenHeightDp < 640 }
    val btnHeight = if (isSmall) 44.dp else 56.dp

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            isInitializing -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Initializing Bluetooth Stack...",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            connectionState == ConnectionState.ERROR -> {
                Button(
                    onClick = onInitialize,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(btnHeight),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Retry Initialization")
                }
            }

            !isInitialized -> {
                Button(
                    onClick = onInitialize,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(btnHeight),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Initialize Bluetooth Stack")
                }

                Text(
                    text = "Initialize the Bluetooth stack first, then start pairing mode",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            connectionState == ConnectionState.SCANNING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = Connecting
                )

                Text(
                    text = "Pairing mode active",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Connecting
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Connecting.copy(alpha = 0.1f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "On your PC/Mac:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "1. Open Bluetooth settings\n2. Search for \"$deviceName\"\n3. Click to pair",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                OutlinedButton(
                    onClick = onStopPairing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop Pairing")
                }
            }

            connectionState == ConnectionState.CONNECTING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = Connecting
                )
                Text(
                    text = "Connecting...",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            connectionState == ConnectionState.CONNECTED -> {
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(btnHeight),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Disconnected
                    )
                ) {
                    Icon(Icons.Default.BluetoothDisabled, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Disconnect")
                }
            }

            isInitialized && connectionState == ConnectionState.DISCONNECTED -> {
                Button(
                    onClick = onStartPairing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(btnHeight),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.BluetoothSearching, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Pairing Mode")
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Connected,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Stack initialized - Ready to pair",
                        style = MaterialTheme.typography.bodySmall,
                        color = Connected
                    )
                }
            }
        }
    }
}

@Composable
private fun BondedDeviceCard(
    address: String,
    isPairing: Boolean,
    onConnect: () -> Unit,
    onRemove: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val isSmall = LocalConfiguration.current.let { it.screenWidthDp < 360 }
    val iconBoxSize = if (isSmall) 32.dp else 40.dp

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(iconBoxSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Computer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Previously paired",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FilledTonalButton(
                onClick = onConnect,
                enabled = !isPairing
            ) {
                if (isPairing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Connect")
                }
            }

            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Remove Device") },
            text = { Text("Remove pairing with $address? You'll need to pair again to reconnect.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ConnectionStatusCard(
    connectionState: ConnectionState,
    connectedHost: HostDevice?,
    deviceMode: DeviceMode,
    deviceName: String,
    isInitialized: Boolean,
    modifier: Modifier = Modifier
) {
    val config = LocalConfiguration.current
    val isSmall = config.screenWidthDp < 360 || config.screenHeightDp < 640
    val iconBoxSize = if (isSmall) 52.dp else 72.dp
    val iconSize = if (isSmall) 28.dp else 40.dp
    val cardPadding = if (isSmall) 16.dp else 24.dp

    val (statusColor, statusText, statusIcon) = when {
        connectionState == ConnectionState.CONNECTED -> Triple(Connected, "Connected", Icons.Default.Bluetooth)
        connectionState == ConnectionState.CONNECTING -> Triple(Connecting, "Connecting", Icons.Default.BluetoothSearching)
        connectionState == ConnectionState.SCANNING -> Triple(Connecting, "Pairing Mode", Icons.Default.BluetoothSearching)
        connectionState == ConnectionState.INITIALIZING -> Triple(Connecting, "Initializing", Icons.Default.Sync)
        connectionState == ConnectionState.ERROR -> Triple(Disconnected, "Error", Icons.Default.Error)
        isInitialized -> Triple(Color(0xFF4CAF50), "Ready", Icons.Default.CheckCircle)
        else -> Triple(Disconnected, "Not Initialized", Icons.Default.BluetoothDisabled)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(cardPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (isSmall) 8.dp else 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(iconBoxSize)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    tint = statusColor
                )
            }

            Text(
                text = statusText,
                style = if (isSmall) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )

            if (connectionState == ConnectionState.CONNECTED && connectedHost != null) {
                Text(
                    text = connectedHost.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (connectionState == ConnectionState.SCANNING) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Visible as: $deviceName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AssistChip(
                onClick = { },
                label = { Text(deviceMode.displayName) },
                leadingIcon = {
                    Icon(
                        imageVector = when (deviceMode) {
                            DeviceMode.KEYBOARD -> Icons.Default.Keyboard
                            DeviceMode.MOUSE -> Icons.Default.Mouse
                            DeviceMode.COMBO -> Icons.Default.DevicesOther
                            DeviceMode.GAMEPAD -> Icons.Default.SportsEsports
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickAccessCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit
) {
    val isSmall = LocalConfiguration.current.screenWidthDp < 360
    val cardPadding = if (isSmall) 12.dp else 24.dp
    val iconSize = if (isSmall) 32.dp else 48.dp

    Card(
        modifier = modifier,
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(cardPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (isSmall) 4.dp else 8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = title,
                style = if (isSmall) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}