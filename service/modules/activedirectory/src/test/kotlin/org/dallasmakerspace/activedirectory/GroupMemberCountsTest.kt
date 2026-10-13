package org.dallasmakerspace.activedirectory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class GroupMemberCountsTest {
  private val userDn = { n: String -> "CN=$n,OU=Users,DC=example,DC=test" }
  private val nestedDn = "CN=Nested,OU=Groups,DC=example,DC=test"

  private fun userMap(name: String) =
      mapOf<String, Any?>(
          "sAMAccountName" to name,
          "userAccountControl" to "512",
          "objectGUID" to "guid-$name",
      )

  private fun service(members: List<String>, resolvable: Set<String>): ActiveDirectoryService {
    val client: IActiveDirectoryClient = mock {
      on { getGroup(any()) } doReturn
          mapOf<String, Any?>(
              "cn" to "Test Group",
              "distinguishedName" to "CN=Test Group,OU=Groups,DC=example,DC=test",
              "member" to members.toTypedArray(),
          )
      on { getUsersByDnList(any()) } doAnswer { call ->
        call.getArgument<List<String>>(0).filter { it in resolvable }.associateWith {
          userMap(it.substringAfter("CN=").substringBefore(','))
        }
      }
    }
    return ActiveDirectoryService(client)
  }

  @Test
  fun `counts the raw members, nested groups and resolved users`() {
    val members = listOf(userDn("asmith"), userDn("bsmith"), nestedDn)

    val group = service(members, members.toSet()).getGroup("Test Group")

    assertEquals(3, group.sourceMemberCount)
    assertEquals(0, group.unresolvedMemberCount)
    assertEquals(listOf(nestedDn), group.nestedGroups)
    assertEquals(setOf("asmith", "bsmith"), group.members.map { it.sAMAccountName }.toSet())
  }

  @Test
  fun `counts user looking DNs that did not resolve to a user`() {
    val members = listOf(userDn("asmith"), userDn("ghost"), userDn("gone"))

    val group = service(members, setOf(userDn("asmith"))).getGroup("Test Group")

    assertEquals(3, group.sourceMemberCount)
    assertEquals(2, group.unresolvedMemberCount)
    assertEquals(1, group.members.size)
  }

  @Test
  fun `a missing group lookup throws the not found subclass of ADException`() {
    val client: IActiveDirectoryClient = mock {
      on { getGroup(any()) } doAnswer { throw GroupNotFoundException("Nope") }
    }

    val thrown = assertFailsWith<ADException> { ActiveDirectoryService(client).getGroup("Nope") }

    assertTrue(thrown is GroupNotFoundException)
    assertEquals("Group Nope not found in AD", thrown.message)
  }
}
