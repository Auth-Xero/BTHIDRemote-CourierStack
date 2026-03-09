package com.courierstack.hidremote.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.courierstack.hidremote.data.ConnectionState
import com.courierstack.hidremote.ui.theme.Connected
import com.courierstack.hidremote.ui.theme.Disconnected
import kotlin.math.roundToInt
import kotlin.math.sqrt

// D-pad hat switch values (per HID spec)
private const val DPAD_N = 0
private const val DPAD_NE = 1
private const val DPAD_E = 2
private const val DPAD_SE = 3
private const val DPAD_S = 4
private const val DPAD_SW = 5
private const val DPAD_W = 6
private const val DPAD_NW = 7
private const val DPAD_CENTER = 8 // Null state (no direction)

// Button indices (bit positions in the 16-bit button mask)
private const val BTN_A = 0
private const val BTN_B = 1
private const val BTN_X = 2
private const val BTN_Y = 3
private const val BTN_LB = 4
private const val BTN_RB = 5
private const val BTN_LT_BTN = 6
private const val BTN_RT_BTN = 7
private const val BTN_SELECT = 8
private const val BTN_START = 9
private const val BTN_L3 = 10
private const val BTN_R3 = 11

/**
 * Gamepad input screen with virtual joysticks, d-pad, face buttons, and triggers.
 * In landscape, uses a natural controller layout with joysticks on the sides.
 */
@Composable
fun GamepadScreen(
    connectionState: ConnectionState,
    onSendGamepad: (buttons: Int, lx: Int, ly: Int, rx: Int, ry: Int, lt: Int, rt: Int, dpad: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected = connectionState == ConnectionState.CONNECTED
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Mutable state for all gamepad inputs
    var buttons by remember { mutableStateOf(0) }
    var leftX by remember { mutableStateOf(128) }
    var leftY by remember { mutableStateOf(128) }
    var rightX by remember { mutableStateOf(128) }
    var rightY by remember { mutableStateOf(128) }
    var leftTrigger by remember { mutableStateOf(0) }
    var rightTrigger by remember { mutableStateOf(0) }
    var dpad by remember { mutableStateOf(DPAD_CENTER) }

    // Send report whenever any value changes
    LaunchedEffect(buttons, leftX, leftY, rightX, rightY, leftTrigger, rightTrigger, dpad) {
        if (isConnected) {
            onSendGamepad(buttons, leftX, leftY, rightX, rightY, leftTrigger, rightTrigger, dpad)
        }
    }

    fun setButton(bit: Int, pressed: Boolean) {
        buttons = if (pressed) buttons or (1 shl bit) else buttons and (1 shl bit).inv()
    }

    if (isLandscape) {
        GamepadLandscape(
            isConnected = isConnected,
            onSetButton = ::setButton,
            onLeftStick = { x, y -> leftX = x; leftY = y },
            onRightStick = { x, y -> rightX = x; rightY = y },
            onLeftTrigger = { leftTrigger = it },
            onRightTrigger = { rightTrigger = it },
            onDpad = { dpad = it },
            modifier = modifier
        )
    } else {
        GamepadPortrait(
            isConnected = isConnected,
            onSetButton = ::setButton,
            onLeftStick = { x, y -> leftX = x; leftY = y },
            onRightStick = { x, y -> rightX = x; rightY = y },
            onLeftTrigger = { leftTrigger = it },
            onRightTrigger = { rightTrigger = it },
            onDpad = { dpad = it },
            modifier = modifier
        )
    }
}

@Composable
private fun GamepadLandscape(
    isConnected: Boolean,
    onSetButton: (Int, Boolean) -> Unit,
    onLeftStick: (Int, Int) -> Unit,
    onRightStick: (Int, Int) -> Unit,
    onLeftTrigger: (Int) -> Unit,
    onRightTrigger: (Int) -> Unit,
    onDpad: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val config = LocalConfiguration.current
    val screenH = config.screenHeightDp
    val screenW = config.screenWidthDp
    val isSmall = screenH < 400 || screenW < 600

    val joystickSize = ((screenH * 0.4f).coerceIn(80f, 120f)).dp
    val dpadBtnSize = if (isSmall) 36.dp else 44.dp
    val shoulderWidth = if (isSmall) 56.dp else 72.dp

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Top row: LB / LT / Select+Start / RB / RT
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GamepadHoldButton(label = "LB", enabled = isConnected, modifier = Modifier.width(shoulderWidth),
                    onPress = { onSetButton(BTN_LB, true) }, onRelease = { onSetButton(BTN_LB, false) })
                GamepadHoldButton(label = "LT", enabled = isConnected, modifier = Modifier.width(shoulderWidth),
                    onPress = { onSetButton(BTN_LT_BTN, true); onLeftTrigger(255) },
                    onRelease = { onSetButton(BTN_LT_BTN, false); onLeftTrigger(0) })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GamepadHoldButton(label = "Select", enabled = isConnected, small = true,
                    onPress = { onSetButton(BTN_SELECT, true) }, onRelease = { onSetButton(BTN_SELECT, false) })
                GamepadHoldButton(label = "Start", enabled = isConnected, small = true,
                    onPress = { onSetButton(BTN_START, true) }, onRelease = { onSetButton(BTN_START, false) })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GamepadHoldButton(label = "RT", enabled = isConnected, modifier = Modifier.width(shoulderWidth),
                    onPress = { onSetButton(BTN_RT_BTN, true); onRightTrigger(255) },
                    onRelease = { onSetButton(BTN_RT_BTN, false); onRightTrigger(0) })
                GamepadHoldButton(label = "RB", enabled = isConnected, modifier = Modifier.width(shoulderWidth),
                    onPress = { onSetButton(BTN_RB, true) }, onRelease = { onSetButton(BTN_RB, false) })
            }
        }

        // Main area: Left Stick | D-pad | Face Buttons | Right Stick
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left stick
            VirtualJoystick(
                label = "L", enabled = isConnected, size = joystickSize,
                onPositionChange = onLeftStick,
                onPress = { onSetButton(BTN_L3, true) },
                onRelease = { onSetButton(BTN_L3, false) }
            )

            // D-pad
            DPad(enabled = isConnected, btnSize = dpadBtnSize, onDirectionChange = onDpad)

            // Face buttons
            FaceButtons(
                enabled = isConnected, btnSize = dpadBtnSize,
                onButtonPress = { onSetButton(it, true) },
                onButtonRelease = { onSetButton(it, false) }
            )

            // Right stick
            VirtualJoystick(
                label = "R", enabled = isConnected, size = joystickSize,
                onPositionChange = onRightStick,
                onPress = { onSetButton(BTN_R3, true) },
                onRelease = { onSetButton(BTN_R3, false) }
            )
        }
    }
}

