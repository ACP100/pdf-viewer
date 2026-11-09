package com.pdfviewer.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.pdfviewer.app.data.Settings

val Brand = Color(0xFFC62828)

private val Light = lightColorScheme(
    primary = Color(0xFFC62828),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF775652),
    secondaryContainer = Color(0xFFFFDAD6),
    tertiary = Color(0xFF1565C0),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFF5DDDA),
    background = Color(0xFFFFFBFF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB4AB),
    onPrimary = Color(0xFF690005),
    primaryContainer = Color(0xFF93000A),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFE7BDB7),
    tertiary = Color(0xFF90CAF9),
    surface = Color(0xFF1A1111),
    background = Color(0xFF1A1111),
)

@Composable
fun isAppDark(): Boolean = when (Settings.appTheme) {
    1 -> false
    2 -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun AppTheme(dark: Boolean = isAppDark(), dynamic: Boolean = false, content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (dynamic && Build.VERSION.SDK_INT >= 31) {
        val c = LocalContext.current
        if (dark) dynamicDarkColorScheme(c) else dynamicLightColorScheme(c)
    } else if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme, content = content)
}
