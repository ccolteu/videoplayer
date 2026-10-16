package com.cc.videoplayer.ui

import android.app.PictureInPictureParams
import android.media.AudioManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.cc.videoplayer.browse.formatDuration
import com.cc.videoplayer.playback.PlaybackState
import com.cc.videoplayer.playback.PlayerViewModel
import com.cc.videoplayer.theme.Signal
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(path: String, title: String, onBack: () -> Unit, vm: PlayerViewModel = viewModel()) {
  val state by vm.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val activity = context as ComponentActivity
  val view = LocalView.current
  var controlsVisible by remember { mutableStateOf(true) }
  var scrubbing by remember { mutableStateOf(false) }
  var scrubMs by remember { mutableLongStateOf(0L) }
  var flashText by remember { mutableStateOf("") }
  var flashTick by remember { mutableIntStateOf(0) }
  var inPip by remember { mutableStateOf(activity.isInPictureInPictureMode) }

  LaunchedEffect(path, title) { vm.openFile(path, title) }

  LaunchedEffect(controlsVisible, state.playing, scrubbing) {
    if (controlsVisible && state.playing && !scrubbing) {
      delay(3_000)
      controlsVisible = false
    }
  }

  LaunchedEffect(flashTick) {
    if (flashTick == 0) return@LaunchedEffect
    delay(700)
    flashText = ""
  }

  DisposableEffect(state.playing) {
    view.keepScreenOn = state.playing
    onDispose { view.keepScreenOn = false }
  }

  LaunchedEffect(controlsVisible, inPip) {
    val controller = WindowCompat.getInsetsController(activity.window, view)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (controlsVisible && !inPip) controller.show(WindowInsetsCompat.Type.systemBars())
    else controller.hide(WindowInsetsCompat.Type.systemBars())
  }

  DisposableEffect(activity) {
    val listener =
      Consumer<PictureInPictureModeChangedInfo> { info ->
        inPip = info.isInPictureInPictureMode
      }
    activity.addOnPictureInPictureModeChangedListener(listener)
    onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
  }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, activity) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP && !activity.isInPictureInPictureMode) vm.pause()
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  DisposableEffect(context) {
    val receiver =
      object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
          if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) vm.pause()
        }
      }
    ContextCompat.registerReceiver(
      context,
      receiver,
      IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    onDispose { context.unregisterReceiver(receiver) }
  }

  Box(modifier = Modifier.fillMaxSize()) {
    if (!inPip && state.error == null) {
      Box(
        modifier =
          Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(
              onTap = { controlsVisible = !controlsVisible },
              onDoubleTap = { offset ->
                val backward = offset.x < size.width / 2f
                flashText = if (backward) "−10s" else "+10s"
                flashTick += 1
                vm.skipBy(if (backward) -10_000 else 10_000)
              },
            )
          },
      )
    }

    if (flashText.isNotEmpty() && !inPip) {
      Text(
        flashText,
        modifier = Modifier.align(Alignment.Center),
        style = MaterialTheme.typography.titleLarge,
        color = Color.White,
      )
    }

    if (state.buffering && state.error == null && !inPip) {
      if (state.positionMs <= 0L) {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Signal)
      } else {
        LinearProgressIndicator(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(), color = Signal)
      }
    }

    if (state.error != null && !inPip) {
      Column(
        modifier = Modifier.align(Alignment.Center).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(state.error.orEmpty(), color = Color.White, style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onBack) { Text("Back") }
      }
    }

    if (controlsVisible && !inPip && state.error == null) {
      PlayerChrome(
        state = state,
        scrubbing = scrubbing,
        scrubMs = scrubMs,
        pipAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
        onBack = onBack,
        onScrubStart = { value ->
          scrubbing = true
          scrubMs = value
        },
        onScrubFinish = {
          vm.seekTo(scrubMs)
          scrubbing = false
        },
        onPlayPause = vm::playPause,
        onSkipBack = { vm.skipBy(-10_000) },
        onSkipForward = { vm.skipBy(10_000) },
        onSpeed = vm::cycleSpeed,
        onAspect = vm::cycleAspect,
        onPip = {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder().setAspectRatio(vm.pipAspectRatio()).build()
            runCatching { activity.enterPictureInPictureMode(params) }
          }
        },
      )
    }
  }
}

@Composable
private fun PlayerChrome(
  state: PlaybackState,
  scrubbing: Boolean,
  scrubMs: Long,
  pipAvailable: Boolean,
  onBack: () -> Unit,
  onScrubStart: (Long) -> Unit,
  onScrubFinish: () -> Unit,
  onPlayPause: () -> Unit,
  onSkipBack: () -> Unit,
  onSkipForward: () -> Unit,
  onSpeed: () -> Unit,
  onAspect: () -> Unit,
  onPip: () -> Unit,
) {
  val duration = state.durationMs.coerceAtLeast(0L)
  val range = if (duration > 0L) 0f..duration.toFloat() else 0f..1f
  val shown = if (scrubbing) scrubMs else state.positionMs
  val sliderValue = shown.toFloat().coerceIn(range.start, range.endInclusive)
  val playIcon =
    when {
      state.ended -> Icons.Filled.Replay
      state.playing -> Icons.Filled.Pause
      else -> Icons.Filled.PlayArrow
    }
  val playLabel = if (state.ended) "Replay" else if (state.playing) "Pause" else "Play"

  Box(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier =
        Modifier
          .align(Alignment.TopCenter)
          .fillMaxWidth()
          .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)))
          .windowInsetsPadding(WindowInsets.safeDrawing)
          .padding(horizontal = 4.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
      }
      Text(
        state.title,
        modifier = Modifier.weight(1f),
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }

    if (!state.playing) {
      IconButton(onClick = onPlayPause, modifier = Modifier.align(Alignment.Center).size(72.dp)) {
        Icon(playIcon, contentDescription = playLabel, tint = Color.White, modifier = Modifier.size(48.dp))
      }
    }

    Column(
      modifier =
        Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))))
          .windowInsetsPadding(WindowInsets.safeDrawing)
          .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TimeText(formatDuration(shown))
        TimeText(formatDuration(duration))
      }
      Slider(
        value = sliderValue,
        onValueChange = { onScrubStart(it.toLong()) },
        onValueChangeFinished = onScrubFinish,
        valueRange = range,
        enabled = duration > 0L,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onSkipBack) {
          Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds", tint = Color.White)
        }
        IconButton(onClick = onPlayPause) {
          Icon(playIcon, contentDescription = playLabel, tint = Color.White)
        }
        IconButton(onClick = onSkipForward) {
          Icon(Icons.Filled.Forward10, contentDescription = "Forward 10 seconds", tint = Color.White)
        }
        TextButton(onClick = onSpeed) { Text(state.speedLabel) }
        TextButton(onClick = onAspect) { Text(state.aspectLabel) }
        if (pipAvailable) {
          IconButton(onClick = onPip) {
            Icon(Icons.Filled.PictureInPicture, contentDescription = "Picture in picture", tint = Color.White)
          }
        }
      }
    }
  }
}

@Composable
private fun TimeText(value: String) {
  Text(value, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
}
