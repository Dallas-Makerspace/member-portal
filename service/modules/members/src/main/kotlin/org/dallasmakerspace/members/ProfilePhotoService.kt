package org.dallasmakerspace.members

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.profilePhotoUrl
import org.dallasmakerspace.storage.ObjectKey
import org.dallasmakerspace.storage.ObjectStoreFactory
import org.dallasmakerspace.storage.StorageNamespace

private const val MAX_UPLOAD_BYTES = 2 * 1024 * 1024
private const val MIN_DIMENSION = 256
private const val MAX_DIMENSION = 4096
private const val MIN_ASPECT_RATIO = 0.95
private const val MAX_ASPECT_RATIO = 1.05
private const val OUTPUT_SIZE = 512
private const val JPEG_QUALITY = 0.85f
private const val RATE_LIMIT_UPLOADS_PER_WINDOW = 10
private const val RATE_LIMIT_WINDOW_HOURS = 24L
private val RATE_LIMIT_WINDOW: Duration = Duration.ofHours(RATE_LIMIT_WINDOW_HOURS)
const val PHOTO_TOO_LARGE_MESSAGE = "Photo must be 2 MB or smaller"

/**
 * Thrown when an uploaded image fails a validation rule; [message] is safe to show to the member.
 */
class ProfilePhotoValidationException(message: String) : IllegalArgumentException(message)

/** Thrown when a member has uploaded photos too many times recently. */
class ProfilePhotoRateLimitedException(message: String) : IllegalStateException(message)

/**
 * Validates, re-encodes and stores member profile photos, and serves them back. Re-encoding to a
 * fixed-size JPEG both normalizes the image and strips all metadata (EXIF/GPS) from the original.
 */