@Composable
private fun GamepadPortrait(
    isConnected: Boolean,
    onSetButton: (Int, Boolean) -> Unit,
    onLeftStick: (Int, Int) -> Unit,
    onRightStick: (Int, Int) -> Unit,
    onLeftTrigger: (Int) -> Unit,
    onRightTrigger: (Int) -> Unit,
    onDpad: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val config = LocalConfiguration.current
    val screenW = config.screenWidthDp
    val screenH = config.screenHeightDp
    val isSmall = screenW < 360 || screenH < 640

    // Scale sizes to screen
    val edgePad = if (isSmall) 4.dp else 8.dp
    val gap = if (isSmall) 4.dp else 8.dp
    val dpadBtnSize = if (isSmall) 38.dp else 52.dp
    val joystickSize = ((screenW * 0.35f).coerceIn(90f, 140f)).dp

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(edgePad),
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        // Connection status bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = if (isConnected) Connected.copy(alpha = 0.1f) else Disconnected.copy(alpha = 0.1f),
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (isConnected) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                    contentDescription = null,
                    tint = if (isConnected) Connected else Disconnected,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isConnected) "Gamepad Ready" else "Not Connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isConnected) Connected else Disconnected
                )
            }
        }

        // Shoulder buttons: LB / RB
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            GamepadHoldButton(label = "LB", enabled = isConnected, modifier = Modifier.weight(1f).padding(end = 4.dp),
                onPress = { onSetButton(BTN_LB, true) }, onRelease = { onSetButton(BTN_LB, false) })
            GamepadHoldButton(label = "RB", enabled = isConnected, modifier = Modifier.weight(1f).padding(start = 4.dp),
                onPress = { onSetButton(BTN_RB, true) }, onRelease = { onSetButton(BTN_RB, false) })
        }

        // Triggers: LT / RT
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            GamepadHoldButton(label = "LT", enabled = isConnected, modifier = Modifier.weight(1f).padding(end = 4.dp),
                onPress = { onSetButton(BTN_LT_BTN, true); onLeftTrigger(255) },
                onRelease = { onSetButton(BTN_LT_BTN, false); onLeftTrigger(0) })
            GamepadHoldButton(label = "RT", enabled = isConnected, modifier = Modifier.weight(1f).padding(start = 4.dp),
                onPress = { onSetButton(BTN_RT_BTN, true); onRightTrigger(255) },
                onRelease = { onSetButton(BTN_RT_BTN, false); onRightTrigger(0) })
        }

        // Main area: D-pad + face buttons
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DPad(enabled = isConnected, btnSize = dpadBtnSize, modifier = Modifier.weight(1f), onDirectionChange = onDpad)

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap)) {
                GamepadHoldButton(label = "Select", enabled = isConnected, small = true,
                    onPress = { onSetButton(BTN_SELECT, true) }, onRelease = { onSetButton(BTN_SELECT, false) })
                GamepadHoldButton(label = "Start", enabled = isConnected, small = true,
                    onPress = { onSetButton(BTN_START, true) }, onRelease = { onSetButton(BTN_START, false) })
            }

            FaceButtons(
                enabled = isConnected, btnSize = dpadBtnSize, modifier = Modifier.weight(1f),
                onButtonPress = { onSetButton(it, true) },
                onButtonRelease = { onSetButton(it, false) }
            )
        }

        // Joysticks
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            VirtualJoystick(label = "L", enabled = isConnected, size = joystickSize,
                onPositionChange = onLeftStick,
                onPress = { onSetButton(BTN_L3, true) }, onRelease = { onSetButton(BTN_L3, false) })
            VirtualJoystick(label = "R", enabled = isConnected, size = joystickSize,
                onPositionChange = onRightStick,
                onPress = { onSetButton(BTN_R3, true) }, onRelease = { onSetButton(BTN_R3, false) })
        }
    }
}

