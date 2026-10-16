package com.cc.videoplayer.browse

sealed interface Place {
  data object Roots : Place

  data class Directory(val path: String, val title: String) : Place
}

data class StorageRoot(
  val id: String,
  val label: String,
  val removable: Boolean,
  val path: String,
)

data class FolderEntry(
  val path: String,
  val name: String,
  val directory: Boolean,
  val sizeBytes: Long,
)
