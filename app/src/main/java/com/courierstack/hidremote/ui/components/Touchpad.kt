package com.courierstack.hidremote.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.courierstack.hidremote.data.MouseButtons
import com.courierstack.hidremote.ui.theme.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Modern touchpad component — sizes scale to available space.
 * Mouse buttons take ~15% of the height; touchpad fills the rest.
 */
@Composable
fun Touchpad(
    modifier: Modifier = Modifier,
    sensitivity: Float = 1f,
    tapToClick: Boolean = true,
    naturalScrolling: Boolean = false,
    onMove: (dx: Float, dy: Float) -> Unit,
    onScroll: (amount: Float) -> Unit,
    onTap: () -> Unit,
    onButtonPress: (Int) -> Unit,
    onButtonRelease: (Int) -> Unit
) {
    var leftPressed by remember { mutableStateOf(false) }
    var rightPressed by remember { mutableStateOf(false) }

    val screenHeight = LocalConfiguration.current.screenHeightDp
    val buttonGap = if (screenHeight < 600) 6.dp else 10.dp

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(buttonGap)
    ) {
        // Touchpad surface — takes most of the space
        TouchpadSurface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            sensitivity = sensitivity,
            tapToClick = tapToClick,
            naturalScrolling = naturalScrolling,
            onMove = onMove, onScroll = onScroll, onTap = onTap
        )

        // Mouse buttons — use weight instead of fixed height
        Row(
            modifier = Modifier.fillMaxWidth().weight(0.15f),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MouseButton(
                modifier = Modifier.weight(1f),
                isPressed = leftPressed, isPrimary = true,
                onPress = { leftPressed = true; onButtonPress(MouseButtons.LEFT) },
                onRelease = { leftPressed = false; onButtonRelease(MouseButtons.LEFT) }
            )
            MouseButton(
                modifier = Modifier.weight(1f),
                isPressed = rightPressed, isPrimary = false,
                onPress = { rightPressed = true; onButtonPress(MouseButtons.RIGHT) },
                onRelease = { rightPressed = false; onButtonRelease(MouseButtons.RIGHT) }
            )
        }
    }
}

