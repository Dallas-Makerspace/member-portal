package org.dallasmakerspace.config

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.config.db.ConfigOverrideDAO
import org.dallasmakerspace.config.db.IConfigOverrideRepository
import org.dallasmakerspace.core.LoggerFactory
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class ConfigOverrideServiceTest {

  private lateinit var repository: IConfigOverrideRepository
  private lateinit var service: ConfigOverrideService

  private val flagKey =
      ConfigKey(
          key = "test.feature-flag",
          description = "Test flag",
          category = "Test",
          defaultValue = FeatureFlagValue(enabled = false),
          type = ConfigValueType.FeatureFlagType,
      )

  @Before
  fun setUp() {
    repository = mock()
    runBlocking { whenever(repository.getCurrent(flagKey.key)).thenReturn(null) }
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(ConfigOverrideService::class.java)).thenReturn(mock<Logger>())
    service = ConfigOverrideService(repository, loggerFactory)
  }

  @Test
  fun `serialize and deserialize a FeatureFlagValue round-trips`() {
    val value = FeatureFlagValue(enabled = true, enabledUsernames = setOf("jdoe"))
    val raw = service.serialize(value, flagKey)
    assertEquals(value, service.deserialize(raw, flagKey))
  }

  @Test
  fun `isFeatureEnabledFor is true when globally enabled`() = runBlocking {
    runBlocking { whenever(repository.getCurrent(flagKey.key)).thenReturn(null) }
    val enabledKey = flagKey.copy(defaultValue = FeatureFlagValue(enabled = true))
    assertTrue(service.isFeatureEnabledFor(enabledKey, "anyone", emptyList()))
  }

  @Test
  fun `isFeatureEnabledFor is true for an allow-listed username even when globally disabled`() =
      runBlocking {
        val piloted =
            flagKey.copy(
                defaultValue = FeatureFlagValue(enabled = false, enabledUsernames = setOf("jdoe")))
        assertTrue(service.isFeatureEnabledFor(piloted, "jdoe", emptyList()))
        assertFalse(service.isFeatureEnabledFor(piloted, "someoneelse", emptyList()))
      }

  @Test
  fun `isFeatureEnabledFor is true for an allow-listed group even when globally disabled`() =
      runBlocking {
        val piloted =
            flagKey.copy(
                defaultValue =
                    FeatureFlagValue(enabled = false, enabledGroupNames = setOf("Board")))
        assertTrue(service.isFeatureEnabledFor(piloted, "jdoe", listOf("Board", "Members")))
        assertFalse(service.isFeatureEnabledFor(piloted, "jdoe", listOf("Members")))
      }

  @Test
  fun `isFeatureEnabledFor is false when globally disabled and no allowlist matches`() =
      runBlocking {
        assertFalse(service.isFeatureEnabledFor(flagKey, "jdoe", listOf("Members")))
      }

  private fun dao(raw: String, reset: Boolean = false, infraOnly: Boolean = false) =
      mock<ConfigOverrideDAO> {
        on { configValue } doReturn raw
        on { isReset } doReturn reset
        on { this.infraOnly } doReturn infraOnly
      }

  @Test
  fun `getFresh returns the stored value even when it is infra-only`() = runBlocking {
    val raw = service.serialize(FeatureFlagValue(enabledGroupNames = setOf("Some Group")), flagKey)
    val stored = dao(raw, infraOnly = true)
    whenever(repository.getCurrent(flagKey.key)).thenReturn(stored)
    assertEquals(setOf("Some Group"), service.getFresh(flagKey).enabledGroupNames)
  }

  @Test
  fun `getFresh falls back to the default when nothing is stored or it was reset`() = runBlocking {
    assertEquals(flagKey.defaultValue, service.getFresh(flagKey))
    val reset = dao("ignored", reset = true)
    whenever(repository.getCurrent(flagKey.key)).thenReturn(reset)
    assertEquals(flagKey.defaultValue, service.getFresh(flagKey))
  }
}
