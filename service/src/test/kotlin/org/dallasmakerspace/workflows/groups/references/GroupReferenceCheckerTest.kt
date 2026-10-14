package org.dallasmakerspace.workflows.groups.references

import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test

private class FakeFinder(
    override val system: String,
    override val notes: List<String> = emptyList(),
    private val onFind: suspend (String) -> List<GroupReference>,
) : GroupReferenceFinder {
  val seen = mutableListOf<String>()

  override suspend fun find(groupName: String): List<GroupReference> {
    seen += groupName
    return onFind(groupName)
  }
}

private fun ref(system: String, kind: String = "KIND", count: Int = 1) =
    GroupReference(system, kind, count, "detail")

class GroupReferenceCheckerTest {

  private fun checker(vararg finders: GroupReferenceFinder) =
      GroupReferenceChecker(finders.toSet(), 5.seconds)

  @Test
  fun `no finders and no hits is not blocked and still carries the standing note`() = runBlocking {
    val result = checker(FakeFinder("A") { emptyList() }).check("Example Group")
    assertFalse(result.isBlocked)
    assertTrue(result.blocking.isEmpty())
    assertTrue(result.notes.any { it.contains("outside the portal") })
  }

  @Test
  fun `hits from every finder are merged and ordered by system`() = runBlocking {
    val result =
        checker(
                FakeFinder("Zeta") { listOf(ref("Zeta", "B"), ref("Zeta", "A")) },
                FakeFinder("Alpha") { listOf(ref("Alpha")) },
                FakeFinder("Mid") { emptyList() })
            .check("Example Group")
    assertTrue(result.isBlocked)
    assertEquals(
        listOf("Alpha" to "KIND", "Zeta" to "A", "Zeta" to "B"),
        result.blocking.map { it.system to it.kind })
  }

  @Test
  fun `the name is trimmed before it reaches the finders`() = runBlocking {
    val finder = FakeFinder("A") { emptyList() }
    checker(finder).check("  Example Group \t")
    assertEquals(listOf("Example Group"), finder.seen)
  }

  @Test
  fun `a throwing finder becomes a blocking UNKNOWN naming the system and error class`() =
      runBlocking {
        val result =
            checker(
                    FakeFinder("Calendar") { throw IOException("secret detail that must not leak") },
                    FakeFinder("Other") { emptyList() })
                .check("Example Group")
        assertTrue(result.isBlocked)
        val unknown = result.blocking.single()
        assertEquals("Calendar", unknown.system)
        assertEquals(GroupReferenceKinds.UNKNOWN, unknown.kind)
        assertEquals("could not check Calendar: IOException", unknown.detail)
        assertFalse(unknown.detail.contains("secret"))
      }

  @Test
  fun `one failing finder does not hide hits from the others`() = runBlocking {
    val result =
        checker(
                FakeFinder("A") { error("boom") },
                FakeFinder("B") { listOf(ref("B", GroupReferenceKinds.COURSE)) })
            .check("Example Group")
    assertEquals(
        listOf(GroupReferenceKinds.UNKNOWN, GroupReferenceKinds.COURSE),
        result.blocking.map { it.kind })
  }

  @Test
  fun `a finder that takes too long becomes a blocking UNKNOWN`() = runBlocking {
    val slow = FakeFinder("Slow") {
      delay(10_000)
      emptyList()
    }
    val result = GroupReferenceChecker(setOf(slow), 50.milliseconds).check("Example Group")
    val unknown = result.blocking.single()
    assertEquals(GroupReferenceKinds.UNKNOWN, unknown.kind)
    assertEquals("could not check Slow: TimeoutException", unknown.detail)
  }

  @Test
  fun `notes from finders are added once`() = runBlocking {
    val result =
        checker(
                FakeFinder("A", notes = listOf("note one", "shared")) { emptyList() },
                FakeFinder("B", notes = listOf("shared")) { emptyList() })
            .check("Example Group")
    assertEquals(1, result.notes.count { it == "shared" })
    assertTrue("note one" in result.notes)
  }

  @Test
  fun `a blank name is rejected`() {
    assertFailsWith<IllegalArgumentException> {
      runBlocking { checker(FakeFinder("A") { emptyList() }).check("   ") }
    }
  }
}
