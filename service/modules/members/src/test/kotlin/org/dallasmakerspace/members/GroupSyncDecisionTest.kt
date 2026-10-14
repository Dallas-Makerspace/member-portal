package org.dallasmakerspace.members

import kotlin.test.Test
import kotlin.test.assertEquals

class GroupSyncDecisionTest {
  private val guid = "11111111-1111-1111-1111-111111111111"
  private val otherGuid = "22222222-2222-2222-2222-222222222222"

  private fun row(
      id: Int,
      name: String,
      dn: String = "CN=$name,OU=Test,DC=example,DC=test",
      g: String? = null,
  ) = GroupRowRef(id, name, dn, g)

  private fun archived(id: Int, name: String, g: String? = null) =
      GroupRowRef(id, name, "CN=$name,OU=Test,DC=example,DC=test", g, "ARCHIVED")

  @Test
  fun `an archived row found by name is ignored and a fresh row is created`() {
    val dn = "CN=Team A,OU=Test,DC=example,DC=test"
    val d = decideGroupSync("Team A", dn, guid, null, archived(1, "Team A", otherGuid), null)
    assertEquals(GroupSyncDecision.Create(dn), d)
  }

  @Test
  fun `an archived row found by dn does not force the placeholder dn`() {
    val dn = "CN=Team A,OU=Test,DC=example,DC=test"
    val d = decideGroupSync("Team A", dn, guid, null, null, archived(1, "Team A"))
    assertEquals(GroupSyncDecision.Create(dn), d)
  }

  @Test
  fun `an archived name holder does not block healing another row`() {
    val d =
        decideGroupSync("New", null, guid, row(1, "Old", g = guid), archived(2, "New"), null)
    assertEquals(GroupSyncDecision.Heal(1, "New", null), d)
  }

  @Test
  fun `a guid match on an archived row is a conflict, never a heal`() {
    val d = decideGroupSync("New", null, guid, archived(1, "Old", guid), null, null)
    assertEquals(GroupSyncDecision.Conflict(1, "row is archived"), d)
  }

  @Test
  fun `no row and no guid creates with placeholder dn`() {
    val d = decideGroupSync("Team A", null, null, null, null, null)
    assertEquals(GroupSyncDecision.Create(placeholderGroupDn("Team A")), d)
  }

  @Test
  fun `no row creates with the given dn`() {
    val dn = "CN=Team A,OU=Test,DC=example,DC=test"
    val d = decideGroupSync("Team A", dn, guid, null, null, null)
    assertEquals(GroupSyncDecision.Create(dn), d)
  }

  @Test
  fun `create falls back to placeholder dn when the dn is held by another row`() {
    val d = decideGroupSync("Team A", "CN=X", guid, null, null, row(9, "Other", "CN=X"))
    assertEquals(GroupSyncDecision.Create(placeholderGroupDn("Team A")), d)
  }

  @Test
  fun `name match without guid fills the guid`() {
    val d = decideGroupSync("Team A", null, guid, null, row(1, "team a"), null)
    assertEquals(GroupSyncDecision.FillGuid(1, guid), d)
  }

  @Test
  fun `name match with no resolved guid just uses the row`() {
    val d = decideGroupSync("Team A", null, null, null, row(1, "Team A", g = guid), null)
    assertEquals(GroupSyncDecision.Use(1), d)
  }

  @Test
  fun `name match with a different stored guid is a conflict`() {
    val d = decideGroupSync("Team A", null, guid, null, row(1, "Team A", g = otherGuid), null)
    assertEquals(GroupSyncDecision.Conflict(1, "row has a different GUID"), d)
  }

  @Test
  fun `guid match with same name and dn is used as is`() {
    val r = row(1, "Team A", g = guid)
    val d = decideGroupSync("TEAM A", r.dn.uppercase(), guid, r, r, r)
    assertEquals(GroupSyncDecision.Use(1), d)
  }

  @Test
  fun `guid match with a new name heals name and dn`() {
    val newDn = "CN=New Name,OU=Test,DC=example,DC=test"
    val d = decideGroupSync("New Name", newDn, guid, row(1, "Old Name", g = guid), null, null)
    assertEquals(GroupSyncDecision.Heal(1, "New Name", newDn), d)
  }

  @Test
  fun `heal keeps the stored dn when none is given`() {
    val d = decideGroupSync("New Name", null, guid, row(1, "Old Name", g = guid), null, null)
    assertEquals(GroupSyncDecision.Heal(1, "New Name", null), d)
  }

  @Test
  fun `heal is blocked when another row already has the new name`() {
    val byGuid = row(1, "Old Name", g = guid)
    val d = decideGroupSync("New Name", null, guid, byGuid, row(2, "New Name"), null)
    assertEquals(GroupSyncDecision.Conflict(1, "name is held by another row"), d)
  }

  @Test
  fun `heal is blocked when another row already has the dn`() {
    val byGuid = row(1, "Old Name", "CN=Old", guid)
    val d = decideGroupSync("Old Name", "CN=Taken", guid, byGuid, byGuid, row(2, "Z", "CN=Taken"))
    assertEquals(GroupSyncDecision.Conflict(1, "dn is held by another row"), d)
  }
}
