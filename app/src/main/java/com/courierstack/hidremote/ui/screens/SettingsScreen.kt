package com.courierstack.hidremote.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.courierstack.hidremote.data.AppSettings
import com.courierstack.hidremote.data.DeviceMode
import com.courierstack.hidremote.data.HidBackendMode

/**
 * Settings screen for configuring HID device options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdateDeviceName: (String) -> Unit,
    onUpdateDeviceMode: (DeviceMode) -> Unit,
    onUpdateHidBackendMode: (HidBackendMode) -> Unit = {},
    onUpdateHapticFeedback: (Boolean) -> Unit,
    onUpdateMouseSensitivity: (Float) -> Unit,
    onUpdateTapToClick: (Boolean) -> Unit,
    onUpdateNaturalScrolling: (Boolean) -> Unit,
    onUpdateAutoConnect: (Boolean) -> Unit,
    onStopService: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showDeviceNameDialog by remember { mutableStateOf(false) }
    var showDeviceModeDialog by remember { mutableStateOf(false) }
    var showBackendModeDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Device Settings Section
        SettingsSection(title = "Device Settings") {
            // Device Name
            SettingsItem(
                icon = Icons.Default.Label,
                title = "Device Name",
                subtitle = settings.deviceName,
                onClick = { showDeviceNameDialog = true }
            )

            // Device Mode
            SettingsItem(
                icon = Icons.Default.Devices,
                title = "Device Mode",
                subtitle = settings.deviceMode.displayName,
                onClick = { showDeviceModeDialog = true }
            )

            // Auto Connect
            SettingsSwitchItem(
                icon = Icons.Default.BluetoothConnected,
                title = "Auto-Connect",
                subtitle = "Automatically connect to last device",
                checked = settings.autoConnect,
                onCheckedChange = onUpdateAutoConnect
            )
        }

        // Bluetooth Backend Section
        SettingsSection(title = "Bluetooth Backend") {
            SettingsItem(
                icon = Icons.Default.Memory,
                title = "HID Backend",
                subtitle = settings.hidBackendMode.displayName,
                onClick = { showBackendModeDialog = true }
            )

            // Explanatory text
            Text(
                text = "Android Native uses the built-in Bluetooth stack (recommended). " +
                        "CourierStack is an experimental low-level backend. " +
                        "Changing backend requires stopping and re-initializing the service.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        // Input Settings Section
        SettingsSection(title = "Input Settings") {
            // Haptic Feedback
            SettingsSwitchItem(
                icon = Icons.Default.Vibration,
                title = "Haptic Feedback",
                subtitle = "Vibrate on key/button press",
                checked = settings.hapticFeedback,
                onCheckedChange = onUpdateHapticFeedback
            )

            // Mouse Sensitivity
            SettingsSliderItem(
                icon = Icons.Default.Speed,
                title = "Mouse Sensitivity",
                value = settings.mouseSensitivity,
                valueRange = 0.1f..3f,
                onValueChange = onUpdateMouseSensitivity
            )

            // Tap to Click
            SettingsSwitchItem(
                icon = Icons.Default.TouchApp,
                title = "Tap to Click",
                subtitle = "Tap touchpad to left-click",
                checked = settings.tapToClick,
                onCheckedChange = onUpdateTapToClick
            )

            // Natural Scrolling
            SettingsSwitchItem(
                icon = Icons.Default.SwapVert,
                title = "Natural Scrolling",
                subtitle = "Scroll content in finger direction",
                checked = settings.naturalScrolling,
                onCheckedChange = onUpdateNaturalScrolling
            )
        }

        // About Section
        SettingsSection(title = "About") {
            SettingsItem(
                icon = Icons.Default.Info,
                title = "Version",
                subtitle = "1.0.0",
                onClick = { }
            )

            SettingsItem(
                icon = Icons.Default.Code,
                title = "Built with",
                subtitle = "CourierStack Bluetooth Library",
                onClick = { }
            )
        }

        // Advanced Section
        SettingsSection(title = "Advanced") {
            SettingsItem(
                icon = Icons.Default.PowerSettingsNew,
                title = "Stop Bluetooth Stack",
                subtitle = "Shut down service and release hardware",
                onClick = onStopService
            )
        }
    }

    // Device Name Dialog
    if (showDeviceNameDialog) {
        var newName by remember { mutableStateOf(settings.deviceName) }

        AlertDialog(
            onDismissRequest = { showDeviceNameDialog = false },
            title = { Text("Device Name") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onUpdateDeviceName(newName.trim())
                        }
                        showDeviceNameDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeviceNameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Device Mode Dialog
    if (showDeviceModeDialog) {
        AlertDialog(
            onDismissRequest = { showDeviceModeDialog = false },
            title = { Text("Device Mode") },
            text = {
                Column {
                    DeviceMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onUpdateDeviceMode(mode)
                                    showDeviceModeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.deviceMode == mode,
                                onClick = {
                                    onUpdateDeviceMode(mode)
                                    showDeviceModeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = mode.displayName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = when (mode) {
                                        DeviceMode.KEYBOARD -> "Only keyboard input"
                                        DeviceMode.MOUSE -> "Only mouse/touchpad input"
                                        DeviceMode.COMBO -> "Both keyboard and mouse"
                                        DeviceMode.GAMEPAD -> "Game controller input"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeviceModeDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Backend Mode Dialog
    if (showBackendModeDialog) {
        AlertDialog(
            onDismissRequest = { showBackendModeDialog = false },
            title = { Text("HID Backend") },
            text = {
                Column {
                    HidBackendMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onUpdateHidBackendMode(mode)
                                    showBackendModeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.hidBackendMode == mode,
                                onClick = {
                                    onUpdateHidBackendMode(mode)
                                    showBackendModeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = mode.displayName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = when (mode) {
                                        HidBackendMode.ANDROID_NATIVE ->
                                            "Uses Android's built-in BluetoothHidDevice API. " +
                                                    "No root required. Works on Android 9+."
                                        HidBackendMode.COURIER_STACK ->
                                            "Uses CourierStack low-level Bluetooth library. " +
                                                    "Experimental — kills Android BT stack. " +
                                                    "Use as backup if native mode fails."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "After changing backend, stop the Bluetooth stack and re-initialize.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBackendModeDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                content = content
            )
        }
    }
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsSwitchItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsSliderItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = String.format("%.1fx", value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.padding(start = 40.dp)
        )
    }
}