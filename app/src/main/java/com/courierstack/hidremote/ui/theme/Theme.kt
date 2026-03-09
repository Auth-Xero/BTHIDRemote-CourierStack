package com.courierstack.hidremote.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Colors
val Primary = Color(0xFF1976D2)
val PrimaryDark = Color(0xFF1565C0)
val PrimaryLight = Color(0xFF42A5F5)
val Secondary = Color(0xFFFF6F00)
val SecondaryDark = Color(0xFFE65100)
val SecondaryLight = Color(0xFFFFA726)

val BackgroundLight = Color(0xFFFAFAFA)
val BackgroundDark = Color(0xFF121212)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceDark = Color(0xFF1E1E1E)

val TextPrimaryLight = Color(0xFF212121)
val TextPrimaryDark = Color(0xFFFFFFFF)
val TextSecondaryLight = Color(0xFF757575)
val TextSecondaryDark = Color(0xFFB0B0B0)

val Connected = Color(0xFF4CAF50)
val Disconnected = Color(0xFFF44336)
val Connecting = Color(0xFFFFC107)

val KeyBackground = Color(0xFFE0E0E0)
val KeyBackgroundDark = Color(0xFF424242)
val KeyPressed = Color(0xFFBDBDBD)
val KeyPressedDark = Color(0xFF616161)
val KeyModifierActive = Color(0xFF2196F3)

val TouchpadSurface = Color(0xFFF5F5F5)
val TouchpadSurfaceDark = Color(0xFF2C2C2C)
val TouchpadBorder = Color(0xFFBDBDBD)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryLight,
    onPrimary = Color.Black,
    primaryContainer = PrimaryDark,
    onPrimaryContainer = Color.White,
    secondary = SecondaryLight,
    onSecondary = Color.Black,
    secondaryContainer = SecondaryDark,
    onSecondaryContainer = Color.White,
    tertiary = Connected,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = TextSecondaryDark,
    error = Color(0xFFCF6679),
    onError = Color.Black
)

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryLight,
    onPrimaryContainer = Color.Black,
    secondary = Secondary,
    onSecondary = Color.White,
    secondaryContainer = SecondaryLight,
    onSecondaryContainer = Color.Black,
    tertiary = Connected,
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = TextSecondaryLight,
    error = Color(0xFFB00020),
    onError = Color.White
)

@Composable
fun BTHidRemoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

val Typography = Typography()
