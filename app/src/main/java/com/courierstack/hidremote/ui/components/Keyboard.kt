package com.courierstack.hidremote.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.courierstack.hidremote.data.HidKeyCodes
import com.courierstack.hidremote.data.KeyboardLedState
import com.courierstack.hidremote.data.ModifierState
import com.courierstack.hidremote.ui.theme.*
import kotlin.math.min

/**
 * Key data class for keyboard layout.
 */
data class KeyData(
    val keyCode: Int,
    val label: String,
    val shiftLabel: String? = null,
    val width: Float = 1f,
    val isModifier: Boolean = false,
    val modifierName: String? = null,
    val icon: ImageVector? = null
)

/**
 * Compact keyboard layout — all rows use weight-based heights so the
 * keyboard scales to fill whatever space the parent provides.
 *
 * IMPORTANT: The parent must give this composable a bounded height
 * (e.g. Modifier.weight(1f) inside a Column, or a fixed height).
 */
@Composable
fun CompactKeyboardLayout(
    modifier: Modifier = Modifier,
    modifierState: ModifierState,
    ledState: KeyboardLedState,
    isLandscape: Boolean = false,
    onKeyPress: (Int) -> Unit,
    onKeyRelease: (Int) -> Unit,
    onModifierToggle: (String) -> Unit
) {
    val isShift = modifierState.leftShift || modifierState.rightShift
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // Tighter spacing on narrow screens
    val keyGap = if (screenWidthDp < 360) 1.dp else 2.dp
    // Scale font sizes for narrow screens
    val fontScale = if (screenWidthDp < 360) 0.85f else 1f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 3.dp, vertical = 1.dp),
        verticalArrangement = Arrangement.spacedBy(keyGap)
    ) {
        CompactKeyRow(
            keys = compactNumberRow, isShift = isShift, modifierState = modifierState,
            fontScale = fontScale, onKeyPress = onKeyPress, onKeyRelease = onKeyRelease,
            onModifierToggle = onModifierToggle, modifier = Modifier.weight(1f)
        )
        CompactKeyRow(
            keys = compactQwertyRow, isShift = isShift, modifierState = modifierState,
            fontScale = fontScale, onKeyPress = onKeyPress, onKeyRelease = onKeyRelease,
            onModifierToggle = onModifierToggle, modifier = Modifier.weight(1f)
        )
        CompactKeyRow(
            keys = compactAsdfRow, isShift = isShift, modifierState = modifierState,
            fontScale = fontScale, onKeyPress = onKeyPress, onKeyRelease = onKeyRelease,
            onModifierToggle = onModifierToggle, modifier = Modifier.weight(1f)
        )
        CompactKeyRow(
            keys = compactZxcvRow, isShift = isShift, modifierState = modifierState,
            fontScale = fontScale, onKeyPress = onKeyPress, onKeyRelease = onKeyRelease,
            onModifierToggle = onModifierToggle, modifier = Modifier.weight(1f)
        )
        CompactKeyRow(
            keys = compactBottomRow, isShift = isShift, modifierState = modifierState,
            fontScale = fontScale, onKeyPress = onKeyPress, onKeyRelease = onKeyRelease,
            onModifierToggle = onModifierToggle, modifier = Modifier.weight(1f)
        )
        ModifierRow(
            modifierState = modifierState, fontScale = fontScale,
            onKeyPress = onKeyPress, onKeyRelease = onKeyRelease,
            onModifierToggle = onModifierToggle, modifier = Modifier.weight(1f)
        )

        if (!isLandscape) {
            LedIndicators(ledState = ledState, modifier = Modifier.weight(0.45f))
        }
    }
}

@Composable
fun KeyboardLayout(
    modifier: Modifier = Modifier,
    modifierState: ModifierState,
    ledState: KeyboardLedState,
    isLandscape: Boolean = false,
    onKeyPress: (Int) -> Unit,
    onKeyRelease: (Int) -> Unit,
    onModifierToggle: (String) -> Unit
) {
    CompactKeyboardLayout(modifier, modifierState, ledState, isLandscape, onKeyPress, onKeyRelease, onModifierToggle)
}

// ─── Internal rows ────────────────────────────────────────────────

