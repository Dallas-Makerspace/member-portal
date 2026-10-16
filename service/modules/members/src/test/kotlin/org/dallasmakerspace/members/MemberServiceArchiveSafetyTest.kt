package org.dallasmakerspace.members

import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.ActiveDirectoryService
import org.dallasmakerspace.activedirectory.AmbiguousGroupException
import org.dallasmakerspace.activedirectory.GroupNotFoundException
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.DMSMember
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

private const val MAIN_INFRA_DN = "CN=Infrastructure,OU=Security,OU=Groups,DC=dms,DC=local"
private const val ADMIN_INFRA_DN = "CN=Infrastructure,OU=Groups,OU=Admin,DC=dms,DC=local"
private const val OTHER_INFRA_DN = "CN=Infrastructure,CN=Users,DC=dms,DC=local"

class MemberServiceArchiveSafetyTest {
  private lateinit var directory: IActiveDirectoryService
  private lateinit var history: IGroupHistoryRepository
  private lateinit var service: MemberService

  @Before
  fun setUp() {
    val loggerFactory: LoggerFactory = mock()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(mock())
    directory = mock()
    history = mock()
    service =
        MemberService(
            loggerFactory = loggerFactory,
            discourseService = mock(),
            discourseAvatarService = mock(),
            memberRepository = mock(),
            whmcsDataService = mock(),
            activityLogService = mock(),
            makerManagerDataService = mock(),
            activeDirectoryService = directory,
            voterRegistrationManager = mock(),
            groupHistoryRepository = history,
            doorEventsRepository = mock(),
        )
  }

  private fun group(cn: String, dn: String) =
      ADGroup(
          cn = cn,
          description = null,
          distinguishedName = dn,
          objectGuid = null,
          members = emptyList(),
          membersListIncomplete = false)

  private fun user(vararg groupDns: String) =
      ADUser(
          sAMAccountName = "actor",
          mail = "actor@example.invalid",
          givenName = "A",
          sn = "B",
          telephoneNumber = null,
          employeeID = null,
          displayName = "A B",
          objectGuid = "guid",
          whenCreated = null,
          enabled = true,
          groups = groupDns.map { group(it.substringAfter("CN=").substringBefore(","), it) })

  // L3: the Infrastructure check

  @Test
  fun `a member of the Infrastructure group in the main OU is an infra user`() {
    whenever(directory.getGroupStrict("Infrastructure")).thenReturn(group("Infrastructure", MAIN_INFRA_DN))
    whenever(directory.getMemberByUsername("actor")).thenReturn(user(MAIN_INFRA_DN))

    assertTrue(service.isUserInInfraGroup("actor"))
  }

  @Test
  fun `a group that merely has the same name elsewhere does not count`() {
    whenever(directory.getGroupStrict("Infrastructure")).thenReturn(group("Infrastructure", MAIN_INFRA_DN))
    whenever(directory.getMemberByUsername("decoy")).thenReturn(user(ADMIN_INFRA_DN))
    whenever(directory.getMemberByUsername("decoy2")).thenReturn(user(OTHER_INFRA_DN))

    assertFalse(service.isUserInInfraGroup("decoy"))
    assertFalse(service.isUserInInfraGroup("decoy2"))
  }

  @Test
  fun `the DN match ignores case and spacing`() {
    whenever(directory.getGroupStrict("Infrastructure")).thenReturn(group("Infrastructure", MAIN_INFRA_DN))
    whenever(directory.getMemberByUsername("actor"))
        .thenReturn(user(MAIN_INFRA_DN.lowercase().replace(",", ", ")))

    assertTrue(service.isUserInInfraGroup("actor"))
  }

  @Test
  fun `the group DN is resolved once`() {
    whenever(directory.getGroupStrict("Infrastructure")).thenReturn(group("Infrastructure", MAIN_INFRA_DN))
    whenever(directory.getMemberByUsername("actor")).thenReturn(user(MAIN_INFRA_DN))

    service.isUserInInfraGroup("actor")
    service.isUserInInfraGroup("actor")

    verify(directory, org.mockito.kotlin.times(1)).getGroupStrict("Infrastructure")
  }

