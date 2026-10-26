package org.dallasmakerspace.members

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.discourse.DiscourseService
import org.dallasmakerspace.models.ActivityLogEvent
import org.dallasmakerspace.models.DMSMember
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MemberServiceAutoLinkTest {
  private lateinit var directory: IActiveDirectoryService
  private lateinit var repository: IMemberRepository
  private lateinit var discourse: DiscourseService
  private lateinit var activityLog: ActivityLogService
  private lateinit var service: MemberService

  @Before
  fun setUp() = runBlocking<Unit> {
    val loggerFactory: LoggerFactory = mock()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(mock())
    directory = mock()
    repository = mock()
    discourse = mock()
    activityLog = mock()
    service =
        MemberService(
            loggerFactory = loggerFactory,
            discourseService = discourse,
            discourseAvatarService = mock(),
            memberRepository = repository,
            whmcsDataService = mock(),
            activityLogService = activityLog,
            makerManagerDataService = mock(),
            activeDirectoryService = directory,
            voterRegistrationManager = mock(),
            groupHistoryRepository = mock(),
            doorEventsRepository = mock(),
        )
    whenever(directory.getMemberByUsername("jdoe")).thenReturn(adUser(enabled = true))
    whenever(repository.getMemberOrInsert("jdoe", true)).thenReturn(member())
    whenever(repository.getMembersByDiscourseUsernames(listOf("talk-jdoe"))).thenReturn(emptyMap())
    whenever(repository.claimDiscourseUsernameIfUnlinked("jdoe", "talk-jdoe")).thenReturn(true)
  }

  private fun adUser(enabled: Boolean) =
      ADUser(
          sAMAccountName = "jdoe",
          mail = "jdoe@example.invalid",
          givenName = "J",
          sn = "Doe",
          telephoneNumber = null,
          employeeID = null,
          displayName = "J Doe",
          objectGuid = "guid",
          whenCreated = null,
          enabled = enabled,
          groups = emptyList())

  private fun member(discourseUsername: String? = null) =
      DMSMember(id = 1, username = "jdoe", enabled = true, discourseUsername = discourseUsername)

  @Test
  fun `links by claiming, adding to the group and logging the trigger`() = runBlocking<Unit> {
    assertEquals(AutoLinkOutcome.LINKED, service.autoLinkDiscourse("jdoe", "talk-jdoe", "cron"))

    verify(repository).claimDiscourseUsernameIfUnlinked("jdoe", "talk-jdoe")
    verify(discourse).addUserToDmsMembersV2Group(listOf("talk-jdoe"))
    verify(activityLog)
        .insertActivityLogEntry(
            "jdoe", "jdoe", ActivityLogEvent.LINK_DISCOURSE, """{"auto":true,"trigger":"cron"}""")
    verify(repository, never()).updateMember(any(), any())
  }

  @Test
  fun `a member disabled in AD is not linked`() = runBlocking<Unit> {
    whenever(directory.getMemberByUsername("jdoe")).thenReturn(adUser(enabled = false))

    assertEquals(
        AutoLinkOutcome.NOT_ENABLED_IN_AD, service.autoLinkDiscourse("jdoe", "talk-jdoe", "cron"))

    verify(repository, never()).claimDiscourseUsernameIfUnlinked(any(), any())
    verify(discourse, never()).addUserToDmsMembersV2Group(any())
  }

  @Test
  fun `a member already linked to the same account is left alone, ignoring case`() = runBlocking<Unit> {
    whenever(repository.getMemberOrInsert("jdoe", true)).thenReturn(member("Talk-JDoe"))

    assertEquals(
        AutoLinkOutcome.ALREADY_LINKED, service.autoLinkDiscourse("jdoe", "talk-jdoe", "cron"))

    verify(repository, never()).claimDiscourseUsernameIfUnlinked(any(), any())
  }

  @Test
  fun `a member linked to a different account is left alone`() = runBlocking<Unit> {
    whenever(repository.getMemberOrInsert("jdoe", true)).thenReturn(member("talk-other"))

    assertEquals(
        AutoLinkOutcome.MEMBER_HAS_OTHER_LINK,
        service.autoLinkDiscourse("jdoe", "talk-jdoe", "webhook"))

    verify(repository, never()).claimDiscourseUsernameIfUnlinked(any(), any())
    verify(discourse, never()).addUserToDmsMembersV2Group(any())
  }

  @Test
  fun `a Talk account held by a disabled member is not taken over`() = runBlocking<Unit> {
    val holder = DMSMember(id = 2, username = "asmith", enabled = false, discourseUsername = "talk-jdoe")
    whenever(repository.getMembersByDiscourseUsernames(listOf("talk-jdoe")))
        .thenReturn(mapOf("talk-jdoe" to holder))

    assertEquals(
        AutoLinkOutcome.TALK_ACCOUNT_TAKEN, service.autoLinkDiscourse("jdoe", "talk-jdoe", "cron"))

    verify(repository, never()).claimDiscourseUsernameIfUnlinked(any(), any())
    verify(repository, never()).updateMember(any(), any())
    verify(discourse, never()).removeUserFromDmsMembersV2Group(any())
  }

  @Test
  fun `a lost claim does not touch the Talk group`() = runBlocking<Unit> {
    whenever(repository.claimDiscourseUsernameIfUnlinked("jdoe", "talk-jdoe")).thenReturn(false)

    assertEquals(AutoLinkOutcome.LOST_RACE, service.autoLinkDiscourse("jdoe", "talk-jdoe", "cron"))

    verify(discourse, never()).addUserToDmsMembersV2Group(any())
    verify(activityLog, never())
        .insertActivityLogEntry(any(), any(), any(), anyOrNull())
  }

  @Test
  fun `a failed group add releases the claim and rethrows`() = runBlocking<Unit> {
    whenever(discourse.addUserToDmsMembersV2Group(listOf("talk-jdoe")))
        .doThrow(IllegalStateException("boom"))

    assertFailsWith<IllegalStateException> {
      service.autoLinkDiscourse("jdoe", "talk-jdoe", "cron")
    }

    verify(repository).releaseDiscourseUsername("jdoe", "talk-jdoe")
    verify(activityLog, never())
        .insertActivityLogEntry(any(), any(), any(), anyOrNull())
  }
}
