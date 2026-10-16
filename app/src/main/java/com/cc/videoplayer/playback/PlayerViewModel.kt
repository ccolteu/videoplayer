package com.cc.videoplayer.playback

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.view.View
import androidx.lifecycle.AndroidViewModel
import com.cc.videoplayer.browse.formatSpeed
import kotlin.math.abs
import kotlin.math.roundToInt
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.cc.videoplayer.browse.DocumentLibrary
import java.io.File
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.libvlc.util.VLCVideoLayout

data class PlaybackState(
  val title: String = "",
  val path: String = "",
  val playbackId: Int = 0,
  val playing: Boolean = false,
  val ended: Boolean = false,
  val buffering: Boolean = true,
  val positionMs: Long = 0,
  val durationMs: Long = 0,
  val resumeAtMs: Long = 0,
  val speed: Float = 1f,
  val speedLabel: String = "1×",
  val aspect: MediaPlayer.ScaleType = MediaPlayer.ScaleType.SURFACE_BEST_FIT,
  val aspectLabel: String = "Fit",
  val error: String? = null,
  val hasPrevious: Boolean = false,
  val hasNext: Boolean = false,
  val audioTracks: List<MediaTrack> = emptyList(),
  val audioTrackId: Int = -1,
  val subtitleTracks: List<MediaTrack> = emptyList(),
  val subtitleTrackId: Int = -1,
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val audioManager = application.getSystemService(AudioManager::class.java)
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
  private var sourceDescriptor: ParcelFileDescriptor? = null
  private var profileRewrite: HevcProfileRewrite? = null
  private var restarting = false
  private var released = false
  private var playbackGeneration = 0
  private var playlist = FolderQueue(emptyList(), -1)
  private var pausedByFocus = false
  private var finishedPath: String? = null
  private var lastPersistedMs = Long.MIN_VALUE
  private var focusRequest: AudioFocusRequest? = null
  private val focusListener =
    AudioManager.OnAudioFocusChangeListener { change ->
      mainHandler.post {
        if (released) return@post
        when (change) {
          AudioManager.AUDIOFOCUS_GAIN ->
            if (pausedByFocus) {
              pausedByFocus = false
              resumeAfterFocus()
            }
          AudioManager.AUDIOFOCUS_LOSS -> {
            pausedByFocus = false
            pauseForFocus(abandon = true)
          }
          AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseForFocus(abandon = false)
          else -> Unit
        }
      }
    }
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
    persistPosition(commit = true)
    finishedPath = null
    openedPath = path
    lastPersistedMs = Long.MIN_VALUE
    pausedByFocus = false
    playlist = FolderPlaylist.around(getApplication(), path)
    val resumeAt = PlaybackMemory.savedPosition(getApplication(), path)
    val keptSpeed = _state.value.speed
    val keptAspect = _state.value.aspect
    playbackGeneration += 1
    _state.value =
      PlaybackState(
        title = title,
        path = path,
        playbackId = playbackGeneration,
        buffering = true,
        positionMs = resumeAt,
        resumeAtMs = resumeAt,
        speed = keptSpeed,
        speedLabel = formatSpeed(keptSpeed),
        aspect = keptAspect,
        aspectLabel = aspectLabel(keptAspect),
        hasPrevious = playlist.hasPrevious,
        hasNext = playlist.hasNext,
      )
    try {
      playFrom(resumeAt)
    } catch (_: Throwable) {
      _state.update { it.copy(buffering = false, error = "Can't open this file.") }
    }
  }

  fun playPrevious() {
    val previous = playlist.files.getOrNull(playlist.index - 1) ?: return
    openFile(previous.location, previous.name)
  }

  fun playNext() {
    val next = playlist.files.getOrNull(playlist.index + 1) ?: return
    openFile(next.location, next.name)
  }

  fun selectAudioTrack(id: Int) {
    if (released) return
    runCatching { mediaPlayer?.setAudioTrack(id) }
    publishTracks()
  }

  fun selectSubtitleTrack(id: Int) {
    if (released) return
    runCatching { mediaPlayer?.setSpuTrack(id) }
    publishTracks()
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
        finishedPath = null
        playFrom(0)
        return
      }
      if (player.isPlaying) {
        pause()
      } else {
        pausedByFocus = false
        requestFocus()
        player.play()
      }
    } catch (_: Throwable) {
      _state.update { it.copy(playing = false, buffering = false, error = "This file can't be played.") }
    }
  }

  fun pause() {
    pausedByFocus = false
    persistPosition(commit = true)
    abandonFocus()
    runCatching {
      val player = mediaPlayer
      if (player != null && player.isPlaying) player.pause()
    }
  }

  fun seekTo(positionMs: Long) {
    val length = _state.value.durationMs
    val target = if (length > 0) positionMs.coerceIn(0, length) else positionMs.coerceAtLeast(0)
    if (_state.value.ended) {
      finishedPath = null
      runCatching { playFrom(target) }
        .onFailure {
          _state.update { it.copy(playing = false, buffering = false, error = "This file can't be played.") }
        }
      return
    }
    runCatching { mediaPlayer?.setTime(target) }
    _state.update { it.copy(positionMs = target) }
    persistPosition(target, commit = true)
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
    persistPosition(commit = true)
    abandonFocus()
    mediaPlayer?.let { player ->
      player.setEventListener(null)
      runCatching { player.vlcVout.removeCallback(surfaceCallback) }
      runCatching { player.detachViews() }
      runCatching { player.release() }
    }
    mediaPlayer = null
    runCatching { currentMedia?.release() }
    currentMedia = null
    runCatching { sourceDescriptor?.close() }
    sourceDescriptor = null
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
    val plan = VideoDecoderChoice.plan(getApplication(), path)
    val rewrite = if (plan.rewriteHevcMain10) HevcProfileRewrite.open(getApplication(), path) else null
    val hardware = plan.useMediaCodec && (!plan.rewriteHevcMain10 || rewrite != null)
    val content = Uri.parse(path).scheme == "content"
    val descriptor =
      if (rewrite == null && content) {
        getApplication<Application>().contentResolver.openFileDescriptor(Uri.parse(path), "r")
      } else {
        null
      }
    val media =
      try {
        when {
          rewrite != null -> Media(vlc, rewrite.fileDescriptor)
          descriptor != null -> Media(vlc, descriptor.fileDescriptor)
          else -> Media(vlc, path)
        }
      } catch (error: Throwable) {
        rewrite?.close()
        runCatching { descriptor?.close() }
        throw error
      }
    // `none` would also reject AAC, because MediaCodec audio is off by default.
    media.addOption(if (hardware) ":codec=mediacodec_ndk,mediacodec,all" else ":codec=avcodec,all")
    media.addOption(":file-caching=1500")
    if (positionMs > 0) media.addOption(":start-time=${positionMs / 1000.0}")
    for (subtitle in subtitlesFor(path)) {
      media.addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, 1, Uri.fromFile(subtitle).toString()))
    }
    val previous = currentMedia
    val previousRewrite = profileRewrite
    val previousDescriptor = sourceDescriptor
    currentMedia = media
    profileRewrite = rewrite
    sourceDescriptor = descriptor
    restarting = true
    if (player.media != null) runCatching { player.stop() }
    player.media = media
    runCatching { previous?.release() }
    if (previousRewrite !== rewrite) previousRewrite?.close()
    if (previousDescriptor !== descriptor) runCatching { previousDescriptor?.close() }
    player.setVideoTrackEnabled(true)
    player.rate = _state.value.speed
    player.videoScale = _state.value.aspect
    val engine =
      when {
        hardware && plan.rewriteHevcMain10 -> "mediacodec main10"
        hardware -> "mediacodec"
        else -> "ffmpeg"
      }
    Log.i(TAG, "play ${layout.width}x${layout.height} $engine at ${positionMs}ms")
    requestFocus()
    player.play()
    restarting = false
    player.updateVideoSurfaces()
    _state.update {
      it.copy(ended = false, playing = true, buffering = true, positionMs = positionMs, error = null)
    }
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
          publishTracks()
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
        MediaPlayer.Event.EndReached -> onEnded(ignoreStop)
        MediaPlayer.Event.EncounteredError ->
          _state.update { it.copy(playing = false, buffering = false, error = "This file can't be played.") }
        MediaPlayer.Event.TimeChanged -> {
          _state.update { it.copy(positionMs = timeChanged.coerceAtLeast(0)) }
          persistPosition(timeChanged, commit = false)
        }
        MediaPlayer.Event.LengthChanged -> _state.update { it.copy(durationMs = lengthChanged.coerceAtLeast(0)) }
        MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> publishTracks()
      }
    }
  }

  private fun onEnded(ignoreStop: Boolean) {
    if (ignoreStop) return
    val finished = openedPath
    if (finished != null) {
      finishedPath = finished
      PlaybackMemory.clear(getApplication(), finished, commit = true)
    }
    abandonFocus()
    val next = playlist.files.getOrNull(playlist.index + 1)
    if (next != null) {
      mainHandler.postDelayed({
        if (released || openedPath != finished) return@postDelayed
        openFile(next.location, next.name)
      }, 50)
    } else {
      _state.update { it.copy(playing = false, ended = true, buffering = false) }
    }
  }

  private fun subtitlesFor(location: String): List<File> {
    val uri = Uri.parse(location)
    if (uri.scheme != "content") return subtitleFiles(File(location))
    return DocumentLibrary.subtitleUris(getApplication(), location).mapNotNull { cachedSubtitle(getApplication(), it) }
  }

  private fun publishTracks() {
    val player = mediaPlayer ?: return
    val audio = audioChoices(readTracks { player.audioTracks })
    val subtitles = subtitleChoices(readTracks { player.spuTracks })
    val audioId = runCatching { player.audioTrack }.getOrDefault(-1)
    val subtitleId = runCatching { player.spuTrack }.getOrDefault(-1)
    _state.update {
      it.copy(
        audioTracks = audio,
        audioTrackId = audioId,
        subtitleTracks = subtitles,
        subtitleTrackId = subtitleId,
      )
    }
  }

  private fun readTracks(load: () -> Array<MediaPlayer.TrackDescription>?): List<MediaTrack> {
    val tracks = runCatching(load).getOrNull() ?: return emptyList()
    return tracks.map { MediaTrack(id = it.id, name = trackLabel(it.name?.trim().orEmpty())) }
  }

  private fun persistPosition(positionMs: Long = currentPosition(), commit: Boolean) {
    val path = openedPath ?: return
    if (path == finishedPath) return
    if (positionMs < 5_000L) return
    if (!commit && abs(positionMs - lastPersistedMs) < 5_000L) return
    val duration = maxOf(_state.value.durationMs, runCatching { mediaPlayer?.length ?: 0L }.getOrDefault(0L))
    val keep = resumePositionMs(positionMs, duration)
    if (keep == 0L) {
      PlaybackMemory.clear(getApplication(), path, commit)
    } else {
      PlaybackMemory.write(getApplication(), path, keep, duration, commit)
    }
    lastPersistedMs = positionMs
  }

  private fun currentPosition(): Long {
    val live = runCatching { mediaPlayer?.time ?: -1L }.getOrDefault(-1L)
    return if (live > 0L) live else _state.value.positionMs
  }

  private fun requestFocus() {
    val manager = audioManager ?: return
    val result =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val request = focusRequest ?: newFocusRequest().also { focusRequest = it }
        manager.requestAudioFocus(request)
      } else {
        @Suppress("DEPRECATION")
        manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
      }
    Log.i(TAG, "audio focus $result")
  }

  private fun abandonFocus() {
    val manager = audioManager ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val request = focusRequest ?: return
      manager.abandonAudioFocusRequest(request)
    } else {
      @Suppress("DEPRECATION")
      manager.abandonAudioFocus(focusListener)
    }
  }

  private fun newFocusRequest(): AudioFocusRequest {
    val attributes =
      AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()
    return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
      .setAudioAttributes(attributes)
      .setOnAudioFocusChangeListener(focusListener, mainHandler)
      .build()
  }

  private fun pauseForFocus(abandon: Boolean) {
    val playing = mediaPlayer?.isPlaying == true
    if (!playing) return
    pausedByFocus = !abandon
    persistPosition(commit = true)
    runCatching { mediaPlayer?.pause() }
    if (abandon) abandonFocus()
  }

  private fun resumeAfterFocus() {
    requestFocus()
    val player = mediaPlayer ?: return
    if (player.media != null && !_state.value.ended) runCatching { player.play() }
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
