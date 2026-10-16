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

  private val guid = "11111111-2222-3333-4444-555555555555"

  private fun entryWithGuid(cn: String, parent: String, guid: String = this.guid) =
      SearchResultEntry(
          "CN=$cn,$parent",
          listOf(Attribute("cn", cn), Attribute("objectGUID", AdGuid.toBytes(guid))))

  private fun delete(
      name: String,
      byGuid: List<SearchResultEntry>,
      byName: List<SearchResultEntry> = byGuid,
      expected: String = guid,
  ) = selectGroupToDelete(name, expected, byGuid, byName)

  @Test
  fun `delete picks the group with the expected GUID in the main ou`() {
    val chosen = delete("Shop", listOf(entryWithGuid("Shop", main)))
    assertEquals("CN=Shop,$main", chosen.dn)
  }

  @Test
  fun `delete accepts a sub ou of the main ou`() {
    val chosen = delete("Shop", listOf(entryWithGuid("Shop", "OU=Sub,$main")))
    assertEquals("CN=Shop,OU=Sub,$main", chosen.dn)
  }

  @Test
  fun `delete never falls back to the name when no group has the GUID`() {
    assertFailsWith<GroupGuidNotFoundException> {
      delete("Shop", emptyList(), listOf(entry("Shop", main)))
    }
    assertFailsWith<GroupGuidNotFoundException> {
      delete("Shop", listOf(entryWithGuid("Shop", main), entryWithGuid("Shop", "OU=Sub,$main")))
    }
  }

  @Test
  fun `delete refuses an entry whose GUID is not the expected one`() {
    assertFailsWith<GroupGuidNotFoundException> {
      delete("Shop", listOf(entryWithGuid("Shop", main, "99999999-2222-3333-4444-555555555555")))
    }
  }

  @Test
  fun `delete refuses when the entry carries another name`() {
    val e =
        assertFailsWith<GroupDeletionRefusedException> {
          delete("Shop", listOf(entryWithGuid("Other", main)))
        }
    assertEquals("CN=Other,$main", e.distinguishedName)
  }

  @Test
  fun `delete refuses a group in the admin ou`() {
    val e =
        assertFailsWith<GroupDeletionRefusedException> {
          delete("Shop", listOf(entryWithGuid("Shop", admin)))
        }
    assertEquals("Shop", e.groupName)
    assertEquals("CN=Shop,$admin", e.distinguishedName)
  }

  @Test
  fun `delete refuses groups outside the group ous`() {
    assertFailsWith<GroupDeletionRefusedException> {
      delete("Shop", listOf(entryWithGuid("Shop", "CN=Builtin,DC=dms,DC=local")))
    }
    assertFailsWith<GroupDeletionRefusedException> {
      delete("Shop", listOf(entryWithGuid("Shop", "OU=Elsewhere,DC=dms,DC=local")))
    }
  }

  @Test
  fun `delete refuses a crafted cn that only looks like the main ou`() {
    val crafted =
        SearchResultEntry(
            "CN=Shop\\,OU=Groups,DC=dms,DC=local",
            listOf(Attribute("objectGUID", AdGuid.toBytes(guid))))
    assertFailsWith<GroupDeletionRefusedException> { delete("Shop", listOf(crafted)) }
  }

  @Test
  fun `delete refuses a name duplicated in the admin ou or a sub ou`() {
    val target = entryWithGuid("Shop", main)

    val inAdmin =
        assertFailsWith<AmbiguousGroupException> {
          delete("Shop", listOf(target), listOf(entry("Shop", admin), target))
        }
    assertFailsWith<AmbiguousGroupException> {
      delete("Shop", listOf(target), listOf(target, entry("Shop", "OU=Sub,$main")))
    }

    assertEquals(2, inAdmin.count)
  }

  @Test
  fun `a same-named group outside the group ous does not make the name ambiguous`() {
    val target = entryWithGuid("Shop", main)

    val chosen =
        delete("Shop", listOf(target), listOf(target, entry("Shop", "CN=Builtin,DC=dms,DC=local")))

    assertEquals(target.dn, chosen.dn)
  }

  @Test
  fun `delete refuses protected group names whatever their location`() {
    assertFailsWith<GroupDeletionRefusedException> {
      delete("Infrastructure", listOf(entryWithGuid("Infrastructure", main)))
    }
    assertFailsWith<GroupDeletionRefusedException> {
      delete("board", listOf(entryWithGuid("Board", main)))
    }
  }

  @Test
  fun `strict lookup refuses a duplicate across a sub ou and the main ou`() {
    assertEquals("CN=Shop,$main", selectUniqueGroup("Shop", listOf(entry("Shop", main)))?.dn)
    assertNull(selectUniqueGroup("Shop", emptyList()))
    assertFailsWith<AmbiguousGroupException> {
      selectUniqueGroup("Shop", listOf(entry("Shop", "OU=Sub,$main"), entry("Shop", main)))
    }
    assertFailsWith<AmbiguousGroupException> {
      selectUniqueGroup("Shop", listOf(entry("Shop", admin), entry("Shop", main)))
    }
  }

  @Test
  fun `strict lookup returns a lone group outside the group ous so callers can refuse it`() {
    val elsewhere = entry("Shop", "CN=Builtin,DC=dms,DC=local")
    assertEquals(elsewhere.dn, selectUniqueGroup("Shop", listOf(elsewhere))?.dn)
    assertEquals(
        "CN=Shop,$main", selectUniqueGroup("Shop", listOf(elsewhere, entry("Shop", main)))?.dn)
  }

  @Test
  fun `parent membership is only allowed inside the main ou`() {
    requireMainOuMembership("Shop", "CN=Shop,$main", "CN=Parent,OU=Sub,$main")
    assertFailsWith<GroupMembershipRefusedException> {
      requireMainOuMembership("Shop", "CN=Shop,$main", "CN=Parent,$admin")
    }
    assertFailsWith<GroupMembershipRefusedException> {
      requireMainOuMembership("Shop", "CN=Shop,$admin", "CN=Parent,$main")
    }
    assertFailsWith<GroupMembershipRefusedException> {
      requireMainOuMembership("Shop", "CN=Shop,$main", "CN=Parent,CN=Builtin,DC=dms,DC=local")
    }
  }

  @Test
  fun `managed objects are counted by kind and a computer is not also a user`() {
    fun e(vararg classes: String) =
        SearchResultEntry("CN=x,DC=dms,DC=local", listOf(Attribute("objectClass", *classes)))
    val counts =
        classifyManagedObjects(
            listOf(
                e("top", "group"),
                e("top", "person", "organizationalPerson", "user"),
                e("top", "person", "organizationalPerson", "user", "computer"),
                e("top", "contact"),
                e("top", "group"),
            ))

    assertEquals(ManagedObjectCounts(groups = 2, users = 1, computers = 1, others = 1), counts)
    assertEquals(5, counts.total)
    assertEquals(0, classifyManagedObjects(emptyList()).total)
  }

  @Test
  fun `a restored group keeps its sAMAccountName and type when usable`() {
    val base = spec()
    assertEquals("Test Group", samAccountNameFor(base))
    assertEquals("shop-saws", samAccountNameFor(base.copy(samAccountName = " shop-saws ")))
    listOf("", "  ", "a/b", "a\\b", "a,b", "x".repeat(257), "a\u0001b").forEach {
      assertEquals("Test Group", samAccountNameFor(base.copy(samAccountName = it)), it)
    }
    assertEquals(GLOBAL_SECURITY_GROUP_TYPE, groupTypeFor(base))
    assertEquals(-2147483640, groupTypeFor(base.copy(groupType = -2147483640)))
    assertEquals(GLOBAL_SECURITY_GROUP_TYPE, groupTypeFor(base.copy(groupType = 2)))
  }

  @Test
  fun `guid text converts to the directory byte layout and back`() {
    val bytes = AdGuid.toBytes(guid)
    assertEquals(guid, AdGuid.fromBytes(bytes))
    assertNull(AdGuid.toBytes("nope"))
    assertNull(AdGuid.toBytes(null))
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
  fun `create treats an existing group as already there only when it is the only one of that name`() {
    val exact = entry("Test Group", main)

    assertIs<GroupCreatePlan.Exists>(planGroupCreate(spec(), listOf(exact)))
    assertFailsWith<GroupCreateRefusedException> {
      planGroupCreate(spec(), listOf(exact, entry("Test Group", "OU=Sub,$main")))
    }
    assertFailsWith<GroupCreateRefusedException> {
      planGroupCreate(spec(), listOf(exact, entry("Test Group", admin)))
    }
    // a same-named group outside the group OUs does not count as a twin
    assertIs<GroupCreatePlan.Exists>(
        planGroupCreate(spec(), listOf(exact, entry("Test Group", "CN=Builtin,DC=dms,DC=local"))))
  }

  @Test
  fun `both LDAP duplicate-member codes mean the member is already there`() {
    assertEquals(true, isAlreadyMember(com.unboundid.ldap.sdk.ResultCode.ATTRIBUTE_OR_VALUE_EXISTS))
    // what Active Directory answers (ERROR_MEMBER_IN_GROUP surfaces as entryAlreadyExists, 68)
    assertEquals(true, isAlreadyMember(com.unboundid.ldap.sdk.ResultCode.ENTRY_ALREADY_EXISTS))
    assertEquals(false, isAlreadyMember(com.unboundid.ldap.sdk.ResultCode.NO_SUCH_OBJECT))
    assertEquals(false, isAlreadyMember(com.unboundid.ldap.sdk.ResultCode.INSUFFICIENT_ACCESS_RIGHTS))
    assertEquals(68, com.unboundid.ldap.sdk.ResultCode.ENTRY_ALREADY_EXISTS.intValue())
  }

  @Test
  fun `log text loses control characters and is bounded`() {
    assertEquals("Shop", logSafe("Shop"))
    assertEquals("a_b_c_d_e", logSafe("a\nb\rc\u0000d\u001be"))
    assertEquals("", logSafe(null))
    val long = logSafe("x".repeat(500))
    assertEquals(203, long.length)
    assertEquals(true, long.endsWith("..."))
  }

  @Test
  fun `service delegates deletes and creates to the client`() {
    val result = AdGroupCreateResult("CN=Test Group,$main", "guid", created = true)
    val client: IActiveDirectoryClient = mock {
      on { deleteGroup("Test Group", guid) } doReturn true
      on { createGroup(any()) } doReturn result
    }
    val service = ActiveDirectoryService(client)

    assertEquals(true, service.deleteGroup("Test Group", guid))
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
