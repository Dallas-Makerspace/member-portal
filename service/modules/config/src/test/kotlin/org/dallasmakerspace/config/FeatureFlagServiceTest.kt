package org.dallasmakerspace.config

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.LoggerFactory
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class FeatureFlagServiceTest {

  private lateinit var configOverrideService: ConfigOverrideService
  private lateinit var activeDirectoryService: IActiveDirectoryService
  private lateinit var featureFlagService: FeatureFlagService

  private val flagKey =
      ConfigKey(
          key = "test.feature-flag",
          description = "Test flag",
          category = "Test",
          defaultValue = FeatureFlagValue(enabled = false),
          type = ConfigValueType.FeatureFlagType,
      )

  private fun adUser(groupCns: List<String>) =
      ADUser(
          sAMAccountName = "jdoe",
          mail = "jdoe@example.com",
          givenName = "Jane",
          sn = "Doe",
          telephoneNumber = null,
          employeeID = null,
          displayName = "Jane Doe",
          objectGuid = "guid",
          whenCreated = null,
          enabled = true,
          groups = groupCns.map { ADGroup(it, null, "CN=$it", null, emptyList(), false) },
      )

  @Before
  fun setUp() {
    configOverrideService = mock()
    activeDirectoryService = mock()
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(FeatureFlagService::class.java)).thenReturn(mock<Logger>())
    featureFlagService = FeatureFlagService(configOverrideService, activeDirectoryService, loggerFactory)
  }

  @Test
  fun `resolves AD groups and delegates to ConfigOverrideService`() = runBlocking {
    whenever(activeDirectoryService.getMembersByUsernameList(listOf("jdoe")))
        .thenReturn(mapOf("jdoe" to adUser(listOf("Board", "Members"))))
    whenever(
            configOverrideService.isFeatureEnabledFor(
                flagKey, "jdoe", listOf("Board", "Members")))
        .thenReturn(true)

    assertTrue(featureFlagService.isEnabledForUsername(flagKey, "jdoe"))
  }

  @Test
  fun `treats an AD lookup failure as no group memberships rather than failing the check`() =
      runBlocking {
        whenever(activeDirectoryService.getMembersByUsernameList(listOf("jdoe")))
            .thenThrow(RuntimeException("LDAP unreachable"))
        whenever(configOverrideService.isFeatureEnabledFor(flagKey, "jdoe", emptyList()))
            .thenReturn(false)

        assertFalse(featureFlagService.isEnabledForUsername(flagKey, "jdoe"))
      }

  @Test
  fun `treats an unknown username as no group memberships`() = runBlocking {
    whenever(activeDirectoryService.getMembersByUsernameList(listOf("ghost")))
        .thenReturn(mapOf("ghost" to null))
    whenever(configOverrideService.isFeatureEnabledFor(flagKey, "ghost", emptyList()))
        .thenReturn(false)

    assertFalse(featureFlagService.isEnabledForUsername(flagKey, "ghost"))
  }
}
