package com.courierstack.hidremote.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.courierstack.hidremote.data.ConnectionState
import com.courierstack.hidremote.data.HidKeyCodes
import com.courierstack.hidremote.data.KeyboardLedState
import com.courierstack.hidremote.data.ModifierState
import com.courierstack.hidremote.ui.components.CompactKeyboardLayout
import com.courierstack.hidremote.ui.theme.Connected
import com.courierstack.hidremote.ui.theme.Disconnected

/**
 * Keyboard input screen — adapts for orientation and screen size.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardScreen(
    connectionState: ConnectionState,
    modifierState: ModifierState,
    ledState: KeyboardLedState,
    onKeyPress: (Int) -> Unit,
    onKeyRelease: (Int) -> Unit,
    onModifierToggle: (String) -> Unit,
    onTypeText: (String) -> Unit,
    onReleaseAllKeys: () -> Unit,
    onTypeKey: (Int) -> Unit = { keyCode -> onKeyPress(keyCode); onKeyRelease(keyCode) },
    onTypeKeyWithModifiers: (Int, ModifierState) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var textToType by rememberSaveable { mutableStateOf("") }
    var showFunctionKeys by rememberSaveable { mutableStateOf(false) }
    val isConnected = connectionState == ConnectionState.CONNECTED

    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isSmall = config.screenWidthDp < 360 || config.screenHeightDp < 640

    // Adaptive padding
    val edgePad = if (isSmall) 4.dp else 8.dp

    if (isLandscape) {
        KeyboardLandscape(
            isConnected = isConnected, isSmall = isSmall, edgePad = edgePad,
            textToType = textToType, onTextChange = { textToType = it },
            showFunctionKeys = showFunctionKeys, onToggleFnKeys = { showFunctionKeys = !showFunctionKeys },
            modifierState = modifierState, ledState = ledState,
            onKeyPress = onKeyPress, onKeyRelease = onKeyRelease,
            onModifierToggle = onModifierToggle, onTypeText = onTypeText,
            onReleaseAllKeys = onReleaseAllKeys, onTypeKey = onTypeKey,
            onTypeKeyWithModifiers = onTypeKeyWithModifiers,
            modifier = modifier
        )
    } else {
        KeyboardPortrait(
            isConnected = isConnected, isSmall = isSmall, edgePad = edgePad,
            textToType = textToType, onTextChange = { textToType = it },
            showFunctionKeys = showFunctionKeys, onToggleFnKeys = { showFunctionKeys = !showFunctionKeys },
            modifierState = modifierState, ledState = ledState,
            onKeyPress = onKeyPress, onKeyRelease = onKeyRelease,
            onModifierToggle = onModifierToggle, onTypeText = onTypeText,
            onReleaseAllKeys = onReleaseAllKeys, onTypeKey = onTypeKey,
            onTypeKeyWithModifiers = onTypeKeyWithModifiers,
            modifier = modifier
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// Portrait
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyboardPortrait(
    isConnected: Boolean, isSmall: Boolean, edgePad: androidx.compose.ui.unit.Dp,
    textToType: String, onTextChange: (String) -> Unit,
    showFunctionKeys: Boolean, onToggleFnKeys: () -> Unit,
    modifierState: ModifierState, ledState: KeyboardLedState,
    onKeyPress: (Int) -> Unit, onKeyRelease: (Int) -> Unit,
    onModifierToggle: (String) -> Unit, onTypeText: (String) -> Unit,
    onReleaseAllKeys: () -> Unit, onTypeKey: (Int) -> Unit,
    onTypeKeyWithModifiers: (Int, ModifierState) -> Unit,
    modifier: Modifier = Modifier
) {
    val gap = if (isSmall) 4.dp else 8.dp

    Column(
        modifier = modifier.fillMaxSize().padding(edgePad),
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        // Status bar
        ConnectionStatusBar(isConnected, if (isConnected) "Keyboard Ready" else "Not Connected")

        // Text input
        TextInputField(textToType, onTextChange, isConnected, onTypeText)

        // Quick action chips + F-key toggle
        QuickActionRow(isConnected, showFunctionKeys, onToggleFnKeys, onTypeKey)

        // Function keys (collapsible)
        if (showFunctionKeys) {
            FunctionKeyRow(isConnected, onTypeKey)
        }

        // Shortcuts — skip on very small screens to save space
        if (!isSmall) {
            ShortcutsRow(isConnected, onTypeKeyWithModifiers)
        }

        Divider(modifier = Modifier.padding(vertical = 2.dp))

        // Keyboard fills remaining space
        CompactKeyboardLayout(
            modifier = Modifier.fillMaxWidth().weight(1f),
            modifierState = modifierState, ledState = ledState,
            onKeyPress = { if (isConnected) onKeyPress(it) },
            onKeyRelease = { if (isConnected) onKeyRelease(it) },
            onModifierToggle = { if (isConnected) onModifierToggle(it) }
        )

        // Release all
        ReleaseAllKeysButton(isConnected, onReleaseAllKeys, compact = isSmall)
    }
}

// ═══════════════════════════════════════════════════════════════════
// Landscape
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyboardLandscape(
    isConnected: Boolean, isSmall: Boolean, edgePad: androidx.compose.ui.unit.Dp,
    textToType: String, onTextChange: (String) -> Unit,
    showFunctionKeys: Boolean, onToggleFnKeys: () -> Unit,
    modifierState: ModifierState, ledState: KeyboardLedState,
    onKeyPress: (Int) -> Unit, onKeyRelease: (Int) -> Unit,
    onModifierToggle: (String) -> Unit, onTypeText: (String) -> Unit,
    onReleaseAllKeys: () -> Unit, onTypeKey: (Int) -> Unit,
    onTypeKeyWithModifiers: (Int, ModifierState) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxSize().padding(edgePad),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Left panel — proportional width (30% of screen, min 160, max 240)
        val config = LocalConfiguration.current
        val panelWidth = (config.screenWidthDp * 0.28f).coerceIn(150f, 240f).dp

        Column(
            modifier = Modifier.width(panelWidth).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ConnectionStatusBar(isConnected, if (isConnected) "Ready" else "Disconnected")
            TextInputField(textToType, onTextChange, isConnected, onTypeText)
            ShortcutsColumn(isConnected, onTypeKeyWithModifiers)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                QuickActionChip("Esc", isConnected) { onTypeKey(HidKeyCodes.KEY_ESCAPE) }
                QuickActionChip("Tab", isConnected) { onTypeKey(HidKeyCodes.KEY_TAB) }
                QuickActionChip("Del", isConnected) { onTypeKey(HidKeyCodes.KEY_DELETE) }
            }
            ReleaseAllKeysButton(isConnected, onReleaseAllKeys, compact = true)
        }

        // Right panel — keyboard fills the rest
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (showFunctionKeys) FunctionKeyRow(isConnected, onTypeKey)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilterChip(
                    selected = showFunctionKeys, onClick = onToggleFnKeys,
                    label = { Text("F1-F12") },
                    leadingIcon = if (showFunctionKeys) {
                        { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) }
                    } else null
                )
            }

            CompactKeyboardLayout(
                modifier = Modifier.fillMaxWidth().weight(1f),
                modifierState = modifierState, ledState = ledState, isLandscape = true,
                onKeyPress = { if (isConnected) onKeyPress(it) },
                onKeyRelease = { if (isConnected) onKeyRelease(it) },
                onModifierToggle = { if (isConnected) onModifierToggle(it) }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Shared building-block composables
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun TextInputField(
    textToType: String, onTextChange: (String) -> Unit,
    isConnected: Boolean, onTypeText: (String) -> Unit
) {
    OutlinedTextField(
        value = textToType, onValueChange = onTextChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Type text") },
        placeholder = { Text("Enter text to send...") },
        enabled = isConnected, singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = {
            if (textToType.isNotEmpty()) { onTypeText(textToType); onTextChange("") }
        }),
        trailingIcon = {
            Row {
                if (textToType.isNotEmpty()) {
                    IconButton(onClick = { onTypeText(textToType); onTextChange("") }) {
                        Icon(Icons.Default.Send, "Send")
                    }
                }
                IconButton(onClick = { onTextChange("") }) { Icon(Icons.Default.Clear, "Clear") }
            }
        }
    )
}

@Composable
private fun ReleaseAllKeysButton(isConnected: Boolean, onClick: () -> Unit, compact: Boolean = false) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().then(if (compact) Modifier.height(36.dp) else Modifier),
        enabled = isConnected,
        contentPadding = if (compact) PaddingValues(horizontal = 12.dp, vertical = 4.dp) else ButtonDefaults.ContentPadding,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
    ) {
        Icon(Icons.Default.Clear, null, Modifier.size(if (compact) 16.dp else 18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Release All Keys", style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickActionRow(
    isConnected: Boolean, showFn: Boolean, onToggleFn: () -> Unit, onTypeKey: (Int) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        QuickActionChip("Esc", isConnected) { onTypeKey(HidKeyCodes.KEY_ESCAPE) }
        QuickActionChip("Tab", isConnected) { onTypeKey(HidKeyCodes.KEY_TAB) }
        QuickActionChip("Del", isConnected) { onTypeKey(HidKeyCodes.KEY_DELETE) }
        Spacer(Modifier.weight(1f))
        FilterChip(
            selected = showFn, onClick = onToggleFn,
            label = { Text("F1-F12") },
            leadingIcon = if (showFn) { { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) } } else null
        )
    }
}

@Composable
fun ConnectionStatusBar(isConnected: Boolean, statusText: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isConnected) Connected.copy(alpha = 0.1f) else Disconnected.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                if (isConnected) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                null, tint = if (isConnected) Connected else Disconnected,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(statusText, style = MaterialTheme.typography.bodySmall,
                color = if (isConnected) Connected else Disconnected)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickActionChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) }, enabled = enabled)
}

@Composable
private fun FunctionKeyRow(enabled: Boolean, onTypeKey: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        (1..12).forEach { i ->
            FilledTonalButton(
                onClick = { onTypeKey(HidKeyCodes.KEY_F1 + i - 1) },
                modifier = Modifier.weight(1f),
                enabled = enabled,
                contentPadding = PaddingValues(2.dp)
            ) { Text("F$i", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun ShortcutsRow(enabled: Boolean, onTypeKeyWithMods: (Int, ModifierState) -> Unit) {
    val ctrl = ModifierState(leftCtrl = true)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ShortcutButton(Modifier.weight(1f), "Ctrl+C", "Copy", enabled) { onTypeKeyWithMods(HidKeyCodes.KEY_C, ctrl) }
        ShortcutButton(Modifier.weight(1f), "Ctrl+V", "Paste", enabled) { onTypeKeyWithMods(HidKeyCodes.KEY_V, ctrl) }
        ShortcutButton(Modifier.weight(1f), "Ctrl+Z", "Undo", enabled) { onTypeKeyWithMods(HidKeyCodes.KEY_Z, ctrl) }
        ShortcutButton(Modifier.weight(1f), "Ctrl+A", "Select", enabled) { onTypeKeyWithMods(HidKeyCodes.KEY_A, ctrl) }
    }
}

@Composable
private fun ShortcutsColumn(enabled: Boolean, onTypeKeyWithMods: (Int, ModifierState) -> Unit) {
    val ctrl = ModifierState(leftCtrl = true)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            ShortcutButton(Modifier.weight(1f), "Ctrl+C", "Copy", enabled) { onTypeKeyWithMods(HidKeyCodes.KEY_C, ctrl) }
            ShortcutButton(Modifier.weight(1f), "Ctrl+V", "Paste", enabled) { onTypeKeyWithMods(HidKeyCodes.KEY_V, ctrl) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            ShortcutButton(Modifier.weight(1f), "Ctrl+Z", "Undo", enabled) { onTypeKeyWithMods(HidKeyCodes.KEY_Z, ctrl) }
            ShortcutButton(Modifier.weight(1f), "Ctrl+A", "Select", enabled) { onTypeKeyWithMods(HidKeyCodes.KEY_A, ctrl) }
        }
    }
}

@Composable
private fun ShortcutButton(
    modifier: Modifier = Modifier, label: String, sublabel: String,
    enabled: Boolean, onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick, modifier = modifier, enabled = enabled,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(sublabel, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}