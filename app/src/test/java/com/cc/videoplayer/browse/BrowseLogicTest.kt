package com.cc.videoplayer.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseLogicTest {
  @Test
  fun videoMimeAndExtensionBothCount() {
    assertTrue(isVideoFile("clip", "video/mp4"))
    assertTrue(isVideoFile("movie.AVI", "application/octet-stream"))
    assertTrue(isVideoFile("show.mkv", null))
    assertTrue(isVideoFile("tape.wmv", null))
  }

  @Test
  fun nonVideoAndHiddenFilesAreSkipped() {
    assertFalse(isVideoFile("photo.jpg", "image/jpeg"))
    assertFalse(isVideoFile("notes.txt", "text/plain"))
    assertFalse(isVideoFile(".hidden.mkv", "video/x-matroska"))
    assertFalse(isVideoFile("readme", null))
  }

  @Test
  fun durationAndSizeFormatForThePlayer() {
    assertEquals("0:00", formatDuration(0))
    assertEquals("0:00", formatDuration(-20))
    assertEquals("1:05", formatDuration(65_000))
    assertEquals("1:01:01", formatDuration(3_661_000))
    assertEquals("", formatSize(0))
    assertEquals("1.5 KB", formatSize(1536))
    assertEquals("1.0 MB", formatSize(1024 * 1024))
    assertEquals("1×", formatSpeed(1f))
    assertEquals("0.75×", formatSpeed(0.75f))
    assertEquals("1.25×", formatSpeed(1.25f))
  }
}
