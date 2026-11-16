package org.dallasmakerspace.dashboards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.members.ProfilePhotoService
import org.dallasmakerspace.models.Committee
import org.dallasmakerspace.models.DMSMember
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class DashboardMembersTest {
  private val committee =
      Committee(id = 99, name = "Test Shop", chairGroupName = "Test Chair", groupPrefixes = listOf("TS"))
  private val directory: IActiveDirectoryService = mock()
  private val memberService: MemberService = mock()
  private val photos: ProfilePhotoService = mock()
  private val members =
      DashboardMembers(directory, memberService, photos, LoggerFactory())

  private fun user(name: String, enabled: Boolean = true) =
      ADUser(
          sAMAccountName = name,
          mail = "$name@example.test",
          givenName = null,
          sn = null,
          telephoneNumber = null,
          employeeID = null,
          displayName = name,
          objectGuid = "guid-$name",
          whenCreated = null,
          enabled = enabled,
          groups = emptyList())

  private fun group(vararg users: ADUser) =
      ADGroup(
          cn = "TS Leads",
          description = null,
          distinguishedName = "CN=TS Leads",
          objectGuid = "g",
          members = users.toList(),
          membersListIncomplete = false)

  @Test
  fun `a small group adds its enabled members`() = runBlocking {
    whenever(directory.getGroup("TS Leads"))
        .thenReturn(group(user("jdoe"), user("asmith"), user("gone", enabled = false)))
    assertEquals(listOf("jdoe", "asmith"), members.groupMembers(committee, "TS Leads"))
  }

  @Test
  fun `a group over the limit is refused`() = runBlocking {
    val big = (1..MAX_GROUP_MEMBERS_TO_ADD + 1).map { user("user$it") }.toTypedArray()
    whenever(directory.getGroup("TS Leads")).thenReturn(group(*big))
    val error = assertFailsWith<DashboardException> { members.groupMembers(committee, "TS Leads") }
    assertTrue(error.message!!.contains("too many members"))
  }

  @Test
  fun `a group of exactly the limit is accepted`() = runBlocking {
    val ten = (1..MAX_GROUP_MEMBERS_TO_ADD).map { user("user$it") }.toTypedArray()
    whenever(directory.getGroup("TS Leads")).thenReturn(group(*ten))
    assertEquals(MAX_GROUP_MEMBERS_TO_ADD, members.groupMembers(committee, "TS Leads").size)
  }

  @Test
  fun `another committee's group is refused`() = runBlocking {
    assertFailsWith<DashboardException> { members.groupMembers(committee, "Other Group") }
    Unit
  }

  @Test
  fun `an empty group is refused`() = runBlocking {
    whenever(directory.getGroup("TS Leads")).thenReturn(group())
    assertFailsWith<DashboardException> { members.groupMembers(committee, "TS Leads") }
    Unit
  }

  @Test
  fun `rows keep the order and drop disabled or unknown people`() = runBlocking {
    val found =
        mapOf(
            "asmith" to DMSMember(id = 2, username = "asmith", displayName = "Alex Smith", enabled = true),
            "jdoe" to
                DMSMember(
                    id = 1, username = "jdoe", displayName = "", enabled = true, profilePhotoUrl = "/avatar/@jdoe?v=1"),
            "off" to DMSMember(id = 3, username = "off", enabled = false))
    whenever(memberService.getMembersByUsernameList(any())).thenReturn(found)
    val rows = members.rows(listOf("jdoe", "off", "nobody", "asmith"))
    assertEquals(listOf("jdoe", "asmith"), rows.map { it.username })
    assertEquals("jdoe", rows[0].displayName)
    assertTrue(rows[0].hasPhoto)
    assertEquals("Alex Smith", rows[1].displayName)
  }

  @Test
  fun `a photo is only served for someone on the list`() = runBlocking {
    whenever(photos.get("jdoe")).thenReturn(byteArrayOf(1) to "image/jpeg")
    assertEquals("image/jpeg", members.photo(setOf("jdoe"), "jdoe")?.second)
    assertEquals(null, members.photo(setOf("asmith"), "jdoe"))
  }
}
