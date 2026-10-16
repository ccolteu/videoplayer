package com.cc.videoplayer.playback

import android.content.Context
import android.net.Uri
import com.cc.videoplayer.browse.DocumentLibrary
import com.cc.videoplayer.browse.isVideoFile
import java.io.File
import java.util.Locale

internal data class PlaylistEntry(val location: String, val name: String)

internal data class FolderQueue(val files: List<PlaylistEntry>, val index: Int) {
  val hasPrevious: Boolean get() = index > 0
  val hasNext: Boolean get() = index >= 0 && index < files.lastIndex
}

internal object FolderPlaylist {
  fun around(path: String): FolderQueue = aroundFiles(path)

  fun around(context: Context, location: String): FolderQueue {
    if (Uri.parse(location).scheme != "content") return aroundFiles(location)
    val parent = DocumentLibrary.parentLocation(location) ?: return FolderQueue(emptyList(), -1)
    val videos =
      runCatching { DocumentLibrary.children(context, parent) }
        .getOrNull()
        ?.filter { !it.directory }
        ?: return FolderQueue(emptyList(), -1)
    val index = videos.indexOfFirst { it.uri.toString() == location }
    return FolderQueue(videos.map { PlaylistEntry(it.uri.toString(), it.name) }, index)
  }

  private fun aroundFiles(path: String): FolderQueue {
    val file = File(path)
    val parent = file.parentFile ?: return FolderQueue(emptyList(), -1)
    val videos =
      runCatching { parent.listFiles() }
        .getOrNull()
        ?.filter { it.isFile && isVideoFile(it.name, null) }
        ?.sortedBy { it.name.lowercase(Locale.ROOT) }
        ?: return FolderQueue(emptyList(), -1)
    val index = videos.indexOfFirst { it.absolutePath == file.absolutePath }
    return FolderQueue(videos.map { PlaylistEntry(it.absolutePath, it.name) }, index)
  }
}
