package org.dallasmakerspace.members

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupDnClassifierTest {
  private fun guid(n: Int) = "00000000-0000-0000-0000-%012d".format(n)

  private fun ad(name: String, n: Int, ou: String = "Test") =
      AdGroupRef(guid(n), "CN=$name,OU=$ou,DC=example,DC=test", name)

  private fun row(id: Int, name: String, dn: String, guid: String? = null) =
      GroupRowRef(id, name, dn, guid)

  private fun classify(
      rows: List<GroupRowRef>,
      ads: List<AdGroupRef>,
      assigned: Map<Int, String> = emptyMap(),
  ) = classifyGroupDns(rows, ads, assigned)

  @Test
  fun `differing dn of a row with a stored guid is updated`() {
    val rows = listOf(row(1, "Team A", "CN=Team A,OU=Groups,DC=example,DC=test", guid(1)))
    val r = classify(rows, listOf(ad("Team A", 1)))
    assertEquals(mapOf(1 to DnUpdate(guid(1), "CN=Team A,OU=Test,DC=example,DC=test")), r.updates)
  }

  @Test
  fun `a guid assigned in this run counts`() {
    val rows = listOf(row(1, "Team A", "CN=Team A,OU=Groups,DC=example,DC=test"))
    val r = classify(rows, listOf(ad("Team A", 1)), mapOf(1 to guid(1)))
    assertEquals(setOf(1), r.updates.keys)
  }

  @Test
  fun `rows without a guid are ignored`() {
    val r = classify(listOf(row(1, "Team A", "CN=other")), listOf(ad("Team A", 1)))
    assertTrue(r.updates.isEmpty() && r.unchanged.isEmpty() && r.conflicts.isEmpty())
  }

  @Test
  fun `rows whose guid is not in ad are ignored`() {
    val r = classify(listOf(row(1, "Gone", "CN=Gone", guid(9))), listOf(ad("Team A", 1)))
    assertTrue(r.updates.isEmpty() && r.unchanged.isEmpty() && r.conflicts.isEmpty())
  }

  @Test
  fun `case and comma spacing differences are unchanged`() {
    val rows = listOf(row(1, "Team A", "cn=team a, ou=test, dc=example, dc=test", guid(1)))
    val r = classify(rows, listOf(ad("Team A", 1)))
    assertEquals(listOf(1), r.unchanged)
    assertTrue(r.updates.isEmpty())
  }

  @Test
  fun `a target dn held by another row is a conflict`() {
    val rows =
        listOf(
            row(1, "Team A", "CN=old", guid(1)),
            row(2, "Other", "CN=Team A,OU=Test,DC=example,DC=test", guid(2)))
    val r = classify(rows, listOf(ad("Team A", 1), ad("Other", 2, "Elsewhere")))
    assertEquals(listOf(1), r.conflicts)
    assertEquals(setOf(2), r.updates.keys)
  }

  @Test
  fun `two rows wanting the same dn are both conflicts`() {
    val rows = listOf(row(1, "A", "CN=x", guid(1)), row(2, "B", "CN=y", guid(2)))
    val dup = AdGroupRef(guid(2), "CN=A,OU=Test,DC=example,DC=test", "B")
    val r = classify(rows, listOf(ad("A", 1), dup))
    assertEquals(listOf(1, 2), r.conflicts)
    assertTrue(r.updates.isEmpty())
  }

  @Test
  fun `unusable ad guids are ignored`() {
    val rows = listOf(row(1, "A", "CN=x", guid(7)), row(2, "B", "CN=y", guid(7)))
    val ads = listOf(ad("A", 7), ad("B", 7))
    assertTrue(classify(rows, ads).updates.isEmpty())
  }
}
