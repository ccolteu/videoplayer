package com.cc.videoplayer.playback

import android.content.Context

internal fun resumePositionMs(positionMs: Long, durationMs: Long): Long {
  if (positionMs < 5_000L) return 0L
  if (durationMs > 0L && positionMs >= durationMs - 10_000L) return 0L
  return positionMs
}

internal object PlaybackMemory {
  private const val PREFS = "playback-memory"

  fun savedPosition(context: Context, path: String): Long {
    val raw = prefs(context).getString(path, null) ?: return 0L
    val parts = raw.split(',')
    val position = parts.getOrNull(0)?.toLongOrNull() ?: return 0L
    val duration = parts.getOrNull(1)?.toLongOrNull() ?: 0L
    return resumePositionMs(position, duration)
  }

  fun write(context: Context, path: String, positionMs: Long, durationMs: Long, commit: Boolean) {
    val editor = prefs(context).edit().putString(path, "$positionMs,$durationMs")
    if (commit) editor.commit() else editor.apply()
  }

  fun clear(context: Context, path: String, commit: Boolean) {
    val editor = prefs(context).edit().remove(path)
    if (commit) editor.commit() else editor.apply()
  }

  private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
