package com.cc.videoplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.cc.videoplayer.theme.VideoPlayerTheme
import com.cc.videoplayer.ui.BrowserScreen

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val insets = WindowCompat.getInsetsController(window, window.decorView)
    insets.isAppearanceLightStatusBars = false
    insets.isAppearanceLightNavigationBars = false
    setContent {
      VideoPlayerTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          BrowserScreen()
        }
      }
    }
  }
}
