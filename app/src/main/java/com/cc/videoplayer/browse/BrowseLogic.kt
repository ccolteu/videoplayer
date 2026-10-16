package com.cc.videoplayer.browse

import java.util.Locale
import kotlin.math.roundToInt

private val VideoExtensions =
  setOf(
    "3g2",
    "3gp",
    "amv",
    "asf",
    "avi",
    "divx",
    "dv",
    "f4v",
    "flv",
    "m2t",
    "m2ts",
    "m2v",
    "m4v",
    "mjpeg",
    "mjpg",
    "mkv",
    "mov",
    "mp2",
    "mp4",
    "mpe",
    "mpeg",
    "mpg",
    "mts",
    "mxf",
    "ogm",
    "ogv",
    "qt",
    "rm",
    "rmvb",
    "ts",
    "vob",
    "webm",
    "wmv",
    "wtv",
  )

fun isVideoFile(name: String, mimeType: String?): Boolean {
  val trimmed = name.trim()
  if (trimmed.startsWith('.')) return false
  if (mimeType != null && mimeType.startsWith("video/", ignoreCase = true)) return true
  val extension = trimmed.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
  return extension in VideoExtensions
}

fun formatDuration(ms: Long): String {
  val safe = ms.coerceAtLeast(0) / 1000
  val hours = safe / 3600
  val minutes = (safe % 3600) / 60
  val seconds = safe % 60
  return if (hours > 0) {
    String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
  } else {
    String.format(Locale.US, "%d:%02d", minutes, seconds)
  }
}

fun formatSize(bytes: Long): String {
  if (bytes <= 0L) return ""
  val units = arrayOf("B", "KB", "MB", "GB", "TB")
  var value = bytes.toDouble()
  var unit = 0
  while (value >= 1024 && unit < units.lastIndex) {
    value /= 1024
    unit++
  }
  return if (unit == 0) {
    "$bytes B"
  } else {
    String.format(Locale.US, "%.1f %s", value, units[unit])
  }
}

fun formatSpeed(speed: Float): String {
  val rounded = (speed * 100).roundToInt() / 100f
  val text =
    if (kotlin.math.abs(rounded - rounded.toInt()) < 0.001f) {
      rounded.toInt().toString()
    } else {
      String.format(Locale.US, "%.2f", rounded).trimEnd('0').trimEnd('.')
    }
  return "${text}×"
}
