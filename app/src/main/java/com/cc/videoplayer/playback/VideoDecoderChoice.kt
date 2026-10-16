package com.cc.videoplayer.playback

import java.io.File
import java.io.RandomAccessFile

/**
 * This phone hardware-decodes AVC, HEVC, and VP9 only. Its MPEG-4 decoder is
 * Simple Profile software, and it turns Xvid Advanced Simple (Cheers) into
 * macroblocks. Those files go to FFmpeg. H.264, HEVC, and VP9 stay on MediaCodec.
 */
internal object VideoDecoderChoice {
  private val hardware =
    setOf("avc1", "avc3", "h264", "x264", "davc", "hevc", "h265", "hvc1", "hev1", "vp09", "vp90")

  fun useHardware(path: String): Boolean {
    val file = File(path)
    if (!file.isFile) return true
    return runCatching { inspect(file) }.getOrDefault(true)
  }

  private fun inspect(file: File): Boolean {
    RandomAccessFile(file, "r").use { raf ->
      if (raf.length() < 12) return true
      val head = ByteArray(12)
      raf.readFully(head)
      val kind = head.copyOfRange(8, 12).decodeToString()
      val box = head.copyOfRange(4, 8).decodeToString()
      val fourcc =
        when {
          head.copyOfRange(0, 4).decodeToString() == "RIFF" && kind == "AVI " -> aviVideo(raf)
          box == "ftyp" -> mp4Video(raf, raf.length())
          head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() -> matroska(raf)
          else -> null
        }
      if (fourcc == null) return true
      return fourcc.lowercase() in hardware
    }
  }

  private fun aviVideo(raf: RandomAccessFile): String? {
    return walkRiff(raf, 12, raf.length(), 0)
  }

  private fun walkRiff(raf: RandomAccessFile, start: Long, end: Long, depth: Int): String? {
    if (depth > 8) return null
    var pos = start
    while (pos + 8 <= end) {
      raf.seek(pos)
      val id = readFour(raf) ?: return null
      val size = readU32Le(raf)
      if (size < 0) return null
      val data = pos + 8
      if (data > end) return null
      if (id == "RIFF" || id == "LIST") {
        val type = readFour(raf) ?: return null
        if (type == "hdrl" || type == "strl" || id == "RIFF") {
          val found = walkRiff(raf, data + 4, minOf(end, data + size), depth + 1)
          if (found != null) return found
        }
      } else if (id == "strh" && size >= 8) {
        val fccType = readFour(raf)
        val handler = readFour(raf)
        if (fccType == "vids" && handler != null) return handler
      }
      val padded = size + (size and 1L)
      if (padded < 0 || data > end - padded) return null
      pos = data + padded
    }
    return null
  }

  private fun mp4Video(raf: RandomAccessFile, end: Long): String? {
    return walkMp4(raf, 0, end, null, 0)
  }

  private fun walkMp4(
    raf: RandomAccessFile,
    start: Long,
    end: Long,
    handler: String?,
    depth: Int,
  ): String? {
    if (depth > 12 || end - start < 8) return null
    var pos = start
    var trackHandler = handler
    while (pos + 8 <= end) {
      raf.seek(pos)
      val size32 = readU32Be(raf)
      val type = readFour(raf) ?: return null
      if (size32 < 0) return null
      var header = 8L
      var size = size32
      if (size32 == 1L) {
        if (pos + 16 > end) return null
        size = readU64Be(raf)
        header = 16
      } else if (size32 == 0L) {
        size = end - pos
      }
      if (size < header || pos > end - size) return null
      val child = pos + header
      when (type) {
        "moov", "mdia", "minf", "stbl" -> {
          val found = walkMp4(raf, child, pos + size, trackHandler, depth + 1)
          if (found != null) return found
        }
        "trak" -> {
          val found = walkMp4(raf, child, pos + size, null, depth + 1)
          if (found != null) return found
        }
        "hdlr" -> if (pos + size - child >= 12) {
          raf.seek(child + 8)
          trackHandler = readFour(raf)
        }
        "stsd" -> if (trackHandler == "vide") {
          return sampleEntry(raf, child, pos + size)
        }
      }
      pos += size
    }
    return null
  }

  private fun sampleEntry(raf: RandomAccessFile, payload: Long, end: Long): String? {
    if (end - payload < 16) return null
    raf.seek(payload + 4)
    val count = readU32Be(raf)
    if (count <= 0 || payload + 16 > end) return null
    raf.seek(payload + 12)
    return readFour(raf)
  }

  private fun matroska(raf: RandomAccessFile): String? {
    val window = minOf(raf.length(), 1024L * 1024).toInt()
    if (window < 16) return null
    val buf = ByteArray(window)
    raf.seek(0)
    raf.readFully(buf)
    val text = String(buf, Charsets.ISO_8859_1)
    return when {
      "V_MPEGH/ISO/HEVC" in text || "V_MPEG4/ISO/AVC" in text || "V_VP9" in text -> "hevc"
      "V_MPEG4/ISO/ASP" in text || "V_MPEG4/ISO/SP" in text || "V_MPEG4/MS" in text -> "mp4v"
      "XVID" in text || "xvid" in text || "DIVX" in text -> "xvid"
      else -> null
    }
  }

  private fun readFour(raf: RandomAccessFile): String? {
    val buf = ByteArray(4)
    if (raf.read(buf) != 4) return null
    return buf.decodeToString()
  }

  private fun readU32Le(raf: RandomAccessFile): Long {
    val buf = ByteArray(4)
    if (raf.read(buf) != 4) return -1
    return (buf[0].toLong() and 0xFF) or
      ((buf[1].toLong() and 0xFF) shl 8) or
      ((buf[2].toLong() and 0xFF) shl 16) or
      ((buf[3].toLong() and 0xFF) shl 24)
  }

  private fun readU32Be(raf: RandomAccessFile): Long {
    val buf = ByteArray(4)
    if (raf.read(buf) != 4) return -1
    return ((buf[0].toLong() and 0xFF) shl 24) or
      ((buf[1].toLong() and 0xFF) shl 16) or
      ((buf[2].toLong() and 0xFF) shl 8) or
      (buf[3].toLong() and 0xFF)
  }

  private fun readU64Be(raf: RandomAccessFile): Long {
    val high = readU32Be(raf)
    val low = readU32Be(raf)
    if (high < 0 || low < 0) return -1
    return (high shl 32) or low
  }
}
