package org.dallasmakerspace.activedirectory

import com.unboundid.ldap.sdk.Attribute
import com.unboundid.ldap.sdk.SearchResultEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class AdGroupWritesTest {
  private val main = "OU=Groups,DC=dms,DC=local"
  private val admin = "OU=Groups,OU=Admin,DC=dms,DC=local"

  private fun entry(cn: String, parent: String) =
      SearchResultEntry("CN=$cn,$parent", listOf(Attribute("cn", cn)))

  private fun spec(name: String = "Test Group", parent: String = main) =
      AdGroupSpec(name, parent, "A description", "CN=Owner,OU=Members,DC=dms,DC=local")

  @Test
  fun `delete picks the group in the main ou`() {
    val chosen = selectGroupToDelete("Shop", listOf(entry("Shop", main)))
    assertEquals("CN=Shop,$main", chosen?.dn)
  }

  @Test
  fun `delete accepts a sub ou of the main ou`() {
    val chosen = selectGroupToDelete("Shop", listOf(entry("Shop", "OU=Sub,$main")))
    assertEquals("CN=Shop,OU=Sub,$main", chosen?.dn)
  }

  @Test
  fun `delete of an absent group selects nothing`() {
    assertNull(selectGroupToDelete("Shop", emptyList()))
  }

  @Test
  fun `delete refuses a group in the admin ou`() {
    val e = assertFailsWith<GroupDeletionRefusedException> {
      selectGroupToDelete("Shop", listOf(entry("Shop", admin)))
    }
    assertEquals("Shop", e.groupName)
    assertEquals("CN=Shop,$admin", e.distinguishedName)
  }

  @Test
  fun `delete refuses groups outside the group ous`() {
    assertFailsWith<GroupDeletionRefusedException> {
      selectGroupToDelete("Shop", listOf(entry("Shop", "CN=Builtin,DC=dms,DC=local")))
    }
    assertFailsWith<GroupDeletionRefusedException> {
      selectGroupToDelete("Shop", listOf(entry("Shop", "OU=Elsewhere,DC=dms,DC=local")))
    }
  }

  @Test
  fun `delete refuses a crafted cn that only looks like the main ou`() {
    val crafted = SearchResultEntry("CN=Shop\\,OU=Groups,DC=dms,DC=local", listOf())
    assertFailsWith<GroupDeletionRefusedException> {
      selectGroupToDelete("Shop", listOf(crafted))
    }
  }

  @Test
  fun `delete prefers the main ou twin and ignores the admin ou twin`() {
    val chosen = selectGroupToDelete("Shop", listOf(entry("Shop", admin), entry("Shop", main)))
    assertEquals("CN=Shop,$main", chosen?.dn)
  }

  @Test
  fun `delete refuses when the only match is the admin ou twin among elsewhere matches`() {
    assertFailsWith<GroupDeletionRefusedException> {
      selectGroupToDelete(
          "Shop", listOf(entry("Shop", "OU=Elsewhere,DC=dms,DC=local"), entry("Shop", admin)))
    }
  }

  @Test
  fun `group dn is built with the name escaped`() {
    assertEquals("CN=Test Group,$main", buildGroupDn("Test Group", main))
    assertEquals("CN=Shop\\, Wood,$main", buildGroupDn("Shop, Wood", main))
  }

  @Test
  fun `create plans a new group when none exists`() {
    val plan = planGroupCreate(spec(), emptyList())
    assertEquals(GroupCreatePlan.Create("CN=Test Group,$main"), plan)
  }

  @Test
  fun `create is a no-op when the same dn exists, ignoring case and spacing`() {
    val existing = SearchResultEntry("cn=test group, ou=groups, dc=dms, dc=local", listOf())
    val plan = planGroupCreate(spec(), listOf(existing))
    assertIs<GroupCreatePlan.Exists>(plan)
    assertEquals(existing.dn, plan.entry.dn)
  }

  @Test
  fun `create fails when the name exists at a different dn`() {
    assertFailsWith<GroupCreateRefusedException> {
      planGroupCreate(spec(), listOf(entry("Test Group", admin)))
    }
    assertFailsWith<GroupCreateRefusedException> {
      planGroupCreate(spec(), listOf(entry("Test Group", "OU=Sub,$main")))
    }
  }

  @Test
  fun `create allows a sub ou of the main ou and refuses other parents`() {
    val sub = "OU=Sub,$main"
    assertEquals(
        GroupCreatePlan.Create("CN=Test Group,$sub"),
        planGroupCreate(spec(parent = sub), emptyList()))
    assertFailsWith<GroupCreateRefusedException> {
      planGroupCreate(spec(parent = admin), emptyList())
    }
    assertFailsWith<GroupCreateRefusedException> {
      planGroupCreate(spec(parent = "DC=dms,DC=local"), emptyList())
    }
  }

  @Test
  fun `create refuses a blank name`() {
    assertFailsWith<GroupCreateRefusedException> { planGroupCreate(spec(name = " "), emptyList()) }
  }

  @Test
  fun `service delegates deletes and creates to the client`() {
    val result = AdGroupCreateResult("CN=Test Group,$main", "guid", created = true)
    val client: IActiveDirectoryClient = mock {
      on { deleteGroup("Test Group") } doReturn true
      on { createGroup(any()) } doReturn result
    }
    val service = ActiveDirectoryService(client)

    assertEquals(true, service.deleteGroup("Test Group"))
    assertEquals(result, service.createGroup(spec()))
    verify(client).createGroup(spec())
  }

  @Test
  fun `mock service creates groups under a valid parent only`() {
    val mock = ActiveDirectoryServiceMock()
    val created = mock.createGroup(spec())
    assertEquals("CN=Test Group,$main", created.distinguishedName)
    assertEquals(true, created.created)
    assertFailsWith<GroupCreateRefusedException> { mock.createGroup(spec(parent = admin)) }
  }
}
