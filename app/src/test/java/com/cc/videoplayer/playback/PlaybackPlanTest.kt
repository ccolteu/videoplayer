package com.cc.videoplayer.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPlanTest {
  @Test
  fun resumeSkipsTheStartAndTheEnding() {
    assertEquals(0L, resumePositionMs(0L, 0L))
    assertEquals(0L, resumePositionMs(4_999L, 60_000L))
    assertEquals(5_000L, resumePositionMs(5_000L, 60_000L))
    assertEquals(50_000L, resumePositionMs(50_000L, 0L))
    assertEquals(0L, resumePositionMs(50_000L, 60_000L))
    assertEquals(80_000L, resumePositionMs(80_000L, 100_000L))
  }

  @Test
  fun playlistOrderMatchesTheFolderListing() {
    val dir = File.createTempFile("playlist", "").apply {
      delete()
      mkdirs()
      deleteOnExit()
    }
    File(dir, "b.mp4").writeBytes(byteArrayOf(1))
    File(dir, "a.mkv").writeBytes(byteArrayOf(1))
    File(dir, "c.txt").writeBytes(byteArrayOf(1))
    File(dir, ".hidden.mp4").writeBytes(byteArrayOf(1))
    val queue = FolderPlaylist.around(File(dir, "b.mp4").absolutePath)
    assertEquals(listOf("a.mkv", "b.mp4"), queue.files.map { it.name })
    assertTrue(queue.hasPrevious)
    assertFalse(queue.hasNext)
    val first = FolderPlaylist.around(File(dir, "a.mkv").absolutePath)
    assertFalse(first.hasPrevious)
    assertTrue(first.hasNext)
  }

  @Test
  fun subtitlesComeFromTheMatchingFolder() {
    val dir = File.createTempFile("subs", "").apply {
      delete()
      mkdirs()
      deleteOnExit()
    }
    val video = File(dir, "Episode.mp4")
    video.writeBytes(byteArrayOf(1))
    val beside = File(dir, "Episode.en.srt")
    beside.writeBytes(byteArrayOf(1))
    val other = File(dir, "Other.srt")
    other.writeBytes(byteArrayOf(1))
    val nested = File(dir, "Subs/Episode")
    nested.mkdirs()
    File(nested, "2_English.srt").writeBytes(byteArrayOf(1))
    File(nested, "notes.txt").writeBytes(byteArrayOf(1))
    assertEquals(
      listOf("2_English.srt", "Episode.en.srt"),
      subtitleFiles(video).map { it.name },
    )
    assertEquals("2_English.srt", trackLabel("file:///tmp/Subs/2_English.srt"))
    assertEquals("My Episode.srt", trackLabel("file:///tmp/My%20Episode.srt"))
  }

  @Test
  fun trackListsDropTheDisabledEntry() {
    val tracks = listOf(MediaTrack(-1, "Disable"), MediaTrack(2, "English"), MediaTrack(3, "Spanish"))
    assertEquals(listOf(MediaTrack(2, "English"), MediaTrack(3, "Spanish")), audioChoices(tracks))
    assertEquals(
      listOf(MediaTrack(-1, "Off"), MediaTrack(2, "English"), MediaTrack(3, "Spanish")),
      subtitleChoices(tracks),
    )
    assertEquals(emptyList<MediaTrack>(), subtitleChoices(emptyList()))
  }
}
