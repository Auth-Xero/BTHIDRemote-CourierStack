package com.courierstack.hidremote.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.courierstack.hidremote.data.ConnectionState
import com.courierstack.hidremote.data.MouseButtons
import com.courierstack.hidremote.ui.components.ScrollWheel
import com.courierstack.hidremote.ui.components.Touchpad
import com.courierstack.hidremote.ui.theme.Connected
import com.courierstack.hidremote.ui.theme.Disconnected

/**
 * Mouse/Touchpad input screen with large touchpad area.
 * Adapts layout for portrait vs landscape orientation.
 */
@Composable
fun MouseScreen(
    connectionState: ConnectionState,
    sensitivity: Float,
    tapToClick: Boolean,
    naturalScrolling: Boolean,
    onMove: (dx: Float, dy: Float) -> Unit,
    onScroll: (amount: Float) -> Unit,
    onTap: () -> Unit,
    onButtonPress: (Int) -> Unit,
    onButtonRelease: (Int) -> Unit,
    onButtonClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected = connectionState == ConnectionState.CONNECTED
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(if (isLandscape) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (isLandscape) 6.dp else 12.dp)
    ) {
        // Connection status bar (compact in landscape)
        if (!isLandscape) {
            MouseConnectionStatusBar(isConnected = isConnected)
        }

        // Main touchpad area with scroll wheel
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 8.dp else 12.dp)
        ) {
            // Touchpad
            Touchpad(
                modifier = Modifier.weight(1f),
                sensitivity = sensitivity,
                tapToClick = tapToClick && isConnected,
                naturalScrolling = naturalScrolling,
                onMove = { dx, dy -> if (isConnected) onMove(dx, dy) },
                onScroll = { if (isConnected) onScroll(it) },
                onTap = { if (isConnected) onTap() },
                onButtonPress = { if (isConnected) onButtonPress(it) },
                onButtonRelease = { if (isConnected) onButtonRelease(it) }
            )

            // In landscape, put quick actions in a vertical column on the right
            if (isLandscape) {
                LandscapeQuickActions(
                    isConnected = isConnected,
                    naturalScrolling = naturalScrolling,
                    sensitivity = sensitivity,
                    onButtonClick = onButtonClick,
                    onButtonPress = onButtonPress,
                    onButtonRelease = onButtonRelease,
                    onScroll = onScroll
                )
            }

            // Scroll wheel
            ScrollWheel(
                modifier = Modifier.fillMaxHeight(),
                naturalScrolling = naturalScrolling,
                onScroll = { if (isConnected) onScroll(it) }
            )
        }

        // Quick actions row (only in portrait)
        if (!isLandscape) {
            QuickActionsRow(
                isConnected = isConnected,
                naturalScrolling = naturalScrolling,
                sensitivity = sensitivity,
                onButtonClick = onButtonClick,
                onButtonPress = onButtonPress,
                onButtonRelease = onButtonRelease,
                onScroll = onScroll
            )
        }
    }
}

@Composable
private fun MouseConnectionStatusBar(isConnected: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isConnected) Connected.copy(alpha = 0.1f) else Disconnected.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                contentDescription = null,
                tint = if (isConnected) Connected else Disconnected,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isConnected) "Touchpad Ready" else "Not Connected",
                style = MaterialTheme.typography.bodySmall,
                color = if (isConnected) Connected else Disconnected
            )
        }
    }
}

@Composable
private fun LandscapeQuickActions(
    isConnected: Boolean,
    naturalScrolling: Boolean,
    sensitivity: Float,
    onButtonClick: (Int) -> Unit,
    onButtonPress: (Int) -> Unit,
    onButtonRelease: (Int) -> Unit,
    onScroll: (Float) -> Unit
) {
    var isDragging by rememberSaveable { mutableStateOf(false) }

    val screenW = LocalConfiguration.current.screenWidthDp
    val columnWidth = if (screenW < 400) 48.dp else 64.dp

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(columnWidth),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Double click
        CompactActionButton(
            icon = Icons.Default.TouchApp,
            label = "2x",
            enabled = isConnected,
            modifier = Modifier.weight(1f),
            onClick = {
                onButtonClick(MouseButtons.LEFT)
                onButtonClick(MouseButtons.LEFT)
            }
        )

        // Middle click
        CompactActionButton(
            icon = Icons.Default.Circle,
            label = "Mid",
            enabled = isConnected,
            modifier = Modifier.weight(1f),
            onClick = { onButtonClick(MouseButtons.MIDDLE) }
        )

        // Drag toggle
        CompactActionButton(
            icon = if (isDragging) Icons.Default.PanTool else Icons.Default.OpenWith,
            label = if (isDragging) "Drop" else "Drag",
            enabled = isConnected,
            isActive = isDragging,
            modifier = Modifier.weight(1f),
            onClick = {
                isDragging = !isDragging
                if (isDragging) onButtonPress(MouseButtons.LEFT)
                else onButtonRelease(MouseButtons.LEFT)
            }
        )

        // Scroll up/down
        IconButton(
            onClick = { onScroll(if (naturalScrolling) 3f else -3f) },
            enabled = isConnected,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Scroll Up", modifier = Modifier.size(20.dp))
        }
        IconButton(
            onClick = { onScroll(if (naturalScrolling) -3f else 3f) },
            enabled = isConnected,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll Down", modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CompactActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        contentPadding = PaddingValues(4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isActive)
                MaterialTheme.colorScheme.onPrimary
            else
                MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text = label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun QuickActionsRow(
    isConnected: Boolean,
    naturalScrolling: Boolean,
    sensitivity: Float,
    onButtonClick: (Int) -> Unit,
    onButtonPress: (Int) -> Unit,
    onButtonRelease: (Int) -> Unit,
    onScroll: (Float) -> Unit
) {
    var isDragging by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Double click
        ActionButton(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.TouchApp,
            label = "Double",
            enabled = isConnected,
            onClick = {
                onButtonClick(MouseButtons.LEFT)
                onButtonClick(MouseButtons.LEFT)
            }
        )

        // Middle click
        ActionButton(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Circle,
            label = "Middle",
            enabled = isConnected,
            onClick = { onButtonClick(MouseButtons.MIDDLE) }
        )

        // Drag toggle
        ActionButton(
            modifier = Modifier.weight(1f),
            icon = if (isDragging) Icons.Default.PanTool else Icons.Default.OpenWith,
            label = if (isDragging) "Drop" else "Drag",
            enabled = isConnected,
            isActive = isDragging,
            onClick = {
                isDragging = !isDragging
                if (isDragging) {
                    onButtonPress(MouseButtons.LEFT)
                } else {
                    onButtonRelease(MouseButtons.LEFT)
                }
            }
        )

        // Scroll buttons
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = { onScroll(if (naturalScrolling) 3f else -3f) },
                    enabled = isConnected,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "Scroll Up",
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = { onScroll(if (naturalScrolling) -3f else 3f) },
                    enabled = isConnected,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Scroll Down",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Text(
                text = "${String.format("%.1f", sensitivity)}x",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActionButton(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    val isSmall = LocalConfiguration.current.let { it.screenWidthDp < 360 || it.screenHeightDp < 640 }
    val btnHeight = if (isSmall) 44.dp else 56.dp

    Button(
        onClick = onClick,
        modifier = modifier.height(btnHeight),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isActive)
                MaterialTheme.colorScheme.onPrimary
            else
                MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}