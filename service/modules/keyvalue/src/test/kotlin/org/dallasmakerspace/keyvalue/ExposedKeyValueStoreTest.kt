package org.dallasmakerspace.keyvalue

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.dallasmakerspace.core.DBMemberPortalConnection
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/** The store against H2 in MariaDB mode. */
class ExposedKeyValueStoreTest {

  companion object {
    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      val db =
          Database.connect(
              "jdbc:h2:mem:key-value;DB_CLOSE_DELAY=-1;MODE=MariaDB;DATABASE_TO_LOWER=TRUE",
              driver = "org.h2.Driver")
      DBMemberPortalConnection.db = db
      transaction(db) { SchemaUtils.create(KeyValueTable) }
    }
  }

  private val store = ExposedKeyValueStore()

  @Before
  fun setUp() {
    transaction(DBMemberPortalConnection.db) { KeyValueTable.deleteAll() }
  }

  @Test
  fun `put then get, and an unknown key is null`() = runBlocking<Unit> {
    assertNull(store.get("ns", "a"))

    store.put("ns", "a", "one")

    assertEquals("one", store.get("ns", "a"))
    assertTrue(store.contains("ns", "a"))
    assertFalse(store.contains("ns", "b"))
  }

  @Test
  fun `put replaces an existing value`() = runBlocking<Unit> {
    store.put("ns", "a", "one")
    store.put("ns", "a", "two")

    assertEquals("two", store.get("ns", "a"))
    assertEquals(setOf("a"), store.keys("ns"))
  }

  @Test
  fun `putIfAbsent only inserts the first time and keeps the original`() = runBlocking<Unit> {
    assertTrue(store.putIfAbsent("ns", "a", "first"))
    assertFalse(store.putIfAbsent("ns", "a", "second"))

    assertEquals("first", store.get("ns", "a"))
  }

  @Test
  fun `concurrent putIfAbsent has exactly one winner`() = runBlocking<Unit> {
    val results = (1..8).map { n -> async { store.putIfAbsent("ns", "race", "v$n") } }.awaitAll()

    assertEquals(1, results.count { it })
    assertTrue(store.get("ns", "race")!!.startsWith("v"))
  }

  @Test
  fun `putAllIfAbsent counts only the new keys and leaves existing values alone`() = runBlocking<Unit> {
    store.put("ns", "a", "old")

    val added = store.putAllIfAbsent("ns", mapOf("a" to "new", "b" to "b1", "c" to "c1"))

    assertEquals(2, added)
    assertEquals("old", store.get("ns", "a"))
    assertEquals(setOf("a", "b", "c"), store.keys("ns"))
    assertEquals(0, store.putAllIfAbsent("ns", mapOf("a" to "x", "b" to "x")))
    assertEquals(0, store.putAllIfAbsent("ns", emptyMap()))
  }

  @Test
  fun `delete reports whether something was removed`() = runBlocking<Unit> {
    store.put("ns", "a", "one")

    assertTrue(store.delete("ns", "a"))
    assertFalse(store.delete("ns", "a"))
    assertNull(store.get("ns", "a"))
  }

  @Test
  fun `namespaces are isolated`() = runBlocking<Unit> {
    store.put("one", "k", "from-one")
    store.put("two", "k", "from-two")

    assertEquals("from-one", store.get("one", "k"))
    assertEquals("from-two", store.get("two", "k"))
    store.delete("one", "k")
    assertEquals("from-two", store.get("two", "k"))
    assertEquals(emptySet(), store.keys("one"))
  }

  @Test
  fun `keys filters by a literal prefix, including wildcard characters`() = runBlocking<Unit> {
    store.putAllIfAbsent(
        "ns",
        mapOf("course-1" to "x", "course-2" to "x", "other" to "x", "50%off" to "x", "50xoff" to "x"))

    assertEquals(setOf("course-1", "course-2"), store.keys("ns", "course-"))
    assertEquals(setOf("50%off"), store.keys("ns", "50%"))
    assertEquals(5, store.keys("ns").size)
    assertEquals(emptySet(), store.keys("ns", "zzz"))
  }

  @Test
  fun `keys are case-sensitive`() = runBlocking<Unit> {
    store.put("ns", "Key", "upper")

    assertNull(store.get("ns", "key"))
    assertTrue(store.putIfAbsent("ns", "key", "lower"))
    assertEquals(setOf("Key", "key"), store.keys("ns"))
  }

  @Test
  fun `limits are enforced before touching the database`() = runBlocking<Unit> {
    val longKey = "k".repeat(KeyValueStore.MAX_KEY_LENGTH + 1)
    val longNamespace = "n".repeat(KeyValueStore.MAX_NAMESPACE_LENGTH + 1)
    val bigValue = "v".repeat(KeyValueStore.MAX_VALUE_BYTES + 1)

    assertFailsWith<IllegalArgumentException> { store.put("ns", longKey, "v") }
    assertFailsWith<IllegalArgumentException> { store.put(longNamespace, "k", "v") }
    assertFailsWith<IllegalArgumentException> { store.put("ns", "k", bigValue) }
    assertFailsWith<IllegalArgumentException> { store.put(" ", "k", "v") }
    assertFailsWith<IllegalArgumentException> { store.put("ns", "", "v") }
    assertFailsWith<IllegalArgumentException> { store.get(longNamespace, "k") }
    assertTrue(store.keys("ns").isEmpty())
    // Exactly at the limits is fine.
    store.put("n".repeat(KeyValueStore.MAX_NAMESPACE_LENGTH), "k".repeat(KeyValueStore.MAX_KEY_LENGTH), "v")
  }

  @Test
  fun `multi-byte values are measured in bytes`() = runBlocking<Unit> {
    val twoByteChars = "é".repeat(KeyValueStore.MAX_VALUE_BYTES / 2 + 1)

    assertFailsWith<IllegalArgumentException> { store.put("ns", "k", twoByteChars) }
  }

  @Serializable private data class Marker(val at: String, val count: Int = 0)

  @Test
  fun `a namespace handle fixes the namespace and round-trips JSON`() = runBlocking<Unit> {
    val courses = store.namespace("moodle-course-announced")

    courses.putJson("42", Marker("2026-10-18T12:00:00Z", 3))

    assertEquals(Marker("2026-10-18T12:00:00Z", 3), courses.getJson<Marker>("42"))
    assertNull(courses.getJson<Marker>("43"))
    assertEquals("moodle-course-announced", courses.name)
    assertEquals(setOf("42"), courses.keys())
    assertTrue(courses.contains("42"))
    assertTrue(courses.delete("42"))
  }
}
