package org.dallasmakerspace.dashboards

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dallasmakerspace.storage.ObjectKey
import org.dallasmakerspace.storage.ObjectStoreFactory
import org.dallasmakerspace.storage.StorageNamespace

/**
 * Pictures for dashboard text cards. Uploads are decoded, shrunk to what a TV can show and
 * re-encoded as JPEG, so whatever was in the original file (metadata, odd formats) is dropped. Keys
 * are `{committeeId}/{uuid}.jpg` in the shared object-store bucket; the widget stores only the
 * `{uuid}.jpg` part, so a card can never point at another committee's picture.
 */
@Singleton
class DashboardImageService @Inject constructor(objectStoreFactory: ObjectStoreFactory) {
  private val store = objectStoreFactory.forNamespace(StorageNamespace.DASHBOARD_IMAGES)

  /** Stores a processed copy of [bytes] for [committeeId] and returns the file name to keep. */
  suspend fun save(committeeId: Int, bytes: ByteArray): String {
    val processed = withContext(Dispatchers.IO) { process(bytes) }
    val file = "${UUID.randomUUID()}.jpg"
    store.put(ObjectKey("$committeeId/$file"), processed, JPEG)
    return file
  }

  /** The stored bytes and content type of [file], or null if it isn't there or isn't a valid name. */
  suspend fun get(committeeId: Int, file: String): Pair<ByteArray, String>? {
    if (!isValidFile(file)) return null
    return store.get(ObjectKey("$committeeId/$file"))?.let { it.bytes to it.stored.contentType }
  }

  @Suppress("MagicNumber", "ThrowsCount", "SwallowedException", "TooGenericExceptionCaught")
  companion object {
    const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024
    const val JPEG = "image/jpeg"
    private const val MAX_EDGE = 1600
    private const val MAX_PIXELS = 40_000_000L
    private const val JPEG_QUALITY = 0.85f
    private val FILE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg$")

    fun isValidFile(file: String): Boolean = FILE.matches(file)

    /** Decodes a JPEG or PNG, shrinks it to [MAX_EDGE], and returns it re-encoded as JPEG. */
    fun process(bytes: ByteArray): ByteArray {
      if (bytes.size > MAX_UPLOAD_BYTES) throw DashboardException("That picture is over 5 MB")
      if (!isJpeg(bytes) && !isPng(bytes)) throw DashboardException("Use a JPEG or PNG picture")
      val source = decode(bytes)
      val scaled = shrink(source)
      return encode(scaled)
    }

    private fun isJpeg(b: ByteArray) =
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()

    private fun isPng(b: ByteArray) =
        b.size > 8 &&
            b[0] == 0x89.toByte() &&
            b[1] == 'P'.code.toByte() &&
            b[2] == 'N'.code.toByte() &&
            b[3] == 'G'.code.toByte()

    private fun unreadable() = DashboardException("That file couldn't be read as a picture")

    private fun decode(bytes: ByteArray): BufferedImage {
      val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: throw unreadable()
      stream.use {
        val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: throw unreadable()
        try {
          reader.input = stream
          val pixels = reader.getWidth(0).toLong() * reader.getHeight(0).toLong()
          if (pixels > MAX_PIXELS) throw DashboardException("That picture is too large")
          return reader.read(0) ?: throw unreadable()
        } catch (e: DashboardException) {
          throw e
        } catch (e: java.io.IOException) {
          throw unreadable()
        } catch (e: RuntimeException) {
          throw unreadable()
        } finally {
          reader.dispose()
        }
      }
    }

    private fun shrink(source: BufferedImage): BufferedImage {
      val longest = maxOf(source.width, source.height)
      val scale = if (longest > MAX_EDGE) MAX_EDGE.toDouble() / longest else 1.0
      val width = maxOf(1, (source.width * scale).toInt())
      val height = maxOf(1, (source.height * scale).toInt())
      val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
      val g = out.createGraphics()
      try {
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(source, 0, 0, width, height, null)
      } finally {
        g.dispose()
      }
      return out
    }

    private fun encode(image: BufferedImage): ByteArray {
      val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
      val output = ByteArrayOutputStream()
      try {
        ImageIO.createImageOutputStream(output).use { stream ->
          writer.output = stream
          val params =
              writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = JPEG_QUALITY
              }
          writer.write(null, IIOImage(image, null, null), params)
        }
      } finally {
        writer.dispose()
      }
      return output.toByteArray()
    }
  }
}
