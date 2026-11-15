package org.dallasmakerspace.dashboards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.FeatureFlagService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.models.Committee
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class CommitteeAccessTest {
  private val committee =
      Committee(id = 99, name = "Test Shop", chairGroupName = "Test Chair", groupPrefixes = listOf("TS"))
  private val ad = mock<IActiveDirectoryService>()
  private val members = mock<MemberService>()
  private val flags = mock<FeatureFlagService>()
  private val config = mock<ConfigOverrideService>()
  private val access: CommitteeAccess

  init {
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(mock<Logger>())
    access = CommitteeAccess(ad, members, flags, config, loggerFactory)
  }

  private fun inGroups(username: String, vararg groups: String) {
    whenever(ad.getMemberByUsername(username))
        .thenReturn(
            ADUser(
                sAMAccountName = username,
                mail = "$username@example.com",
                givenName = null,
                sn = null,
                telephoneNumber = null,
                employeeID = null,
                displayName = username,
                objectGuid = "guid-$username",
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
                    }))
  }

  @Test
  fun `a chair of the committee passes, case-insensitively`() = runBlocking {
    inGroups("chairperson", "test chair")

    assertTrue(access.isChairOrInfra("chairperson", committee))
  }

  @Test
  fun `an ordinary member does not pass`() = runBlocking {
    inGroups("asmith", "TS Basics")

    assertFalse(access.isChairOrInfra("asmith", committee))
  }

  @Test
  fun `the infrastructure group passes for any committee`() = runBlocking {
    inGroups("admin", "Something Else")
    whenever(members.isUserInInfraGroup("admin")).thenReturn(true)

    assertTrue(access.isChairOrInfra("admin", committee))
  }

  @Test
  fun `a directory error denies`() = runBlocking {
    whenever(ad.getMemberByUsername("chairperson")).thenThrow(RuntimeException("AD down"))

    assertFalse(access.isChairOrInfra("chairperson", committee))
  }

  @Test
  fun `editing needs the committee enabled, the flag on and a chair`() = runBlocking {
    inGroups("chairperson", "Test Chair")
    whenever(config.get(ConfigRegistry.COMMITTEE_DASHBOARD_COMMITTEES)).thenReturn("Test Shop")
    whenever(flags.isEnabledForUsername(eq(ConfigRegistry.COMMITTEE_DASHBOARD_ENABLED), any()))
        .thenReturn(true)
    assertTrue(access.canEditDashboard("chairperson", committee))

    whenever(flags.isEnabledForUsername(eq(ConfigRegistry.COMMITTEE_DASHBOARD_ENABLED), any()))
        .thenReturn(false)
    assertFalse(access.canEditDashboard("chairperson", committee))
  }

  @Test
  fun `a committee not in the list has no dashboard`() = runBlocking {
    whenever(config.get(ConfigRegistry.COMMITTEE_DASHBOARD_COMMITTEES)).thenReturn("Woodshop")

    assertFalse(access.hasDashboard(committee))
  }

  @Test
  fun `the committee list ignores case and spacing`() {
    assertEquals(setOf("woodshop", "3d fab"), CommitteeAccess.dashboardCommittees(" Woodshop, 3D Fab ,,"))
  }
}
