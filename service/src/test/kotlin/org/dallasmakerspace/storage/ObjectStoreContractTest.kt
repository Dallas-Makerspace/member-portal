package org.dallasmakerspace.storage

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Behavior every [IObjectStore] implementation must satisfy. Run against [InMemoryObjectStore]
 * here; an S3/MinIO-backed run of the same contract is left for an opt-in integration suite (S3
 * itself isn't exercised by unit tests).
 */
abstract class ObjectStoreContractTest {

  abstract fun createStore(): IObjectStore

  @Test
  fun `put then get returns the same bytes and content type`() = runBlocking {
    val store = createStore()
    val key = ObjectKey("a/b.jpg")
    store.put(key, byteArrayOf(1, 2, 3), "image/jpeg")

    val result = store.get(key)

    assertContentEquals(byteArrayOf(1, 2, 3), result?.bytes)
    assertEquals("image/jpeg", result?.stored?.contentType)
  }

  @Test
  fun `get on a missing key returns null`() = runBlocking {
    val store = createStore()
    assertNull(store.get(ObjectKey("does/not/exist.jpg")))
  }

  @Test
  fun `head on a missing key returns null`() = runBlocking {
    val store = createStore()
    assertNull(store.head(ObjectKey("does/not/exist.jpg")))
  }

  @Test
  fun `head returns metadata without bytes`() = runBlocking {
    val store = createStore()
    val key = ObjectKey("a/b.jpg")
    store.put(key, byteArrayOf(1, 2, 3, 4), "image/jpeg")

    val head = store.head(key)

    assertEquals(4L, head?.sizeBytes)
  }

  @Test
  fun `delete removes the object`() = runBlocking {
    val store = createStore()
    val key = ObjectKey("a/b.jpg")
    store.put(key, byteArrayOf(1), "image/jpeg")

    store.delete(key)

    assertNull(store.get(key))
  }

  @Test
  fun `delete of a missing key does not throw`() = runBlocking {
    val store = createStore()
    store.delete(ObjectKey("does/not/exist.jpg"))
  }

  @Test
  fun `put overwrites an existing key`() = runBlocking {
    val store = createStore()
    val key = ObjectKey("a/b.jpg")
    store.put(key, byteArrayOf(1), "image/jpeg")

    store.put(key, byteArrayOf(2, 2), "image/jpeg")

    assertContentEquals(byteArrayOf(2, 2), store.get(key)?.bytes)
  }

  @Test
  fun `list returns only keys under the prefix`() = runBlocking {
    val store = createStore()
    store.put(ObjectKey("jdoe/one.jpg"), byteArrayOf(1), "image/jpeg")
    store.put(ObjectKey("jdoe/two.jpg"), byteArrayOf(1), "image/jpeg")
    store.put(ObjectKey("asmith/one.jpg"), byteArrayOf(1), "image/jpeg")

    val keys = store.list(ObjectKey("jdoe")).map { it.key.value }.toSet()

    assertEquals(setOf("jdoe/one.jpg", "jdoe/two.jpg"), keys)
  }

  @Test
  fun `list respects the limit`() = runBlocking {
    val store = createStore()
    repeat(3) { store.put(ObjectKey("jdoe/$it.jpg"), byteArrayOf(1), "image/jpeg") }

    assertTrue(store.list(ObjectKey("jdoe"), limit = 2).size <= 2)
  }
}

class InMemoryObjectStoreContractTest : ObjectStoreContractTest() {
  override fun createStore(): IObjectStore = InMemoryObjectStore()
}