@Composable
private fun CompactKeyRow(
    keys: List<KeyData>,
    isShift: Boolean,
    modifierState: ModifierState,
    fontScale: Float,
    onKeyPress: (Int) -> Unit,
    onKeyRelease: (Int) -> Unit,
    onModifierToggle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        keys.forEach { key ->
            val isActive = when {
                key.modifierName == "shift" -> modifierState.leftShift || modifierState.rightShift
                key.modifierName == "ctrl" -> modifierState.leftCtrl || modifierState.rightCtrl
                key.modifierName == "alt" -> modifierState.leftAlt || modifierState.rightAlt
                key.modifierName == "gui" -> modifierState.leftGui || modifierState.rightGui
                else -> false
            }

            KeyButton(
                keyData = key,
                modifier = Modifier.weight(key.width).fillMaxHeight(),
                isShift = isShift,
                isActive = isActive,
                fontScale = fontScale,
                onPress = {
                    if (key.isModifier && key.modifierName != null) onModifierToggle(key.modifierName)
                    else onKeyPress(key.keyCode)
                },
                onRelease = { if (!key.isModifier) onKeyRelease(key.keyCode) }
            )
        }
    }
}

@Composable
private fun ModifierRow(
    modifierState: ModifierState,
    fontScale: Float,
    onKeyPress: (Int) -> Unit,
    onKeyRelease: (Int) -> Unit,
    onModifierToggle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        ModifierButton("Ctrl", modifierState.leftCtrl, fontScale, Modifier.weight(1f).fillMaxHeight()) { onModifierToggle("ctrl") }
        ModifierButton("\u2318", modifierState.leftGui, fontScale, Modifier.weight(1f).fillMaxHeight()) { onModifierToggle("gui") }
        ModifierButton("Alt", modifierState.leftAlt, fontScale, Modifier.weight(1f).fillMaxHeight()) { onModifierToggle("alt") }
        ArrowKeyCluster(Modifier.weight(2f).fillMaxHeight(), onKeyPress, onKeyRelease)
    }
}

@Composable
private fun ArrowKeyCluster(
    modifier: Modifier = Modifier,
    onKeyPress: (Int) -> Unit,
    onKeyRelease: (Int) -> Unit
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        SmallKeyButton(Icons.Default.KeyboardArrowLeft, Modifier.weight(1f).fillMaxHeight(),
            { onKeyPress(HidKeyCodes.KEY_LEFT_ARROW) }, { onKeyRelease(HidKeyCodes.KEY_LEFT_ARROW) })
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SmallKeyButton(Icons.Default.KeyboardArrowUp, Modifier.fillMaxWidth().weight(1f),
                { onKeyPress(HidKeyCodes.KEY_UP_ARROW) }, { onKeyRelease(HidKeyCodes.KEY_UP_ARROW) })
            SmallKeyButton(Icons.Default.KeyboardArrowDown, Modifier.fillMaxWidth().weight(1f),
                { onKeyPress(HidKeyCodes.KEY_DOWN_ARROW) }, { onKeyRelease(HidKeyCodes.KEY_DOWN_ARROW) })
        }
        SmallKeyButton(Icons.Default.KeyboardArrowRight, Modifier.weight(1f).fillMaxHeight(),
            { onKeyPress(HidKeyCodes.KEY_RIGHT_ARROW) }, { onKeyRelease(HidKeyCodes.KEY_RIGHT_ARROW) })
    }
}

// ─── Single key composables ──────────────────────────────────────

@Composable
private fun KeyButton(
    keyData: KeyData,
    modifier: Modifier = Modifier,
    isShift: Boolean = false,
    isActive: Boolean = false,
    fontScale: Float = 1f,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "ks")
    val bg by animateColorAsState(
        when {
            isActive -> KeyModifierActive; pressed -> KeyPressed
            keyData.isModifier -> KeyBackground.copy(alpha = 0.8f); else -> KeyBackground
        }, label = "kb"
    )
    val textColor = if (isActive) Color.White else Color.Black
    val label = if (isShift && keyData.shiftLabel != null) keyData.shiftLabel else keyData.label

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(5.dp))
            .background(bg)
            .border(1.dp, if (isActive) KeyModifierActive else Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(5.dp))
            .pointerInput(keyData.keyCode, isActive) {
                detectTapGestures(onPress = {
                    pressed = true; onPress(); tryAwaitRelease(); pressed = false; onRelease()
                })
            },
        contentAlignment = Alignment.Center
    ) {
        if (keyData.icon != null) {
            Icon(keyData.icon, keyData.label, tint = textColor, modifier = Modifier.size(16.dp))
        } else {
            Text(
                text = label,
                fontSize = (when {
                    label.length > 3 -> 9f; label.length > 1 -> 11f; else -> 14f
                } * fontScale).sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = textColor, textAlign = TextAlign.Center, maxLines = 1
            )
        }
    }
}

@Composable
private fun ModifierButton(
    label: String, isActive: Boolean, fontScale: Float = 1f,
    modifier: Modifier = Modifier, onClick: () -> Unit
) {
    val bg by animateColorAsState(if (isActive) KeyModifierActive else KeyBackground, label = "mb")
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp)).background(bg)
            .border(if (isActive) 2.dp else 1.dp, if (isActive) KeyModifierActive else Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(5.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = (11f * fontScale).sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            color = if (isActive) Color.White else Color.Black)
    }
}

