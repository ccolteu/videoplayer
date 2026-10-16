package com.cc.videoplayer.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val Scheme =
  darkColorScheme(
    primary = Amber,
    onPrimary = Ink,
    secondary = AmberDark,
    onSecondary = Cream,
    background = Ink,
    onBackground = Cream,
    surface = Panel,
    onSurface = Cream,
    surfaceVariant = PanelRaised,
    onSurfaceVariant = Muted,
    outline = AmberDark,
  )

@Composable
fun VideoPlayerTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = Scheme, typography = Typography, content = content)
}