/**
 * Virtual joystick - circular touch area that maps finger position to 0-255 axis values.
 */
@Composable
private fun VirtualJoystick(
    label: String,
    enabled: Boolean,
    size: Dp,
    onPositionChange: (x: Int, y: Int) -> Unit,
    onPress: () -> Unit = {},
    onRelease: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val sizePx = with(LocalDensity.current) { size.toPx() }
    val radius = sizePx / 2f
    var thumbOffset by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    if (enabled) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
                .border(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), CircleShape)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = { isDragging = true; onPress() },
                        onDragEnd = {
                            isDragging = false; thumbOffset = Offset.Zero
                            onPositionChange(128, 128); onRelease()
                        },
                        onDragCancel = {
                            isDragging = false; thumbOffset = Offset.Zero
                            onPositionChange(128, 128); onRelease()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val newOffset = thumbOffset + dragAmount
                            val dist = sqrt(newOffset.x * newOffset.x + newOffset.y * newOffset.y)
                            val maxDist = radius * 0.8f
                            thumbOffset = if (dist > maxDist) newOffset * (maxDist / dist) else newOffset
                            val x = ((thumbOffset.x / maxDist) * 127 + 128).toInt().coerceIn(0, 255)
                            val y = ((thumbOffset.y / maxDist) * 127 + 128).toInt().coerceIn(0, 255)
                            onPositionChange(x, y)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (!isDragging) {
                Text(label, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffset.x.roundToInt(), thumbOffset.y.roundToInt()) }
                .size(size / 3)
                .clip(CircleShape)
                .background(
                    if (isDragging) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
        )
    }
}

