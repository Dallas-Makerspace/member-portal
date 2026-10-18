package org.dallasmakerspace.webhook

import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.webhook.handlers.CommitteeFollowerFilter
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CommitteeFollowerFilterTest {

  private val ad = mock<IActiveDirectoryService>()
  private val filter = CommitteeFollowerFilter(ad)

  private fun user(name: String, vararg groups: String) =
      ADUser(
          sAMAccountName = name,
          mail = "$name@example.com",
          givenName = null,
          sn = null,
          telephoneNumber = null,
          employeeID = null,
          displayName = name,
          objectGuid = "guid-$name",
          whenCreated = null,
          enabled = true,
          groups =
              groups.map {
                ADGroup(
                    cn = it,
                    description = null,
                    distinguishedName = "CN=$it,OU=Groups,DC=example,DC=internal",
                    objectGuid = null,
                    members = emptyList(),
                    membersListIncomplete = false)
              })

  @Test
  fun `drops members already in the group, ignoring case`() = runBlocking<Unit> {
    whenever(ad.getMembersByUsernameList(listOf("alice", "bob", "carol")))
        .thenReturn(
            mapOf(
                "alice" to user("alice", "LASER BASICS"),
                "bob" to user("bob", "Other"),
                "carol" to user("carol")))

    assertEquals(listOf("bob", "carol"), filter.withoutGroup(listOf("alice", "bob", "carol"), "Laser Basics"))
  }

  @Test
  fun `keeps members AD doesn't know`() = runBlocking<Unit> {
    whenever(ad.getMembersByUsernameList(listOf("alice", "ghost")))
        .thenReturn(mapOf("alice" to user("alice"), "ghost" to null))

    assertEquals(listOf("alice", "ghost"), filter.withoutGroup(listOf("alice", "ghost"), "Laser Basics"))
  }

  @Test
  fun `keeps a whole batch when AD fails`() = runBlocking<Unit> {
    whenever(ad.getMembersByUsernameList(any())).thenThrow(RuntimeException("ldap down"))

    assertEquals(listOf("alice", "bob"), filter.withoutGroup(listOf("alice", "bob"), "Laser Basics"))
  }

  @Test
  fun `looks members up 100 at a time`() = runBlocking<Unit> {
    val names = (1..250).map { "member$it" }
    whenever(ad.getMembersByUsernameList(any())).thenAnswer { invocation ->
      @Suppress("UNCHECKED_CAST")
      (invocation.arguments[0] as List<String>).associateWith { user(it) }
    }

    assertEquals(names, filter.withoutGroup(names, "Laser Basics"))
    verify(ad, times(3)).getMembersByUsernameList(any())
  }

  @Test
  fun `an empty list never calls AD`() = runBlocking<Unit> {
    assertEquals(emptyList(), filter.withoutGroup(emptyList(), "Laser Basics"))
    verify(ad, times(0)).getMembersByUsernameList(any())
  }
}
