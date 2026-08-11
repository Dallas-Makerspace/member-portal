package org.dallasmakerspace.members

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.db.master.IMakerManagerDataService
import org.dallasmakerspace.db.master.IWhmcsDataService
import org.dallasmakerspace.discourse.DiscourseAvatarService
import org.dallasmakerspace.discourse.DiscourseService
import org.dallasmakerspace.doorcontroller.DoorEventsRepository
import org.dallasmakerspace.voterregistration.VoterRegistrationManager
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.Logger

private const val ADMIN_CN = "CA Teachers"
private const val ADMIN_DN = "CN=CA Teachers,OU=Security,OU=Groups,DC=dms,DC=local"
private const val MID_CN = "Fiber Arts Leads"
private const val MID_DN = "CN=Fiber Arts Leads,OU=Security,OU=Groups,DC=dms,DC=local"
private const val FAR_DN = "CN=Sewing 101 TAs,OU=Security,OU=Groups,DC=dms,DC=local"
private const val TARGET_GROUP_SLUG = "CA+Sewing+Basics"
private const val TARGET_GROUP_NAME = "CA Sewing Basics"
// A person-type managedBy entry — under OU=Members, not OU=Security/Organizational/Groups, so
// isGroupDN treats it as a person, not a group (mirrors "Susie Kadota7299" set directly as
// managedBy on "Voting Members - Test").
private const val PERSON_ADMIN_DN = "CN=Susie Kadota7299,OU=Members,DC=dms,DC=local"

class MemberServiceGroupAdminTest {

  private lateinit var mockActiveDirectoryService: IActiveDirectoryService
  private lateinit var mockLogger: Logger
  private lateinit var service: MemberService

  @Before
  fun setUp() {
    val mockLoggerFactory: LoggerFactory = mock()
    mockLogger = mock()
    whenever(mockLoggerFactory.create(any<Class<*>>())).thenReturn(mockLogger)
    mockActiveDirectoryService = mock()

    service =
        MemberService(
            loggerFactory = mockLoggerFactory,
            discourseService = mock<DiscourseService>(),
            discourseAvatarService = mock<DiscourseAvatarService>(),
            memberRepository = mock(),
            whmcsDataService = mock<IWhmcsDataService>(),
            activityLogService = mock(),
            makerManagerDataService = mock<IMakerManagerDataService>(),
            activeDirectoryService = mockActiveDirectoryService,
            voterRegistrationManager = mock<VoterRegistrationManager>(),
            groupHistoryRepository = mock(),
            doorEventsRepository = mock<DoorEventsRepository>(),
        )
  }

  private fun adGroup(cn: String, dn: String, nestedGroups: List<String> = emptyList()) =
      ADGroup(
          cn = cn,
          description = null,
          distinguishedName = dn,
          objectGuid = null,
          members = emptyList(),
          membersListIncomplete = false,
          nestedGroups = nestedGroups)

  private fun adUser(directGroupDns: List<String>, ownDn: String = "CN=Actor User,OU=Members,DC=dms,DC=local") =
      ADUser(
          sAMAccountName = "actor",
          mail = "actor@dallasmakerspace.org",
          givenName = "Actor",
          sn = "User",
          telephoneNumber = null,
          employeeID = null,
          displayName = "Actor User",
          objectGuid = "guid",
          whenCreated = null,
          enabled = true,
          distinguishedName = ownDn,
          groups = directGroupDns.map { adGroup(cn = it, dn = it) })

  @Test
  fun `direct member of an admin group is a group admin`() = runBlocking {
    whenever(mockActiveDirectoryService.getGroup(TARGET_GROUP_NAME))
        .thenReturn(adGroup(TARGET_GROUP_NAME, "irrelevant", nestedGroups = emptyList()).copy(
            administrators = listOf(ADMIN_DN)))
    whenever(mockActiveDirectoryService.getMemberByUsername("nomad4life"))
        .thenReturn(adUser(directGroupDns = listOf(ADMIN_DN)))

    assertTrue(service.isGroupAdmin("nomad4life", TARGET_GROUP_SLUG))
  }

  @Test
  fun `direct member matches despite differing DN casing`() = runBlocking {
    // The same AD object's DN can come back with different casing depending on which attribute
    // it was read from (managedBy vs. memberOf) — the match must not be case-sensitive.
    whenever(mockActiveDirectoryService.getGroup(TARGET_GROUP_NAME))
        .thenReturn(adGroup(TARGET_GROUP_NAME, "irrelevant").copy(administrators = listOf(ADMIN_DN)))
    whenever(mockActiveDirectoryService.getMemberByUsername("nomad4life"))
        .thenReturn(adUser(directGroupDns = listOf(ADMIN_DN.uppercase())))

    assertTrue(service.isGroupAdmin("nomad4life", TARGET_GROUP_SLUG))
  }

  @Test
  fun `the person set as managedBy is a group admin`() {
    runBlocking {
      whenever(mockActiveDirectoryService.getGroup(TARGET_GROUP_NAME))
          .thenReturn(
              adGroup(TARGET_GROUP_NAME, "irrelevant")
                  .copy(administrators = listOf(PERSON_ADMIN_DN)))
      whenever(mockActiveDirectoryService.getMemberByUsername("susie"))
          .thenReturn(adUser(directGroupDns = emptyList(), ownDn = PERSON_ADMIN_DN))

      assertTrue(service.isGroupAdmin("susie", TARGET_GROUP_SLUG))
      // A person entry has no members to walk — getGroup should only be called once, for the
      // target group itself, never for a nested-group expansion hop.
      verify(mockActiveDirectoryService, times(1)).getGroup(any<String>())
    }
  }

