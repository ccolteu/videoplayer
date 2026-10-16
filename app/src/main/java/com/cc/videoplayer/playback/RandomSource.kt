package com.cc.videoplayer.playback

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

internal class RandomSource private constructor(
  private val channel: FileChannel,
  private val stream: FileInputStream,
  private val descriptor: ParcelFileDescriptor?,
) : Closeable {
  private var position = 0L

  fun length(): Long = channel.size()

  fun seek(offset: Long) {
    position = offset
  }

  fun read(buf: ByteArray): Int {
    if (buf.isEmpty()) return 0
    val n = channel.read(ByteBuffer.wrap(buf), position)
    if (n > 0) position += n
    return n
  }

  fun readFully(buf: ByteArray) {
    var filled = 0
    while (filled < buf.size) {
      val wrap = ByteBuffer.wrap(buf, filled, buf.size - filled)
      val n = channel.read(wrap, position)
      if (n < 0) throw EOFException()
      if (n == 0) throw EOFException()
      position += n
      filled += n
    }
  }

  override fun close() {
    runCatching { stream.close() }
    runCatching { descriptor?.close() }
  }

  companion object {
    fun open(file: File): RandomSource {
      val stream = FileInputStream(file)
      return RandomSource(stream.channel, stream, null)
    }

    fun open(context: Context, location: String): RandomSource {
      val uri = Uri.parse(location)
      if (uri.scheme != "content") return open(File(location))
      val descriptor =
        context.contentResolver.openFileDescriptor(uri, "r")
          ?: throw IllegalStateException("Can't open this file.")
      val stream = FileInputStream(descriptor.fileDescriptor)
      return RandomSource(stream.channel, stream, descriptor)
    }
  }
}
