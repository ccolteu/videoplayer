package com.cc.videoplayer.playback

data class MediaTrack(val id: Int, val name: String)

internal fun audioChoices(tracks: List<MediaTrack>): List<MediaTrack> = tracks.filter { it.id >= 0 }

internal fun subtitleChoices(tracks: List<MediaTrack>): List<MediaTrack> {
  val real = tracks.filter { it.id >= 0 }
  if (real.isEmpty()) return emptyList()
  return listOf(MediaTrack(id = -1, name = "Off")) + real
}
