package com.cc.videoplayer.playback

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.view.View
import androidx.lifecycle.AndroidViewModel
import com.cc.videoplayer.browse.formatSpeed
import kotlin.math.abs
import kotlin.math.roundToInt
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.libvlc.util.VLCVideoLayout

data class PlaybackState(
  val title: String = "",
  val playing: Boolean = false,
  val ended: Boolean = false,
  val buffering: Boolean = true,
  val positionMs: Long = 0,
  val durationMs: Long = 0,
  val speed: Float = 1f,
  val speedLabel: String = "1×",
  val aspect: MediaPlayer.ScaleType = MediaPlayer.ScaleType.SURFACE_BEST_FIT,
  val aspectLabel: String = "Fit",
  val error: String? = null,
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
  private val aspects =
    arrayOf(
      MediaPlayer.ScaleType.SURFACE_BEST_FIT,
      MediaPlayer.ScaleType.SURFACE_FIT_SCREEN,
      MediaPlayer.ScaleType.SURFACE_FILL,
      MediaPlayer.ScaleType.SURFACE_16_9,
      MediaPlayer.ScaleType.SURFACE_4_3,
      MediaPlayer.ScaleType.SURFACE_ORIGINAL,
    )

  private val _state = kotlinx.coroutines.flow.MutableStateFlow(PlaybackState())
  val state: kotlinx.coroutines.flow.StateFlow<PlaybackState> = _state

  private var libVlc: LibVLC? = null
  private var mediaPlayer: MediaPlayer? = null
  private var openedPath: String? = null
  private var attachedLayout: VLCVideoLayout? = null
  private var awaitingSurface = false
  private var surfaceReady = false
  private var startPositionMs = 0L
  private var currentMedia: Media? = null
  private var profileRewrite: HevcProfileRewrite? = null
  private var restarting = false
  private var released = false
  private val surfaceCallback =
    object : IVLCVout.Callback {
      override fun onSurfacesCreated(vout: IVLCVout) {
        surfaceReady = true
        Log.i(TAG, "surface ready")
        if (awaitingSurface) playFrom(startPositionMs)
      }

      override fun onSurfacesDestroyed(vout: IVLCVout) {
        surfaceReady = false
      }
    }
  private val layoutWatcher =
    View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
      if (view.width <= 0 || view.height <= 0) return@OnLayoutChangeListener
      if (awaitingSurface) playFrom(startPositionMs)
    }

  fun openFile(path: String, title: String) {
    if (released) return
    if (path == openedPath && _state.value.error == null) {
      _state.update { it.copy(title = title) }
      return
    }
    openedPath = path
    val keptSpeed = _state.value.speed
    val keptAspect = _state.value.aspect
    _state.value =
      PlaybackState(
        title = title,
        buffering = true,
        speed = keptSpeed,
        speedLabel = formatSpeed(keptSpeed),
        aspect = keptAspect,
        aspectLabel = aspectLabel(keptAspect),
      )
    try {
      playFrom(0)
    } catch (_: Throwable) {
      _state.update { it.copy(buffering = false, error = "Can't open this file.") }
    }
  }

  fun attach(layout: VLCVideoLayout) {
    if (released) return
    try {
      val player = player()
      if (attachedLayout !== layout) {
        attachedLayout?.removeOnLayoutChangeListener(layoutWatcher)
        runCatching { player.detachViews() }
        attachedLayout = layout
        layout.addOnLayoutChangeListener(layoutWatcher)
        // SurfaceView, not TextureView. MediaCodec draws 10-bit HEVC onto a
        // real Surface; a SurfaceTexture rejects that buffer and VLC falls
        // through to FFmpeg, which cannot build a displayable picture.
        player.attachViews(layout, null, true, false)
        player.videoScale = _state.value.aspect
      }
      if (layout.width > 0 && layout.height > 0) {
        player.updateVideoSurfaces()
        if (awaitingSurface) playFrom(startPositionMs)
      }
    } catch (_: Throwable) {
      _state.update { it.copy(buffering = false, error = "Playback isn't available on this device.") }
    }
  }

  fun detach(layout: VLCVideoLayout) {
    if (attachedLayout !== layout) return
    layout.removeOnLayoutChangeListener(layoutWatcher)
    runCatching { mediaPlayer?.detachViews() }
    attachedLayout = null
  }

  fun playPause() {
    if (released || openedPath == null) return
    try {
      val player = mediaPlayer
      if (_state.value.ended || player == null || player.media == null) {
        playFrom(0)
        return
      }
      if (player.isPlaying) player.pause() else player.play()
    } catch (_: Throwable) {
      _state.update { it.copy(playing = false, buffering = false, error = "This file can't be played.") }
    }
  }

  fun pause() {
    runCatching {
      val player = mediaPlayer
      if (player != null && player.isPlaying) player.pause()
    }
  }

  fun seekTo(positionMs: Long) {
    val length = _state.value.durationMs
    val target = if (length > 0) positionMs.coerceIn(0, length) else positionMs.coerceAtLeast(0)
    if (_state.value.ended) {
      runCatching { playFrom(target) }
        .onFailure {
          _state.update { it.copy(playing = false, buffering = false, error = "This file can't be played.") }
        }
      return
    }
    runCatching { mediaPlayer?.setTime(target) }
    _state.update { it.copy(positionMs = target) }
  }

  fun skipBy(deltaMs: Long) {
    val position = if (mediaPlayer != null) mediaPlayer?.time ?: _state.value.positionMs else _state.value.positionMs
    seekTo(position + deltaMs)
  }

  fun cycleSpeed() {
    val current = _state.value.speed
    val index = speeds.indexOfFirst { abs(it - current) < 0.01f }.let { if (it < 0) 2 else it }
    val next = speeds[(index + 1) % speeds.size]
    _state.update { it.copy(speed = next, speedLabel = formatSpeed(next)) }
    runCatching { mediaPlayer?.rate = next }
  }

  fun cycleAspect() {
    val current = _state.value.aspect
    val index = aspects.indexOf(current).let { if (it < 0) 0 else it }
    val next = aspects[(index + 1) % aspects.size]
    _state.update { it.copy(aspect = next, aspectLabel = aspectLabel(next)) }
    runCatching { mediaPlayer?.videoScale = next }
  }

  fun pipAspectRatio(): Rational {
    val track = runCatching { mediaPlayer?.currentVideoTrack }.getOrNull()
    val width = track?.width ?: 0
    val height = track?.height ?: 0
    if (width <= 0 || height <= 0) return Rational(16, 9)
    val ratio = width.toFloat() / height.toFloat()
    val clamped = ratio.coerceIn(0.42f, 2.39f)
    return Rational((clamped * 1000).roundToInt().coerceAtLeast(1), 1000)
  }

  override fun onCleared() {
    released = true
    mainHandler.removeCallbacksAndMessages(null)
    mediaPlayer?.let { player ->
      player.setEventListener(null)
      runCatching { player.vlcVout.removeCallback(surfaceCallback) }
      runCatching { player.detachViews() }
      runCatching { player.release() }
    }
    mediaPlayer = null
    runCatching { currentMedia?.release() }
    currentMedia = null
    profileRewrite?.close()
    profileRewrite = null
    runCatching { libVlc?.release() }
    libVlc = null
    super.onCleared()
  }

  private fun playFrom(positionMs: Long) {
    val path = openedPath ?: return
    val layout = attachedLayout
    if (layout == null || layout.width <= 0 || layout.height <= 0 || !surfaceReady) {
      startPositionMs = positionMs
      awaitingSurface = true
      return
    }
    awaitingSurface = false
    val player = player()
    val vlc = libVlc ?: return
    val plan = VideoDecoderChoice.plan(path)
    val rewrite = if (plan.rewriteHevcMain10) HevcProfileRewrite.open(getApplication(), path) else null
    val hardware = plan.useMediaCodec && (!plan.rewriteHevcMain10 || rewrite != null)
    val media =
      try {
        if (rewrite != null) Media(vlc, rewrite.fileDescriptor) else Media(vlc, path)
      } catch (error: Throwable) {
        rewrite?.close()
        throw error
      }
    // `none` would also reject AAC, because MediaCodec audio is off by default.
    media.addOption(if (hardware) ":codec=mediacodec_ndk,mediacodec,all" else ":codec=avcodec,all")
    media.addOption(":file-caching=1500")
    if (positionMs > 0) media.addOption(":start-time=${positionMs / 1000.0}")
    val previous = currentMedia
    val previousRewrite = profileRewrite
    currentMedia = media
    profileRewrite = rewrite
    restarting = true
    if (player.media != null) runCatching { player.stop() }
    player.media = media
    runCatching { previous?.release() }
    if (previousRewrite !== rewrite) previousRewrite?.close()
    player.setVideoTrackEnabled(true)
    player.rate = _state.value.speed
    player.videoScale = _state.value.aspect
    val engine =
      when {
        hardware && plan.rewriteHevcMain10 -> "mediacodec main10"
        hardware -> "mediacodec"
        else -> "ffmpeg"
      }
    Log.i(TAG, "play ${layout.width}x${layout.height} $engine")
    player.play()
    restarting = false
    player.updateVideoSurfaces()
    _state.update { it.copy(ended = false, playing = true, buffering = true, positionMs = positionMs, error = null) }
  }

  private fun player(): MediaPlayer {
    mediaPlayer?.let { return it }
    val vlc = libVlc ?: createLibVlc().also { libVlc = it }
    return MediaPlayer(vlc).also {
      it.setEventListener(::onPlayerEvent)
      it.vlcVout.addCallback(surfaceCallback)
      mediaPlayer = it
    }
  }

  private fun createLibVlc(): LibVLC {
    val options =
      arrayListOf(
        "--no-drop-late-frames",
        "--no-skip-frames",
        "--audio-time-stretch",
        // Empty chroma suppresses LibVLC's default RV16. MediaCodec draws
        // 10-bit HEVC straight to the Surface; forcing GLES2 rejects that.
        "--android-display-chroma=",
      )
    return LibVLC(getApplication(), options)
  }

  private fun onPlayerEvent(event: MediaPlayer.Event) {
    val type = event.type
    val ignoreStop = restarting
    val timeChanged = if (type == MediaPlayer.Event.TimeChanged) event.timeChanged else 0L
    val lengthChanged = if (type == MediaPlayer.Event.LengthChanged) event.lengthChanged else 0L
    val buffering = if (type == MediaPlayer.Event.Buffering) event.buffering else 0f
    mainHandler.post {
      if (released) return@post
      when (type) {
        MediaPlayer.Event.Opening -> _state.update { it.copy(buffering = true, error = null) }
        MediaPlayer.Event.Buffering -> _state.update { it.copy(buffering = buffering < 100f) }
        MediaPlayer.Event.Vout -> mediaPlayer?.updateVideoSurfaces()
        MediaPlayer.Event.Playing -> {
          mediaPlayer?.updateVideoSurfaces()
          val known = runCatching { mediaPlayer?.length ?: 0L }.getOrDefault(0L)
          _state.update {
            it.copy(
              playing = true,
              ended = false,
              buffering = false,
              error = null,
              durationMs = maxOf(it.durationMs, known),
            )
          }
        }
        MediaPlayer.Event.Paused -> _state.update { it.copy(playing = false, buffering = false) }
        MediaPlayer.Event.Stopped -> if (!ignoreStop) _state.update { it.copy(playing = false) }
        MediaPlayer.Event.EndReached -> _state.update { it.copy(playing = false, ended = true, buffering = false) }
        MediaPlayer.Event.EncounteredError ->
          _state.update { it.copy(playing = false, buffering = false, error = "This file can't be played.") }
        MediaPlayer.Event.TimeChanged -> _state.update { it.copy(positionMs = timeChanged.coerceAtLeast(0)) }
        MediaPlayer.Event.LengthChanged -> _state.update { it.copy(durationMs = lengthChanged.coerceAtLeast(0)) }
      }
    }
  }

  private fun aspectLabel(aspect: MediaPlayer.ScaleType): String =
    when (aspect) {
      MediaPlayer.ScaleType.SURFACE_FIT_SCREEN -> "Crop"
      MediaPlayer.ScaleType.SURFACE_FILL -> "Fill"
      MediaPlayer.ScaleType.SURFACE_16_9 -> "16:9"
      MediaPlayer.ScaleType.SURFACE_4_3 -> "4:3"
      MediaPlayer.ScaleType.SURFACE_ORIGINAL -> "1:1"
      else -> "Fit"
    }
}

private const val TAG = "VideoPlayer"

private fun <T> kotlinx.coroutines.flow.MutableStateFlow<T>.update(block: (T) -> T) {
  value = block(value)
}