@Composable
private fun DPad(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    btnSize: Dp = 52.dp,
    onDirectionChange: (Int) -> Unit
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DPadButton(label = "\u25B2", enabled = enabled, size = btnSize,
                onPress = { onDirectionChange(DPAD_N) }, onRelease = { onDirectionChange(DPAD_CENTER) })
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                DPadButton(label = "\u25C4", enabled = enabled, size = btnSize,
                    onPress = { onDirectionChange(DPAD_W) }, onRelease = { onDirectionChange(DPAD_CENTER) })
                Box(
                    modifier = Modifier.size(btnSize).clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) { Text("\u25CF", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f), fontSize = 12.sp) }
                DPadButton(label = "\u25BA", enabled = enabled, size = btnSize,
                    onPress = { onDirectionChange(DPAD_E) }, onRelease = { onDirectionChange(DPAD_CENTER) })
            }
            DPadButton(label = "\u25BC", enabled = enabled, size = btnSize,
                onPress = { onDirectionChange(DPAD_S) }, onRelease = { onDirectionChange(DPAD_CENTER) })
        }
    }
}

@Composable
private fun DPadButton(label: String, enabled: Boolean, size: Dp, onPress: () -> Unit, onRelease: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(4.dp)).background(
            when {
                !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                pressed -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ).pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val anyDown = event.changes.any { it.pressed }
                    if (anyDown && !pressed) { pressed = true; onPress() }
                    else if (!anyDown && pressed) { pressed = false; onRelease() }
                    event.changes.forEach { it.consume() }
                }
            }
        },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = (size.value * 0.35f).coerceIn(12f, 18f).sp,
            color = if (pressed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FaceButtons(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    btnSize: Dp = 52.dp,
    onButtonPress: (Int) -> Unit,
    onButtonRelease: (Int) -> Unit
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FaceButton(label = "Y", color = Color(0xFFE8B230), enabled = enabled, size = btnSize,
                onPress = { onButtonPress(BTN_Y) }, onRelease = { onButtonRelease(BTN_Y) })
            Row(horizontalArrangement = Arrangement.spacedBy(btnSize + 4.dp)) {
                FaceButton(label = "X", color = Color(0xFF3B82F6), enabled = enabled, size = btnSize,
                    onPress = { onButtonPress(BTN_X) }, onRelease = { onButtonRelease(BTN_X) })
                FaceButton(label = "B", color = Color(0xFFEF4444), enabled = enabled, size = btnSize,
                    onPress = { onButtonPress(BTN_B) }, onRelease = { onButtonRelease(BTN_B) })
            }
            FaceButton(label = "A", color = Color(0xFF22C55E), enabled = enabled, size = btnSize,
                onPress = { onButtonPress(BTN_A) }, onRelease = { onButtonRelease(BTN_A) })
        }
    }
}

@Composable
private fun FaceButton(label: String, color: Color, enabled: Boolean, size: Dp, onPress: () -> Unit, onRelease: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(
            when {
                !enabled -> color.copy(alpha = 0.2f)
                pressed -> color
                else -> color.copy(alpha = 0.6f)
            }
        ).pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val anyDown = event.changes.any { it.pressed }
                    if (anyDown && !pressed) { pressed = true; onPress() }
                    else if (!anyDown && pressed) { pressed = false; onRelease() }
                    event.changes.forEach { it.consume() }
                }
            }
        },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.3f).coerceIn(10f, 16f).sp, color = Color.White, textAlign = TextAlign.Center)
    }
}

@Composable
private fun GamepadHoldButton(
    label: String, enabled: Boolean, modifier: Modifier = Modifier, small: Boolean = false,
    onPress: () -> Unit, onRelease: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val screenW = LocalConfiguration.current.screenWidthDp
    val isSmall = screenW < 360
    val btnHeight = if (small) (if (isSmall) 30.dp else 36.dp) else (if (isSmall) 36.dp else 44.dp)
    val smallWidth = if (isSmall) 56.dp else 72.dp

    Button(
        onClick = { },
        modifier = modifier
            .then(if (small) Modifier.width(smallWidth).height(btnHeight) else Modifier.height(btnHeight))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val anyDown = event.changes.any { it.pressed }
                        if (anyDown && !pressed) { pressed = true; onPress() }
                        else if (!anyDown && pressed) { pressed = false; onRelease() }
                    }
                }
            },
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (pressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (pressed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label,
            style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold)
    }
}