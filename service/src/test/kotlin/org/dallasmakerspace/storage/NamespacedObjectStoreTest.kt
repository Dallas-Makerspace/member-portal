package org.dallasmakerspace.storage

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import org.junit.Test

class NamespacedObjectStoreTest {

  private val delegate = InMemoryObjectStore()
  private val store = NamespacedObjectStore(delegate, StorageNamespace.PROFILE_PHOTOS)

  @Test
  fun `put writes under the namespace prefix on the delegate`() = runBlocking {
    store.put(ObjectKey("jdoe/photo.jpg"), byteArrayOf(1), "image/jpeg")

    val raw = delegate.get(ObjectKey("profile-photos/jdoe/photo.jpg"))

    assertContentEquals(byteArrayOf(1), raw?.bytes)
  }

  @Test
  fun `get returns the key without the namespace prefix`() = runBlocking {
    store.put(ObjectKey("jdoe/photo.jpg"), byteArrayOf(1), "image/jpeg")

    val result = store.get(ObjectKey("jdoe/photo.jpg"))

    assertEquals("jdoe/photo.jpg", result?.stored?.key?.value)
  }

  @Test
  fun `list strips the namespace prefix from returned keys`() = runBlocking {
    store.put(ObjectKey("jdoe/one.jpg"), byteArrayOf(1), "image/jpeg")

    val keys = store.list(ObjectKey("jdoe")).map { it.key.value }

    assertEquals(listOf("jdoe/one.jpg"), keys)
  }

  @Test
  fun `a namespace cannot read another namespace's key by embedding a traversal`() = runBlocking {
    try {
      store.get(ObjectKey("../other-namespace/secret.jpg"))
      fail("expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message.orEmpty().contains(".."))
    }
  }
}
