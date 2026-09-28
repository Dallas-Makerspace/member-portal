package org.dallasmakerspace.members

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.DMSMember
import org.dallasmakerspace.storage.InMemoryObjectStore
import org.dallasmakerspace.storage.NamespacedObjectStore
import org.dallasmakerspace.storage.ObjectKey
import org.dallasmakerspace.storage.ObjectStoreFactory
import org.dallasmakerspace.storage.StorageNamespace
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ProfilePhotoServiceTest {

  private lateinit var repo: IMemberRepository
  // Scoped to the same namespace/prefix ProfilePhotoService itself writes through, so keys the
  // service hands back (already unscoped by NamespacedObjectStore) resolve correctly here too.
  private lateinit var store: NamespacedObjectStore
  private lateinit var service: ProfilePhotoService

  @Before
  fun setUp() {
    repo = mock()
    val objectStoreFactory = ObjectStoreFactory(InMemoryObjectStore())
    store = objectStoreFactory.forNamespace(StorageNamespace.PROFILE_PHOTOS)
    service = ProfilePhotoService(repo, objectStoreFactory, LoggerFactory())
    runBlocking {
      whenever(repo.getMemberOrInsert(any(), anyOrNull()))
          .thenReturn(DMSMember(id = 1, username = "jdoe"))
    }
  }

  @Test
  fun `save re-encodes to a 512x512 JPEG and stores it`() = runBlocking {
    whenever(repo.setProfilePhoto(eq("jdoe"), any(), any())).thenReturn(null)

    val photoUrl = service.save("jdoe", jpegBytes(300, 300))

    assertTrue(photoUrl.startsWith("/avatar/@jdoe?v="))
    val keyCaptor = argumentCaptor<String>()
    verify(repo).setProfilePhoto(eq("jdoe"), keyCaptor.capture(), any())
    val stored = store.get(ObjectKey(keyCaptor.firstValue))
    assertNotNull(stored)
    assertEquals("image/jpeg", stored.stored.contentType)
    val reencoded = ImageIO.read(ByteArrayInputStream(stored.bytes))
    assertEquals(512, reencoded.width)
    assertEquals(512, reencoded.height)
  }

  @Test
  fun `save deletes the previously-stored photo when replacing it`() = runBlocking {
    val oldKey = "jdoe/old.jpg"
    store.put(ObjectKey(oldKey), byteArrayOf(1), "image/jpeg")
    whenever(repo.setProfilePhoto(eq("jdoe"), any(), any())).thenReturn(oldKey)

    service.save("jdoe", jpegBytes(300, 300))

    assertNull(store.get(ObjectKey(oldKey)))
  }

  @Test
  fun `save rejects a file that is not JPEG or PNG`() = runBlocking {
    assertFailsWith<ProfilePhotoValidationException> {
      service.save("jdoe", byteArrayOf(1, 2, 3, 4))
    }
    Unit
  }

  @Test
  fun `save rejects an image below the minimum dimension`() = runBlocking {
    assertFailsWith<ProfilePhotoValidationException> { service.save("jdoe", jpegBytes(100, 100)) }
    Unit
  }

  @Test
  fun `save rejects a non-square image`() = runBlocking {
    assertFailsWith<ProfilePhotoValidationException> { service.save("jdoe", jpegBytes(400, 200)) }
    Unit
  }

  @Test
  fun `save rejects oversized declared dimensions without decoding the pixels`() = runBlocking {
    val error =
        assertFailsWith<ProfilePhotoValidationException> {
          service.save("jdoe", pngWithDeclaredSize(pngBytes(300, 300), 60_000, 60_000))
        }
    assertTrue(error.message!!.contains("too large"))
  }

  @Test
  fun `save returns a different photo URL for each upload`() = runBlocking {
    whenever(repo.setProfilePhoto(eq("jdoe"), any(), any())).thenReturn(null)
    val first = service.save("jdoe", jpegBytes(300, 300))
    val second = service.save("jdoe", jpegBytes(300, 300))
    assertTrue(first != second)
  }

  @Test
  fun `save rejects a body larger than the upload limit`() = runBlocking {
    assertFailsWith<ProfilePhotoValidationException> {
      service.save("jdoe", ByteArray(3 * 1024 * 1024))
    }
    Unit
  }

  @Test
  fun `save enforces the daily upload rate limit`() = runBlocking {
    whenever(repo.setProfilePhoto(eq("jdoe"), any(), any())).thenReturn(null)
    repeat(10) { service.save("jdoe", jpegBytes(300, 300)) }

    assertFailsWith<ProfilePhotoRateLimitedException> { service.save("jdoe", jpegBytes(300, 300)) }
    Unit
  }

  @Test
  fun `remove deletes the stored object`() = runBlocking {
    val key = "jdoe/x.jpg"
    store.put(ObjectKey(key), byteArrayOf(1), "image/jpeg")
    whenever(repo.clearProfilePhoto("jdoe")).thenReturn(key)

    service.remove("jdoe")

    assertNull(store.get(ObjectKey(key)))
  }

  @Test
  fun `remove is a no-op when the member has no photo`() = runBlocking {
    whenever(repo.clearProfilePhoto("jdoe")).thenReturn(null)
    service.remove("jdoe")
  }

  @Test
  fun `get returns bytes and content type for an existing photo`() = runBlocking {
    val key = "jdoe/x.jpg"
    store.put(ObjectKey(key), byteArrayOf(5, 6), "image/jpeg")
    whenever(repo.getProfilePhotoKey("jdoe")).thenReturn(key)

    val result = service.get("jdoe")

    assertEquals("image/jpeg", result?.second)
    assertEquals(listOf<Byte>(5, 6), result?.first?.toList())
  }

  @Test
  fun `get returns null when the member has no photo`() = runBlocking {
    whenever(repo.getProfilePhotoKey("jdoe")).thenReturn(null)
    assertNull(service.get("jdoe"))
  }
}

private fun jpegBytes(width: Int, height: Int): ByteArray = imageBytes(width, height, "jpg")

private fun pngBytes(width: Int, height: Int): ByteArray = imageBytes(width, height, "png")

private fun imageBytes(width: Int, height: Int, format: String): ByteArray {
  val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
  val graphics = image.createGraphics()
  graphics.color = Color.BLUE
  graphics.fillRect(0, 0, width, height)
  graphics.dispose()
  val output = ByteArrayOutputStream()
  ImageIO.write(image, format, output)
  return output.toByteArray()
}

/** Rewrites a PNG's IHDR width/height (and its CRC) - a tiny file claiming a huge canvas. */
private fun pngWithDeclaredSize(png: ByteArray, width: Int, height: Int): ByteArray {
  val patched = png.copyOf()
  val buffer = java.nio.ByteBuffer.wrap(patched)
  buffer.putInt(16, width)
  buffer.putInt(20, height)
  val crc = java.util.zip.CRC32().apply { update(patched, 12, 17) }
  buffer.putInt(29, crc.value.toInt())
  return patched
}
