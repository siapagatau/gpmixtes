package com.gpaudio.gpmix.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Tema: "default" (abu gelap + oranye) dan "studio" (Studio Teal). Selalu gelap: dipakai di panggung/studio redup. */
private val DefaultScheme = darkColorScheme(
    primary = Color(0xFFFFB020), onPrimary = Color(0xFF241800),
    secondary = Color(0xFF8AB4F8), onSecondary = Color(0xFF00131F),
    background = Color(0xFF15181C), onBackground = Color(0xFFE6E8EB),
    surface = Color(0xFF1D2126), onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF2A3037), onSurfaceVariant = Color(0xFFB9C0C8),
    error = Color(0xFFFF6B6B), outline = Color(0xFF6B7480),
)

private val StudioScheme = darkColorScheme(
    primary = Color(0xFF26C6B0), onPrimary = Color(0xFF00201C),
    secondary = Color(0xFFFFB74D), onSecondary = Color(0xFF241400),
    background = Color(0xFF0F1A1B), onBackground = Color(0xFFE0EEEC),
    surface = Color(0xFF162426), onSurface = Color(0xFFE0EEEC),
    surfaceVariant = Color(0xFF213538), onSurfaceVariant = Color(0xFFADC7C4),
    error = Color(0xFFFF6B6B), outline = Color(0xFF5E807C),
)

/** Warna channel 0..7 (juga ditampilkan lewat teks/posisi, bukan hanya warna). */
val ChannelColors = listOf(
    Color(0xFFFF7043), Color(0xFFFFCA28), Color(0xFF66BB6A), Color(0xFF26C6DA),
    Color(0xFF42A5F5), Color(0xFFAB47BC), Color(0xFFEC407A), Color(0xFFBDBDBD),
)

val MeterGreen = Color(0xFF4CAF50)
val MeterYellow = Color(0xFFFFEB3B)
val MeterRed = Color(0xFFF44336)

@Composable
fun GpTheme(theme: String, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (theme == "studio") StudioScheme else DefaultScheme, content = content)
}
