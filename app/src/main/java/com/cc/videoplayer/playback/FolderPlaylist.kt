package com.cc.videoplayer.playback

import com.cc.videoplayer.browse.isVideoFile
import java.io.File
import java.util.Locale

internal data class FolderQueue(val files: List<File>, val index: Int) {
  val hasPrevious: Boolean get() = index > 0
  val hasNext: Boolean get() = index >= 0 && index < files.lastIndex
}

internal object FolderPlaylist {
  fun around(path: String): FolderQueue {
    val file = File(path)
    val parent = file.parentFile ?: return FolderQueue(emptyList(), -1)
    val videos =
      runCatching { parent.listFiles() }
        .getOrNull()
        ?.filter { it.isFile && isVideoFile(it.name, null) }
        ?.sortedBy { it.name.lowercase(Locale.ROOT) }
        ?: return FolderQueue(emptyList(), -1)
    val index = videos.indexOfFirst { it.absolutePath == file.absolutePath }
    return FolderQueue(videos, index)
  }
}