@Suppress("TooManyFunctions")
@Singleton
class ProfilePhotoService
@Inject
constructor(
    private val memberRepository: IMemberRepository,
    objectStoreFactory: ObjectStoreFactory,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)
  private val store = objectStoreFactory.forNamespace(StorageNamespace.PROFILE_PHOTOS)

  // Per-process rate limiting is sufficient here: it's a UX guardrail against accidental loops,
  // not a security control, and the service runs as a single instance.
  private val recentUploads = ConcurrentHashMap<String, MutableList<Instant>>()

  @Suppress("TooGenericExceptionCaught")
  suspend fun save(username: String, bytes: ByteArray): String {
    checkRateLimit(username)
    if (bytes.size > MAX_UPLOAD_BYTES) {
      throw ProfilePhotoValidationException(PHOTO_TOO_LARGE_MESSAGE)
    }
    val reencoded = withContext(Dispatchers.IO) { decodeValidateAndReencode(bytes) }

    memberRepository.getMemberOrInsert(username, null)
    val key = ObjectKey("$username/${UUID.randomUUID()}.jpg")
    store.put(key, reencoded, "image/jpeg")

    val updatedAt = Instant.now()
    val previousKey =
        try {
          memberRepository.setProfilePhoto(username, key.value, updatedAt)
        } catch (e: Exception) {
          // The DB write is the point of no return for the new object - clean it up before
          // propagating, or it's orphaned in storage forever.
          runCatching { store.delete(key) }
              .onFailure { log.warn("Failed to clean up orphaned photo key=${key.value}", it) }
          throw e
        }
    deleteIfPresent(previousKey)
    recordUpload(username)
    return profilePhotoUrl(username, key.value)
  }

  suspend fun remove(username: String) {
    val previousKey = memberRepository.clearProfilePhoto(username)
    deleteIfPresent(previousKey)
  }

  suspend fun get(username: String): Pair<ByteArray, String>? {
    val key = memberRepository.getProfilePhotoKey(username) ?: return null
    return store.get(ObjectKey(key))?.let { it.bytes to it.stored.contentType }
  }

  private suspend fun deleteIfPresent(key: String?) {
    if (key == null) return
    runCatching { store.delete(ObjectKey(key)) }
        .onFailure { log.warn("Failed to delete superseded profile photo key=$key", it) }
  }

  private fun checkRateLimit(username: String) {
    val now = Instant.now()
    val windowStart = now.minus(RATE_LIMIT_WINDOW)
    val recent =
        recentUploads.compute(username) { _, existing ->
          (existing ?: mutableListOf()).apply { removeAll { it.isBefore(windowStart) } }
        }
    if (recent != null && recent.size >= RATE_LIMIT_UPLOADS_PER_WINDOW) {
      throw ProfilePhotoRateLimitedException(
          "You've changed your profile photo too many times today. Please try again tomorrow.")
    }
  }

  private fun recordUpload(username: String) {
    // Mutate under compute (like checkRateLimit) rather than computeIfAbsent+add - the latter's
    // .add() runs outside the map's per-key atomicity and can race a concurrent checkRateLimit's
    // removeAll on the same plain ArrayList.
    recentUploads.compute(username) { _, existing ->
      (existing ?: mutableListOf()).apply { add(Instant.now()) }
    }
  }

  private fun decodeValidateAndReencode(bytes: ByteArray): ByteArray {
    requireJpegOrPng(bytes)
    return reencode(decodeValidated(bytes))
  }

  private fun requireJpegOrPng(bytes: ByteArray) {
    if (!isJpegOrPng(bytes)) {
      throw ProfilePhotoValidationException("Only JPEG and PNG photos are supported")
    }
  }

  // Checks dimensions from the header before decoding pixels, so a small file declaring a huge
  // canvas (decompression bomb) is rejected without allocating it.
  @Suppress("ThrowsCount")
  private fun decodeValidated(bytes: ByteArray): BufferedImage {
    val input =
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: throw unreadableImage()
    input.use {
      val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: throw unreadableImage()
      try {
        reader.input = input
        val (width, height) =
            runCatching { reader.getWidth(0) to reader.getHeight(0) }.getOrNull()
                ?: throw unreadableImage()
        requireValidDimensions(width, height)
        requireSquareAspectRatio(width, height)
        return runCatching { reader.read(0) }.getOrNull() ?: throw unreadableImage()
      } finally {
        reader.dispose()
      }
    }
  }

  private fun unreadableImage() = ProfilePhotoValidationException("Could not read this file as an image")

  private fun requireValidDimensions(width: Int, height: Int) {
    if (width < MIN_DIMENSION || height < MIN_DIMENSION) {
      throw ProfilePhotoValidationException(
          "Photo is too small (minimum ${MIN_DIMENSION}x$MIN_DIMENSION)")
    }
    if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
      throw ProfilePhotoValidationException(
          "Photo is too large (maximum ${MAX_DIMENSION}x$MAX_DIMENSION)")
    }
  }

  private fun requireSquareAspectRatio(width: Int, height: Int) {
    val aspectRatio = width.toDouble() / height.toDouble()
    if (aspectRatio < MIN_ASPECT_RATIO || aspectRatio > MAX_ASPECT_RATIO) {
      throw ProfilePhotoValidationException("Photo must be square")
    }
  }

  // Sniffs magic bytes rather than trusting the declared Content-Type, which the client controls.
  @Suppress("MagicNumber")
  private fun isJpegOrPng(bytes: ByteArray): Boolean {
    val jpegMagic = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    return bytes.size >= jpegMagic.size &&
        bytes.copyOfRange(0, jpegMagic.size).contentEquals(jpegMagic) ||
        bytes.size >= pngMagic.size && bytes.copyOfRange(0, pngMagic.size).contentEquals(pngMagic)
  }

  private fun reencode(source: BufferedImage): ByteArray {
    val square = BufferedImage(OUTPUT_SIZE, OUTPUT_SIZE, BufferedImage.TYPE_INT_RGB)
    val graphics = square.createGraphics()
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
      graphics.color = Color.WHITE
      graphics.fillRect(0, 0, OUTPUT_SIZE, OUTPUT_SIZE)
      graphics.drawImage(source, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE, null)
    } finally {
      graphics.dispose()
    }

    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val params =
        writer.defaultWriteParam.apply {
          compressionMode = ImageWriteParam.MODE_EXPLICIT
          compressionQuality = JPEG_QUALITY
        }
    val output = ByteArrayOutputStream()
    ImageIO.createImageOutputStream(output).use { imageOutputStream ->
      writer.output = imageOutputStream
      writer.write(null, IIOImage(square, null, null), params)
    }
    writer.dispose()
    return output.toByteArray()
  }
}