@Composable
private fun TouchpadSurface(
    modifier: Modifier = Modifier,
    sensitivity: Float,
    tapToClick: Boolean,
    naturalScrolling: Boolean,
    onMove: (dx: Float, dy: Float) -> Unit,
    onScroll: (amount: Float) -> Unit,
    onTap: () -> Unit
) {
    var lastPosition by remember { mutableStateOf<Offset?>(null) }
    var touchCount by remember { mutableIntStateOf(0) }
    var lastTwoFingerY by remember { mutableStateOf(0f) }
    var isTwoFingerScroll by remember { mutableStateOf(false) }
    var tapStartTime by remember { mutableLongStateOf(0L) }
    var tapStartPosition by remember { mutableStateOf(Offset.Zero) }
    var touchPosition by remember { mutableStateOf<Offset?>(null) }
    var isActive by remember { mutableStateOf(false) }

    val scrollMultiplier = if (naturalScrolling) -1f else 1f
    val touchAlpha by animateFloatAsState(
        targetValue = if (isActive) 0.3f else 0f,
        animationSpec = spring(), label = "touchAlpha"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(TouchpadSurface, TouchpadSurface.copy(alpha = 0.95f))
                )
            )
            .border(1.5.dp, TouchpadBorder, RoundedCornerShape(12.dp))
            .shadow(3.dp, RoundedCornerShape(12.dp), clip = false)
            .pointerInput(sensitivity, tapToClick, naturalScrolling) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    touchCount = 1
                    lastPosition = down.position
                    touchPosition = down.position
                    tapStartTime = System.currentTimeMillis()
                    tapStartPosition = down.position
                    isActive = true
                    var movedDistance = 0f

                    do {
                        val event = awaitPointerEvent()
                        val changes = event.changes
                        touchCount = changes.count { it.pressed }

                        if (touchCount == 1) {
                            val change = changes.firstOrNull { it.pressed }
                            if (change != null && lastPosition != null) {
                                val delta = change.position - lastPosition!!
                                val distance = sqrt(delta.x * delta.x + delta.y * delta.y)
                                movedDistance += distance
                                if (movedDistance > 3f) {
                                    onMove(delta.x * sensitivity, delta.y * sensitivity)
                                }
                                lastPosition = change.position
                                touchPosition = change.position
                            }
                            isTwoFingerScroll = false
                        } else if (touchCount >= 2) {
                            val avgY = changes.filter { it.pressed }.map { it.position.y }.average().toFloat()
                            if (isTwoFingerScroll) {
                                val scrollDelta = (avgY - lastTwoFingerY) * scrollMultiplier * 0.08f
                                if (abs(scrollDelta) > 0.3f) onScroll(scrollDelta)
                            }
                            lastTwoFingerY = avgY
                            isTwoFingerScroll = true
                        }
                        changes.forEach { it.consume() }
                    } while (changes.any { it.pressed })

                    val tapDuration = System.currentTimeMillis() - tapStartTime
                    if (tapToClick && tapDuration < 200 && movedDistance < 15f && touchCount <= 1) {
                        onTap()
                    }
                    lastPosition = null; touchPosition = null
                    touchCount = 0; isTwoFingerScroll = false; isActive = false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        touchPosition?.let { pos ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(Color.Gray.copy(alpha = touchAlpha), radius = 36f, center = pos)
            }
        }
        if (!isActive) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(Icons.Default.TouchApp, null, Modifier.size(28.dp), tint = Color.Gray.copy(alpha = 0.3f))
                Text("Move cursor", color = Color.Gray.copy(alpha = 0.4f), fontSize = 12.sp)
                Text("Two fingers to scroll", color = Color.Gray.copy(alpha = 0.3f), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun MouseButton(
    modifier: Modifier = Modifier,
    isPressed: Boolean,
    isPrimary: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val bg by animateColorAsState(
        when {
            isPressed -> KeyPressed
            isPrimary -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }, label = "btnBg"
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(
                width = if (isPressed) 2.dp else 1.dp,
                color = if (isPressed) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                shape = RoundedCornerShape(10.dp)
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onPress()
                    do { val event = awaitPointerEvent() } while (event.changes.any { it.pressed })
                    onRelease()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                if (isPrimary) Icons.Default.TouchApp else Icons.Default.Menu,
                null, Modifier.size(20.dp),
                tint = if (isPrimary) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (isPrimary) "Left" else "Right", fontSize = 11.sp,
                color = if (isPrimary) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Scroll wheel — width scales with screen size.
 */
@Composable
fun ScrollWheel(
    modifier: Modifier = Modifier,
    naturalScrolling: Boolean = false,
    onScroll: (amount: Float) -> Unit
) {
    var lastY by remember { mutableStateOf(0f) }
    var isActive by remember { mutableStateOf(false) }
    val scrollMultiplier = if (naturalScrolling) -1f else 1f

    val screenWidth = LocalConfiguration.current.screenWidthDp
    val wheelWidth = (screenWidth * 0.12f).coerceIn(40f, 56f).dp

    val bg by animateColorAsState(
        if (isActive) KeyPressed else KeyBackground, label = "scrollBg"
    )

    Box(
        modifier = modifier
            .width(wheelWidth)
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .pointerInput(naturalScrolling) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    lastY = down.position.y
                    isActive = true
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull()
                        if (change != null && change.pressed) {
                            val deltaY = (change.position.y - lastY) * scrollMultiplier * 0.04f
                            if (abs(deltaY) > 0.08f) onScroll(deltaY)
                            lastY = change.position.y
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    isActive = false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(16.dp), tint = Color.Gray)
            Box(
                Modifier.width(3.dp).height(30.dp).clip(RoundedCornerShape(1.5.dp))
                    .background(Color.Gray.copy(alpha = 0.3f))
            )
            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp), tint = Color.Gray)
        }
    }
}