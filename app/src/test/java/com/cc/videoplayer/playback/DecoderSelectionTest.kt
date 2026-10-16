package com.cc.videoplayer.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderSelectionTest {
  private val softwareHevc = ListedDecoder(hardware = false, mime = DecoderSelection.HEVC, profiles = listOf(0x1, 0x4))
  private val qualcommHevc = ListedDecoder(hardware = true, mime = DecoderSelection.HEVC, profiles = listOf(0x1, 0x4))
  private val main10Hevc = ListedDecoder(hardware = true, mime = DecoderSelection.HEVC, profiles = listOf(0x1, 0x2, 0x1000))
  private val softwareMpeg4 = ListedDecoder(hardware = false, mime = "video/mp4v-es", profiles = listOf(0x1))
  private val highAvc = ListedDecoder(hardware = true, mime = DecoderSelection.AVC, profiles = listOf(0x01, 0x02, 0x08))

  @Test
  fun unknownFilesUseFfmpeg() {
    val plan = DecoderSelection.plan(null, listOf(qualcommHevc))
    assertFalse(plan.useMediaCodec)
    assertFalse(plan.rewriteHevcMain10)
  }

  @Test
  fun xvidUsesFfmpegWhenOnlySoftwareMpeg4Exists() {
    val plan = DecoderSelection.plan(VideoStream("video/mp4v-es", 0), listOf(softwareMpeg4, qualcommHevc))
    assertFalse(plan.useMediaCodec)
  }

  @Test
  fun softwareDecoderListedFirstIsNotUsed() {
    val plan =
      DecoderSelection.plan(
        VideoStream(DecoderSelection.HEVC, 1),
        listOf(softwareHevc, qualcommHevc),
      )
    assertFalse(plan.useMediaCodec)
  }

  @Test
  fun main10IsRewrittenWhenHardwareOnlyAdvertisesMain() {
    val plan = DecoderSelection.plan(VideoStream(DecoderSelection.HEVC, 2), listOf(qualcommHevc, softwareHevc))
    assertTrue(plan.useMediaCodec)
    assertTrue(plan.rewriteHevcMain10)
    assertTrue(DecoderSelection.hevcMain10Quirk(listOf(qualcommHevc)))
  }

  @Test
  fun main10StaysUntouchedWhenTheDecoderAdvertisesIt() {
    val plan = DecoderSelection.plan(VideoStream(DecoderSelection.HEVC, 2), listOf(main10Hevc))
    assertTrue(plan.useMediaCodec)
    assertFalse(plan.rewriteHevcMain10)
    assertFalse(DecoderSelection.hevcMain10Quirk(listOf(main10Hevc)))
  }

  @Test
  fun main10WithoutHardwareUsesFfmpeg() {
    val plan = DecoderSelection.plan(VideoStream(DecoderSelection.HEVC, 2), listOf(softwareHevc))
    assertFalse(plan.useMediaCodec)
    assertFalse(plan.rewriteHevcMain10)
  }

  @Test
  fun highProfileAvcUsesHardwareWhenThatProfileIsAdvertised() {
    val plan = DecoderSelection.plan(VideoStream(DecoderSelection.AVC, 100), listOf(highAvc))
    assertTrue(plan.useMediaCodec)
    assertFalse(plan.rewriteHevcMain10)
  }

  @Test
  fun highProfileAvcUsesFfmpegWhenTheDecoderOnlyAdvertisesBaseline() {
    val baseline = ListedDecoder(hardware = true, mime = DecoderSelection.AVC, profiles = listOf(0x01))
    val plan = DecoderSelection.plan(VideoStream(DecoderSelection.AVC, 100), listOf(baseline))
    assertFalse(plan.useMediaCodec)
  }

  @Test
  fun containersReportTheVideoProfile() {
    val hevc = VideoDecoderChoice.describe(mp4WithHevcProfile(2))
    assertEquals(VideoStream(DecoderSelection.HEVC, 2), hevc)
    val avc = VideoDecoderChoice.describe(mp4WithAvcProfile(100))
    assertEquals(VideoStream(DecoderSelection.AVC, 100), avc)
    val xvid = VideoDecoderChoice.describe(aviWithFourcc("xvid"))
    assertEquals(VideoStream("video/mp4v-es", 0), xvid)
    assertNull(VideoDecoderChoice.describe(tempFile(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12))))
  }

  private fun mp4WithHevcProfile(profile: Int): File {
    val hvcc = ByteArray(23)
    hvcc[0] = 1
    hvcc[1] = profile.toByte()
    val entry = box("hev1", ByteArray(8) + box("hvcC", hvcc))
    return mp4WithVideoEntry(entry)
  }

  private fun mp4WithAvcProfile(profile: Int): File {
    val avcc = byteArrayOf(1, profile.toByte(), 0, 1)
    val entry = box("avc1", ByteArray(8) + box("avcC", avcc))
    return mp4WithVideoEntry(entry)
  }

  private fun mp4WithVideoEntry(entry: ByteArray): File {
    val hdlr = box("hdlr", ByteArray(8) + "vide".encodeToByteArray())
    val stsd = box("stsd", ByteArray(4) + u32(1) + entry)
    val stbl = box("stbl", stsd)
    val minf = box("minf", stbl)
    val mdia = box("mdia", hdlr + minf)
    val trak = box("trak", mdia)
    val moov = box("moov", trak)
    val ftyp = box("ftyp", "isom".encodeToByteArray())
    return tempFile(ftyp + moov)
  }

  private fun aviWithFourcc(fourcc: String): File {
    val strh = boxLe("strh", "vids".encodeToByteArray() + fourcc.encodeToByteArray())
    val strl = listLe("strl", strh)
    val hdrl = listLe("hdrl", strl)
    val body = "AVI ".encodeToByteArray() + hdrl
    val size = body.size
    val riff = "RIFF".encodeToByteArray() + u32Le(size) + body
    return tempFile(riff)
  }

  private fun tempFile(bytes: ByteArray): File {
    val file = File.createTempFile("decoder-choice", ".bin")
    file.writeBytes(bytes)
    file.deleteOnExit()
    return file
  }

  private fun box(type: String, payload: ByteArray): ByteArray = u32(8 + payload.size) + type.encodeToByteArray() + payload

  private fun boxLe(type: String, payload: ByteArray): ByteArray = type.encodeToByteArray() + u32Le(payload.size) + payload

  private fun listLe(type: String, payload: ByteArray): ByteArray {
    val body = type.encodeToByteArray() + payload
    return "LIST".encodeToByteArray() + u32Le(body.size) + body
  }

  private fun u32(value: Int): ByteArray =
    byteArrayOf(
      (value ushr 24).toByte(),
      (value ushr 16).toByte(),
      (value ushr 8).toByte(),
      value.toByte(),
    )

  private fun u32Le(value: Int): ByteArray =
    byteArrayOf(
      value.toByte(),
      (value ushr 8).toByte(),
      (value ushr 16).toByte(),
      (value ushr 24).toByte(),
    )
}
