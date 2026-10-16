package com.cc.videoplayer.playback

import android.content.Context
import android.net.Uri
import com.cc.videoplayer.browse.isSubtitleName
import java.io.File
import java.util.Locale

internal fun subtitleFiles(video: File): List<File> {
  val parent = video.parentFile ?: return emptyList()
  val base = video.name.substringBeforeLast('.', video.name)
  if (base.isEmpty()) return emptyList()
  val found = LinkedHashSet<File>()
  parent.listFiles()?.forEach { file ->
    if (file.isFile && isSubtitleName(file.name) && file.name.startsWith(base, ignoreCase = true)) found.add(file)
  }
  val subs = File(parent, "Subs")
  File(subs, base).listFiles()?.forEach { file ->
    if (file.isFile && isSubtitleName(file.name)) found.add(file)
  }
  subs.listFiles()?.forEach { file ->
    if (file.isFile && isSubtitleName(file.name) && file.name.startsWith(base, ignoreCase = true)) found.add(file)
  }
  return found.sortedBy { it.name.lowercase(Locale.ROOT) }
}

internal fun trackLabel(raw: String): String {
  val leaf = raw.substringAfterLast('/').substringAfterLast('\\').substringBefore('?')
  val decoded = leaf.replace("%20", " ").trim()
  return decoded.ifBlank { "Track" }
}

internal fun cachedSubtitle(context: Context, uri: Uri): File? {
  val dir = File(context.cacheDir, "subtitles").apply { mkdirs() }
  val leaf = Uri.decode(uri.lastPathSegment.orEmpty()).substringAfterLast('/').substringAfterLast(':')
  val safe = leaf.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "subtitle.srt" }.take(80)
  val dest = File(dir, "${uri.toString().hashCode().toUInt().toString(16)}_$safe")
  return runCatching {
    context.contentResolver.openInputStream(uri).use { input ->
      if (input == null) return null
      dest.outputStream().use { input.copyTo(it) }
    }
    dest
  }.getOrNull()
}