@Composable
private fun SmallKeyButton(
    icon: ImageVector, modifier: Modifier = Modifier,
    onPress: () -> Unit, onRelease: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (pressed) KeyPressed else KeyBackground)
            .border(1.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true; onPress(); tryAwaitRelease(); pressed = false; onRelease()
                })
            },
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, Modifier.size(14.dp), tint = Color.DarkGray) }
}

// ─── LED indicators ──────────────────────────────────────────────

@Composable
private fun LedIndicators(ledState: KeyboardLedState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LedDot("NUM", ledState.numLock)
        Spacer(Modifier.width(14.dp))
        LedDot("CAPS", ledState.capsLock)
        Spacer(Modifier.width(14.dp))
        LedDot("SCR", ledState.scrollLock)
    }
}

@Composable
private fun LedDot(label: String, isOn: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(3.5.dp))
            .background(if (isOn) Connected else Color.Gray.copy(alpha = 0.3f)))
        Text(label, fontSize = 9.sp,
            fontWeight = if (isOn) FontWeight.Medium else FontWeight.Normal,
            color = if (isOn) Connected else Color.Gray)
    }
}

// ─── Key data ────────────────────────────────────────────────────

private val compactNumberRow = listOf(
    KeyData(HidKeyCodes.KEY_1, "1", "!"), KeyData(HidKeyCodes.KEY_2, "2", "@"),
    KeyData(HidKeyCodes.KEY_3, "3", "#"), KeyData(HidKeyCodes.KEY_4, "4", "$"),
    KeyData(HidKeyCodes.KEY_5, "5", "%"), KeyData(HidKeyCodes.KEY_6, "6", "^"),
    KeyData(HidKeyCodes.KEY_7, "7", "&"), KeyData(HidKeyCodes.KEY_8, "8", "*"),
    KeyData(HidKeyCodes.KEY_9, "9", "("), KeyData(HidKeyCodes.KEY_0, "0", ")")
)
private val compactQwertyRow = listOf(
    KeyData(HidKeyCodes.KEY_Q, "Q"), KeyData(HidKeyCodes.KEY_W, "W"),
    KeyData(HidKeyCodes.KEY_E, "E"), KeyData(HidKeyCodes.KEY_R, "R"),
    KeyData(HidKeyCodes.KEY_T, "T"), KeyData(HidKeyCodes.KEY_Y, "Y"),
    KeyData(HidKeyCodes.KEY_U, "U"), KeyData(HidKeyCodes.KEY_I, "I"),
    KeyData(HidKeyCodes.KEY_O, "O"), KeyData(HidKeyCodes.KEY_P, "P")
)
private val compactAsdfRow = listOf(
    KeyData(HidKeyCodes.KEY_A, "A"), KeyData(HidKeyCodes.KEY_S, "S"),
    KeyData(HidKeyCodes.KEY_D, "D"), KeyData(HidKeyCodes.KEY_F, "F"),
    KeyData(HidKeyCodes.KEY_G, "G"), KeyData(HidKeyCodes.KEY_H, "H"),
    KeyData(HidKeyCodes.KEY_J, "J"), KeyData(HidKeyCodes.KEY_K, "K"),
    KeyData(HidKeyCodes.KEY_L, "L"),
    KeyData(HidKeyCodes.KEY_BACKSPACE, "\u232B", icon = Icons.Default.Backspace)
)
private val compactZxcvRow = listOf(
    KeyData(0, "\u21E7", isModifier = true, modifierName = "shift", width = 1.5f),
    KeyData(HidKeyCodes.KEY_Z, "Z"), KeyData(HidKeyCodes.KEY_X, "X"),
    KeyData(HidKeyCodes.KEY_C, "C"), KeyData(HidKeyCodes.KEY_V, "V"),
    KeyData(HidKeyCodes.KEY_B, "B"), KeyData(HidKeyCodes.KEY_N, "N"),
    KeyData(HidKeyCodes.KEY_M, "M"),
    KeyData(HidKeyCodes.KEY_ENTER, "\u21B5", width = 1.5f)
)
private val compactBottomRow = listOf(
    KeyData(HidKeyCodes.KEY_COMMA, ",", "<"),
    KeyData(HidKeyCodes.KEY_SPACE, "Space", width = 6f),
    KeyData(HidKeyCodes.KEY_PERIOD, ".", ">"),
    KeyData(HidKeyCodes.KEY_SLASH, "/", "?")
)