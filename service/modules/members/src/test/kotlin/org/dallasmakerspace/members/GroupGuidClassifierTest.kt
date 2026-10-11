package org.dallasmakerspace.members

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupGuidClassifierTest {
  private fun guid(n: Int) = "00000000-0000-0000-0000-%012d".format(n)

  private fun dnOf(name: String) = "CN=$name,OU=Test,DC=example,DC=test"

  private fun ad(name: String, n: Int?, dn: String = dnOf(name)) =
      AdGroupRef(n?.let { guid(it) }, dn, name)

  private fun row(id: Int, name: String, dn: String = dnOf(name), guid: String? = null) =
      GroupRowRef(id, name, dn, guid)

  @Test
  fun `matches by dn`() {
    val r = classifyGroupGuids(listOf(row(1, "Old Label", dnOf("Team A"))), listOf(ad("Team A", 1)))
    assertEquals(mapOf(1 to guid(1)), r.matched)
  }

  @Test
  fun `dn match wins over a name match on a different group`() {
    val rows = listOf(row(1, "Team B", dnOf("Team A")))
    val r = classifyGroupGuids(rows, listOf(ad("Team A", 1), ad("Team B", 2)))
    assertEquals(mapOf(1 to guid(1)), r.matched)
    assertEquals(1, r.adGroupsWithoutRow)
  }

  @Test
  fun `falls back to case-insensitive name when the dn is a placeholder`() {
    val rows = listOf(row(1, "team a", placeholderGroupDn("team a")))
    val r = classifyGroupGuids(rows, listOf(ad("Team A", 1)))
    assertEquals(mapOf(1 to guid(1)), r.matched)
  }

  @Test
  fun `dn comparison ignores case and spacing after commas`() {
    val rows = listOf(row(1, "x", "cn=team a, ou=test, dc=example, dc=test"))
    val r = classifyGroupGuids(rows, listOf(ad("Team A", 1)))
    assertEquals(mapOf(1 to guid(1)), r.matched)
  }

  @Test
  fun `already set rows are confirmed by guid even when the name changed`() {
    val rows = listOf(row(1, "Old Name", guid = guid(1)))
    val r = classifyGroupGuids(rows, listOf(ad("New Name", 1)))
    assertEquals(listOf(1), r.alreadySet)
    assertTrue(r.matched.isEmpty())
    assertEquals(0, r.adGroupsWithoutRow)
  }

  @Test
  fun `row without an ad group is left alone`() {
    val r = classifyGroupGuids(listOf(row(1, "Gone")), listOf(ad("Team A", 1)))
    assertEquals(listOf(1), r.rowsWithoutAdGroup)
    assertEquals(1, r.adGroupsWithoutRow)
  }

  @Test
  fun `stored guid that no ad group has is a row without ad group`() {
    val r = classifyGroupGuids(listOf(row(1, "Gone", guid = guid(9))), listOf(ad("Team A", 1)))
    assertEquals(listOf(1), r.rowsWithoutAdGroup)
  }

  @Test
  fun `ad group without a row is only counted`() {
    val r = classifyGroupGuids(emptyList(), listOf(ad("Team A", 1), ad("Team B", 2)))
    assertEquals(2, r.adGroupsWithoutRow)
    assertTrue(r.matched.isEmpty())
  }

  @Test
  fun `two rows matching one ad group are both ambiguous`() {
    val rows = listOf(row(1, "Team A"), row(2, "Other", dnOf("Team A")))
    val r = classifyGroupGuids(rows, listOf(ad("Team A", 1)))
    assertEquals(
        mapOf(1 to AmbiguityReason.MULTIPLE_ROWS, 2 to AmbiguityReason.MULTIPLE_ROWS), r.ambiguous)
    assertTrue(r.matched.isEmpty())
    assertEquals(0, r.adGroupsWithoutRow)
  }

  @Test
  fun `a row matching two ad groups by name is ambiguous`() {
    val ads = listOf(ad("Team A", 1, "CN=Team A,OU=One,DC=example,DC=test"),
        ad("Team A", 2, "CN=Team A,OU=Two,DC=example,DC=test"))
    val r = classifyGroupGuids(listOf(row(1, "team a", placeholderGroupDn("team a"))), ads)
    assertEquals(mapOf(1 to AmbiguityReason.MULTIPLE_AD_GROUPS), r.ambiguous)
    assertEquals(0, r.adGroupsWithoutRow)
  }

  @Test
  fun `a guid already stored on another row makes the candidate ambiguous`() {
    val rows = listOf(row(1, "Old Name", guid = guid(1)), row(2, "New Name"))
    val r = classifyGroupGuids(rows, listOf(ad("New Name", 1)))
    assertEquals(mapOf(2 to AmbiguityReason.GUID_IN_USE), r.ambiguous)
    assertEquals(listOf(1), r.alreadySet)
    assertTrue(r.matched.isEmpty())
  }

  @Test
  fun `ad groups with a missing, malformed or duplicated guid are unusable`() {
    val ads = listOf(
        ad("No Guid", null), AdGroupRef("mock-guid", dnOf("Bad Guid"), "Bad Guid"),
        ad("Dup A", 7), ad("Dup B", 7))
    val rows = listOf(row(1, "No Guid"), row(2, "Bad Guid"), row(3, "Dup A"), row(4, "Dup B"))
    val r = classifyGroupGuids(rows, ads)
    assertEquals(listOf(1, 2, 3, 4), r.unusableGuid)
    assertTrue(r.matched.isEmpty())
  }

  @Test
  fun `counts add up on a mixed set`() {
    val rows =
        listOf(
            row(1, "Team A"), row(2, "Team B", guid = guid(2)), row(3, "Team C"),
            row(4, "Team D"), row(5, "Team D2", dnOf("Team D")))
    val ads = listOf(ad("Team A", 1), ad("Team B", 2), ad("Team D", 4), ad("Team E", 5))
    val r = classifyGroupGuids(rows, ads)
    assertEquals(mapOf(1 to guid(1)), r.matched)
    assertEquals(listOf(2), r.alreadySet)
    assertEquals(listOf(3), r.rowsWithoutAdGroup)
    assertEquals(setOf(4, 5), r.ambiguous.keys)
    assertEquals(1, r.adGroupsWithoutRow)
  }
}