  @Test
  fun `a different member is not a group admin when managedBy is a specific person`() = runBlocking {
    whenever(mockActiveDirectoryService.getGroup(TARGET_GROUP_NAME))
        .thenReturn(
            adGroup(TARGET_GROUP_NAME, "irrelevant").copy(administrators = listOf(PERSON_ADMIN_DN)))
    whenever(mockActiveDirectoryService.getMemberByUsername("outsider"))
        .thenReturn(adUser(directGroupDns = emptyList()))

    assertFalse(service.isGroupAdmin("outsider", TARGET_GROUP_SLUG))
  }

  @Test
  fun `member of a group nested one hop inside an admin group is a group admin`() = runBlocking {
    whenever(mockActiveDirectoryService.getGroup(TARGET_GROUP_NAME))
        .thenReturn(adGroup(TARGET_GROUP_NAME, "irrelevant").copy(administrators = listOf(ADMIN_DN)))
    whenever(mockActiveDirectoryService.getMemberByUsername("someuser"))
        .thenReturn(adUser(directGroupDns = listOf(MID_DN)))
    whenever(mockActiveDirectoryService.getGroup(ADMIN_CN))
        .thenReturn(adGroup(ADMIN_CN, ADMIN_DN, nestedGroups = listOf(MID_DN)))

    assertTrue(service.isGroupAdmin("someuser", TARGET_GROUP_SLUG))
  }

  @Test
  fun `member nested two hops beyond an admin group is not a group admin - depth cap bites`() {
    runBlocking {
      whenever(mockActiveDirectoryService.getGroup(TARGET_GROUP_NAME))
          .thenReturn(
              adGroup(TARGET_GROUP_NAME, "irrelevant").copy(administrators = listOf(ADMIN_DN)))
      whenever(mockActiveDirectoryService.getMemberByUsername("someuser"))
          .thenReturn(adUser(directGroupDns = listOf(FAR_DN)))
      whenever(mockActiveDirectoryService.getGroup(ADMIN_CN))
          .thenReturn(adGroup(ADMIN_CN, ADMIN_DN, nestedGroups = listOf(MID_DN)))
      // Would match if the walk went one hop further — it must not get here.
      whenever(mockActiveDirectoryService.getGroup(MID_CN))
          .thenReturn(adGroup(MID_CN, MID_DN, nestedGroups = listOf(FAR_DN)))

      assertFalse(service.isGroupAdmin("someuser", TARGET_GROUP_SLUG))
      verify(mockActiveDirectoryService, never()).getGroup(MID_CN)
    }
  }

  @Test
  fun `unrelated member is not a group admin`() = runBlocking {
    whenever(mockActiveDirectoryService.getGroup(TARGET_GROUP_NAME))
        .thenReturn(adGroup(TARGET_GROUP_NAME, "irrelevant").copy(administrators = listOf(ADMIN_DN)))
    whenever(mockActiveDirectoryService.getMemberByUsername("outsider"))
        .thenReturn(adUser(directGroupDns = listOf(FAR_DN)))
    whenever(mockActiveDirectoryService.getGroup(ADMIN_CN))
        .thenReturn(adGroup(ADMIN_CN, ADMIN_DN, nestedGroups = emptyList()))

    assertFalse(service.isGroupAdmin("outsider", TARGET_GROUP_SLUG))
  }

  @Test
  fun `no administrators means no group admin`() = runBlocking {
    whenever(mockActiveDirectoryService.getGroup(TARGET_GROUP_NAME))
        .thenReturn(adGroup(TARGET_GROUP_NAME, "irrelevant", nestedGroups = emptyList()))

    assertFalse(service.isGroupAdmin("nomad4life", TARGET_GROUP_SLUG))
  }

  @Test
  fun `AD failure defaults to not a group admin`() = runBlocking {
    whenever(mockActiveDirectoryService.getGroup(TARGET_GROUP_NAME))
        .thenThrow(RuntimeException("AD unavailable"))

    assertFalse(service.isGroupAdmin("nomad4life", TARGET_GROUP_SLUG))
  }

  @Test
  fun `a failed nested-group lookup is logged and does not abort the whole check`() = runBlocking {
    // MID_CN's own nested-group lookup fails (renamed group, transient LDAP timeout, etc). The
    // failure must be logged (not silently swallowed) and must not prevent other admin DNs —
    // there are none here, so the overall result is false, but via the logged path, not a crash.
    whenever(mockActiveDirectoryService.getGroup(TARGET_GROUP_NAME))
        .thenReturn(adGroup(TARGET_GROUP_NAME, "irrelevant").copy(administrators = listOf(ADMIN_DN)))
    whenever(mockActiveDirectoryService.getMemberByUsername("someuser"))
        .thenReturn(adUser(directGroupDns = listOf(FAR_DN)))
    whenever(mockActiveDirectoryService.getGroup(ADMIN_CN)).thenThrow(RuntimeException("AD timeout"))

    assertFalse(service.isGroupAdmin("someuser", TARGET_GROUP_SLUG))
    verify(mockLogger).warn(any<String>(), any<Throwable>())
  }
}
