package com.cc.videoplayer.playback

import java.io.File
import java.util.Locale

private val subtitleExtensions = setOf("ass", "srt", "ssa", "sub", "vtt")

internal fun subtitleFiles(video: File): List<File> {
  val parent = video.parentFile ?: return emptyList()
  val base = video.name.substringBeforeLast('.', video.name)
  if (base.isEmpty()) return emptyList()
  val found = LinkedHashSet<File>()
  parent.listFiles()?.forEach { file ->
    if (file.isFile && isSubtitle(file.name) && file.name.startsWith(base, ignoreCase = true)) found.add(file)
  }
  val subs = File(parent, "Subs")
  File(subs, base).listFiles()?.forEach { file ->
    if (file.isFile && isSubtitle(file.name)) found.add(file)
  }
  subs.listFiles()?.forEach { file ->
    if (file.isFile && isSubtitle(file.name) && file.name.startsWith(base, ignoreCase = true)) found.add(file)
  }
  return found.sortedBy { it.name.lowercase(Locale.ROOT) }
}

internal fun trackLabel(raw: String): String {
  val leaf = raw.substringAfterLast('/').substringAfterLast('\\').substringBefore('?')
  val decoded = leaf.replace("%20", " ").trim()
  return decoded.ifBlank { "Track" }
}

private fun isSubtitle(name: String): Boolean {
  val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
  return extension in subtitleExtensions
}
