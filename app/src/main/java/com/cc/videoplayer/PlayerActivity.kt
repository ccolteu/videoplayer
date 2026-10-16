package com.cc.videoplayer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.cc.videoplayer.playback.PlayerViewModel
import com.cc.videoplayer.theme.VideoPlayerTheme
import com.cc.videoplayer.ui.PlayerScreen
import org.videolan.libvlc.util.VLCVideoLayout

class PlayerActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
    val insets = WindowCompat.getInsetsController(window, window.decorView)
    insets.isAppearanceLightStatusBars = false
    insets.isAppearanceLightNavigationBars = false
    val path = intent?.getStringExtra(EXTRA_PATH)?.takeIf { it.isNotBlank() }
    val title = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Video"
    if (path == null) {
      finish()
      return
    }
    val videoLayout =
      VLCVideoLayout(this).apply {
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
      }
    val composeView =
      ComposeView(this).apply {
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.TRANSPARENT)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
      }
    setContentView(
      FrameLayout(this).apply {
        addView(videoLayout)
        addView(composeView)
      },
    )
    val vm = ViewModelProvider(this)[PlayerViewModel::class.java]
    composeView.setContent {
      VideoPlayerTheme {
        PlayerScreen(
          path = path,
          title = title,
          onBack = { finish() },
          onNowPlaying = { currentPath, currentTitle ->
            intent.putExtra(EXTRA_PATH, currentPath)
            intent.putExtra(EXTRA_TITLE, currentTitle)
            setIntent(intent)
          },
          vm = vm,
        )
      }
    }
    videoLayout.post { vm.attach(videoLayout) }
  }

  companion object {
    private const val EXTRA_PATH = "path"
    private const val EXTRA_TITLE = "title"

    fun intent(context: Context, path: String, title: String): Intent {
      return Intent(context, PlayerActivity::class.java)
        .putExtra(EXTRA_PATH, path)
        .putExtra(EXTRA_TITLE, title)
    }
  }
}
