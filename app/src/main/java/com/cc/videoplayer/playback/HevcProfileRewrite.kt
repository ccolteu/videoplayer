package com.cc.videoplayer.playback

import android.content.Context
import android.media.MediaCodecList
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * This phone's HEVC decoder decodes Main 10, but only advertises Main (0x1) and
 * Main Still (0x4). VLC refuses a decoder whose OMX profiles do not include
 * Main 10 (0x2 / 0x1000), then FFmpeg emits 10-bit frames the display cannot
 * convert. Rewriting the hvcC general_profile_idc to Main makes VLC select
 * MediaCodec. The SPS bit depth is left unchanged, so the decoder still outputs
 * 10-bit onto the Surface.
 */
internal class HevcProfileRewrite private constructor(
  private val channel: FileInputStream,
  private val length: Long,
  private val patches: List<Patch>,
  private val thread: HandlerThread,
  private val descriptor: ParcelFileDescriptor,
) {
  val fileDescriptor = descriptor.fileDescriptor

  fun close() {
    runCatching { descriptor.close() }
  }

  private class Reader(
    private val channel: FileInputStream,
    private val length: Long,
    private val patches: List<Patch>,
    private val thread: HandlerThread,
  ) : ProxyFileDescriptorCallback() {
    private val file = channel.channel

    override fun onGetSize(): Long = length

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
      if (offset < 0) throw ErrnoException("onRead", OsConstants.EINVAL)
      if (offset >= length || size <= 0) return 0
      val want = minOf(size, data.size, (length - offset).toInt())
      if (want <= 0) return 0
      val buf = ByteBuffer.wrap(data, 0, want)
      var read = 0
      synchronized(file) {
        while (read < want) {
          val n = file.read(buf, offset + read)
          if (n <= 0) break
          read += n
        }
      }
      if (read <= 0) return 0
      val end = offset + read
      for (patch in patches) {
        if (patch.offset in offset until end) data[(patch.offset - offset).toInt()] = patch.value
      }
      return read
    }

    override fun onRelease() {
      runCatching { channel.close() }
      thread.quitSafely()
    }
  }

  private data class Patch(val offset: Long, val value: Byte)

  companion object {
    private const val TAG = "VideoPlayer"
    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "udta", "mvex", "moof", "traf")
    private val hevcCodec = "V_MPEGH/ISO/HEVC".encodeToByteArray()

    fun open(context: Context, path: String): HevcProfileRewrite? {
      if (android.os.Build.VERSION.SDK_INT < 26) return null
      val file = File(path)
      if (!file.isFile) return null
      val patches = runCatching { findPatches(file) }.getOrElse {
        Log.w(TAG, "HEVC profile scan failed", it)
        return null
      }
      if (patches.isEmpty()) return null
      return runCatching { start(context, file, patches) }.getOrElse {
        Log.w(TAG, "HEVC profile rewrite failed", it)
        null
      }
    }

    private fun start(context: Context, file: File, patches: List<Patch>): HevcProfileRewrite {
      val input = FileInputStream(file)
      val thread = HandlerThread("hevc-profile")
      thread.start()
      try {
        val storage = context.getSystemService(StorageManager::class.java)
        val descriptor =
          storage.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            Reader(input, file.length(), patches, thread),
            Handler(thread.looper),
          )
        Log.i(TAG, "HEVC profile presented as Main (${patches.size})")
        return HevcProfileRewrite(input, file.length(), patches, thread, descriptor)
      } catch (error: Throwable) {
        thread.quitSafely()
        runCatching { input.close() }
        throw error
      }
    }

    private fun findPatches(file: File): List<Patch> {
      if (!deviceNeedsRewrite()) return emptyList()
      RandomAccessFile(file, "r").use { raf ->
        if (raf.length() < 12) return emptyList()
        val kind = ByteArray(12)
        raf.readFully(kind)
        val mp4 = kind.copyOfRange(4, 8).decodeToString() == "ftyp"
        val mkv = kind[0] == 0x1A.toByte() && kind[1] == 0x45.toByte() && kind[2] == 0xDF.toByte()
        val found = ArrayList<Patch>()
        if (mp4) walk(raf, 0, raf.length(), found, 0)
        if (mkv) scanMatroska(raf, found)
        return found
      }
    }

    private fun deviceNeedsRewrite(): Boolean {
      val profiles = deviceHevcProfiles() ?: return false
      return 0x2 !in profiles && 0x1000 !in profiles
    }

    private fun deviceHevcProfiles(): IntArray? {
      return runCatching {
        val found = HashSet<Int>()
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
          if (info.isEncoder) continue
          val types = runCatching { info.supportedTypes }.getOrNull() ?: continue
          if (types.none { it.equals("video/hevc", ignoreCase = true) }) continue
          val levels =
            runCatching { info.getCapabilitiesForType("video/hevc").profileLevels }.getOrNull() ?: continue
          for (level in levels) found.add(level.profile)
        }
        found.toIntArray()
      }.getOrNull()
    }

    private fun walk(raf: RandomAccessFile, start: Long, end: Long, patches: MutableList<Patch>, depth: Int) {
      if (depth > 12 || end - start < 8) return
      var pos = start
      while (pos + 8 <= end) {
        raf.seek(pos)
        val size32 = readU32(raf)
        val type = readType(raf)
        if (size32 < 0 || type == null) return
        var header = 8L
        var size = size32
        if (size32 == 1L) {
          if (pos + 16 > end) return
          size = readU64(raf)
          header = 16
        } else if (size32 == 0L) {
          size = end - pos
        }
        if (size < header || pos > end - size) return
        when {
          type in containers -> walk(raf, pos + header, pos + size, patches, depth + 1)
          type == "stsd" -> scanForHvcC(raf, pos, size, patches)
          type == "hvcC" -> consider(raf, pos + header, pos + size, patches)
        }
        pos += size
      }
    }

    private fun scanForHvcC(raf: RandomAccessFile, box: Long, size: Long, patches: MutableList<Patch>) {
      if (size < 16 || size > 2_000_000) return
      val buf = ByteArray(size.toInt())
      raf.seek(box)
      raf.readFully(buf)
      var index = 0
      while (index + 10 < buf.size) {
        if (buf[index + 4] == 'h'.code.toByte() &&
          buf[index + 5] == 'v'.code.toByte() &&
          buf[index + 6] == 'c'.code.toByte() &&
          buf[index + 7] == 'C'.code.toByte()
        ) {
          val boxSize = readU32(buf, index)
          if (boxSize in 31L..4096L && index + boxSize <= buf.size) {
            considerBytes(buf, index + 8, (index + boxSize).toInt(), box + index + 8, patches)
          }
        }
        index++
      }
    }

    private fun consider(raf: RandomAccessFile, payload: Long, end: Long, patches: MutableList<Patch>) {
      val length = end - payload
      if (length !in 23L..4096L) return
      val buf = ByteArray(length.toInt())
      raf.seek(payload)
      raf.readFully(buf)
      considerBytes(buf, 0, buf.size, payload, patches)
    }

    private fun considerBytes(buf: ByteArray, from: Int, to: Int, fileOffset: Long, patches: MutableList<Patch>) {
      if (to - from < 23 || buf[from] != 1.toByte()) return
      val profile = buf[from + 1].toInt() and 0x1F
      if (profile == 0 || profile == 1) return
      val patched = ((buf[from + 1].toInt() and 0xE0) or 0x01).toByte()
      val offset = fileOffset + 1
      if (patches.any { it.offset == offset }) return
      patches.add(Patch(offset, patched))
    }

    private fun scanMatroska(raf: RandomAccessFile, patches: MutableList<Patch>) {
      val window = minOf(raf.length(), 8L * 1024 * 1024).toInt()
      if (window < 64) return
      val buf = ByteArray(window)
      raf.seek(0)
      raf.readFully(buf)
      var from = 0
      while (from < buf.size) {
        val at = indexOf(buf, hevcCodec, from)
        if (at < 0) return
        val regionEnd = minOf(buf.size, at + 256 * 1024)
        var index = at
        while (index + 4 < regionEnd) {
          if (buf[index] == 0x63.toByte() && buf[index + 1] == 0xA2.toByte()) {
            val vint = readVint(buf, index + 2) ?: break
            val dataAt = index + 2 + vint.second
            if (vint.first in 23..4096 && dataAt + vint.first <= buf.size) {
              considerBytes(buf, dataAt, dataAt + vint.first, dataAt.toLong(), patches)
            }
          }
          index++
        }
        from = at + hevcCodec.size
      }
    }

    private fun readVint(buf: ByteArray, offset: Int): Pair<Int, Int>? {
      if (offset >= buf.size) return null
      val first = buf[offset].toInt() and 0xFF
      if (first == 0) return null
      var marker = 0x80
      var length = 1
      while (length <= 8 && first and marker == 0) {
        marker = marker shr 1
        length++
      }
      if (marker == 0 || offset + length > buf.size) return null
      var value = first and (marker - 1)
      for (extra in 1 until length) {
        value = (value shl 8) or (buf[offset + extra].toInt() and 0xFF)
        if (value < 0) return null
      }
      return value to length
    }

    private fun indexOf(buf: ByteArray, needle: ByteArray, start: Int): Int {
      val last = buf.size - needle.size
      var index = start
      while (index <= last) {
        var match = true
        for (n in needle.indices) {
          if (buf[index + n] != needle[n]) {
            match = false
            break
          }
        }
        if (match) return index
        index++
      }
      return -1
    }

    private fun readU32(raf: RandomAccessFile): Long {
      val buf = ByteArray(4)
      if (raf.read(buf) != 4) return -1
      return readU32(buf, 0)
    }

    private fun readU32(buf: ByteArray, offset: Int): Long {
      return ((buf[offset].toLong() and 0xFF) shl 24) or
        ((buf[offset + 1].toLong() and 0xFF) shl 16) or
        ((buf[offset + 2].toLong() and 0xFF) shl 8) or
        (buf[offset + 3].toLong() and 0xFF)
    }

    private fun readU64(raf: RandomAccessFile): Long {
      val high = readU32(raf)
      val low = readU32(raf)
      if (high < 0 || low < 0) return -1
      return (high shl 32) or low
    }

    private fun readType(raf: RandomAccessFile): String? {
      val buf = ByteArray(4)
      if (raf.read(buf) != 4) return null
      for (byte in buf) if (byte < 0x20 || byte > 0x7E) return null
      return buf.decodeToString()
    }
  }
}