  @Test
  fun `a duplicated or misplaced Infrastructure group, or a directory error, fails closed and is retried`() {
    whenever(directory.getMemberByUsername("actor")).thenReturn(user(MAIN_INFRA_DN))
    doAnswer { throw AmbiguousGroupException("Infrastructure", 2) }
        .whenever(directory)
        .getGroupStrict("Infrastructure")
    assertFalse(service.isUserInInfraGroup("actor"))

    doReturn(group("Infrastructure", ADMIN_INFRA_DN))
        .whenever(directory)
        .getGroupStrict("Infrastructure")
    assertFalse(service.isUserInInfraGroup("actor"))

    doAnswer { throw IOException("down") }.whenever(directory).getGroupStrict("Infrastructure")
    assertFalse(service.isUserInInfraGroup("actor"))

    doReturn(group("Infrastructure", MAIN_INFRA_DN))
        .whenever(directory)
        .getGroupStrict("Infrastructure")
    assertTrue(service.isUserInInfraGroup("actor"))
  }

  // M4: no portal rows for groups that are not in AD

  @Test
  fun `adding members to a group that is gone writes nothing to the portal`() = runBlocking<Unit> {
    whenever(directory.groupExists("Gone Group")).thenReturn(false)

    assertFailsWith<GroupNotFoundException> {
      service.addMembersToGroup(listOf("asmith"), "Gone+Group", "jdoe")
    }

    verify(history, never()).getOrCreateGroup(any(), any())
    verify(history, never()).insertGroupHistory(any(), any(), any(), any(), any(), any())
    verify(directory, never()).addUsersToGroup(any(), any())
  }

  @Test
  fun `removing members from a group that is gone writes nothing to the portal`() =
      runBlocking<Unit> {
        whenever(directory.groupExists("Gone Group")).thenReturn(false)

        assertFailsWith<GroupNotFoundException> {
          service.removeMembersToGroup(listOf("asmith"), "Gone+Group", "jdoe")
        }

        verify(history, never()).getOrCreateGroup(any(), any())
        verify(directory, never()).removeUsersFromGroup(any(), any())
      }

  @Test
  fun `a group that exists still gets its row and the directory write`() = runBlocking<Unit> {
    whenever(directory.groupExists("Live Group")).thenReturn(true)
    whenever(history.getOrCreateGroup(any(), anyOrNull())).thenReturn(1)

    service.addMembersToGroup(listOf("asmith"), "Live+Group", null)
    service.removeMembersToGroup(listOf("asmith"), "Live+Group", null)

    verify(history, org.mockito.kotlin.times(2)).getOrCreateGroup("Live Group")
    verify(directory).addUsersToGroup(listOf("asmith"), "Live Group")
    verify(directory).removeUsersFromGroup(listOf("asmith"), "Live Group")
  }

  @Test
  fun `a stale history event for a deleted group creates no group row`() = runBlocking<Unit> {
    MembersGroupsTestDb.reset()
    val memberService: MemberService = mock()
    val ad: ActiveDirectoryService = mock()
    whenever(ad.getMembersByDnList(any())).thenReturn(listOf(user()))
    whenever(memberService.getMemberByUsername(any(), any())).thenReturn(DMSMember(id = 1, username = "actor"))
    whenever(ad.groupExists("Gone Group")).thenReturn(false)
    val events = GroupHistoryService(memberService, ad)

    val recorded =
        events.recordGroupHistoryEvent(
            "actor", "CN=A B,OU=Members,DC=dms,DC=local", "Gone Group", LocalDateTime(2030, 1, 2, 3, 4))

    assertFalse(recorded)
    assertEquals(0, MembersGroupsTestDb.count())
  }
}
