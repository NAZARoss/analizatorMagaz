package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = DarkBrandPrimary,
    secondary = DarkTextMuted,
    tertiary = DarkLavenderText,
    background = DarkCarbonBg,
    surface = DarkSlateSurface,
    onPrimary = Color(0xFF0C1017),
    onSecondary = Color.White,
    onBackground = DarkTextOffWhite,
    onSurface = DarkTextOffWhite,
    outlineVariant = DarkBorderSoft,
    surfaceVariant = DarkBorderSoft,
    tertiaryContainer = DarkLavenderBg,
    onTertiaryContainer = DarkLavenderText
)

private val LightColorScheme = lightColorScheme(
    primary = BrandBluePrimary,
    secondary = SlateGrayMuted,
    tertiary = LavenderSecondary,
    background = PearlIceBg,
    surface = PearlIceSurface,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = DeepNavyDark,
    onSurface = DeepNavyDark,
    outlineVariant = BorderSoftGray,
    surfaceVariant = ActivePillBg,
    tertiaryContainer = LavenderMutedBg,
    onTertiaryContainer = LavenderDeepText
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Disable dynamic color to enforce our beautiful custom Emerald/Slate theme!
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
