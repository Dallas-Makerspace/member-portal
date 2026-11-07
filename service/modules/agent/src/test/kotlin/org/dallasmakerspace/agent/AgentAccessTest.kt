package org.dallasmakerspace.agent

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.config.ConfigKey
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigValueType
import org.dallasmakerspace.config.FeatureFlagValue
import org.dallasmakerspace.core.LoggerFactory
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.slf4j.Logger

class AgentAccessTest {
  private val flagKey =
      ConfigKey("test.enabled", "test", "Test", FeatureFlagValue(), ConfigValueType.FeatureFlagType)
  private val bot = BotDefinition("ask-ai", "Ask AI", "Help.", emptyList(), flagKey)
  private val loggerFactory = mock<LoggerFactory> { on { create(any()) } doReturn mock<Logger>() }

  private fun group(name: String) = ADGroup(name, null, "cn=$name", null, emptyList(), false)

  private val directory =
      mock<IActiveDirectoryService> {
        on { getMembersByUsernameList(any()) } doAnswer
            {
              mapOf(
                  "jdoe" to
                      ADUser("jdoe", "m@example.test", null, null, null, null, "J Doe", "g", null, true,
                          listOf(group("Pilot Group"))))
            }
      }

  private fun access(flag: FeatureFlagValue): AgentAccess {
    val config =
        mock<ConfigOverrideService> {
          onBlocking { get(flagKey, false) } doReturn flag
          onBlocking { isFeatureEnabledFor(any(), any(), any()) } doAnswer
              { call ->
                val groups = call.getArgument<Collection<String>>(2)
                flag.enabled || call.getArgument<String>(1) in flag.enabledUsernames ||
                    groups.any { it in flag.enabledGroupNames }
              }
        }
    return AgentAccess(directory, config, loggerFactory)
  }

  @Test
  fun `a flag on for everyone needs no directory lookup`() = runBlocking<Unit> {
    assertTrue(access(FeatureFlagValue(enabled = true)).canUse(bot, "jdoe"))
    verify(directory, times(0)).getMembersByUsernameList(any())
  }

  @Test
  fun `a flag that is off with no pilot refuses without a directory lookup`() = runBlocking<Unit> {
    assertFalse(access(FeatureFlagValue()).canUse(bot, "jdoe"))
    verify(directory, times(0)).getMembersByUsernameList(any())
  }

  @Test
  fun `a member piloted by username needs no directory lookup`() = runBlocking<Unit> {
    assertTrue(access(FeatureFlagValue(enabledUsernames = setOf("jdoe"))).canUse(bot, "jdoe"))
    verify(directory, times(0)).getMembersByUsernameList(any())
  }

  @Test
  fun `a group pilot looks the member's groups up once`() = runBlocking<Unit> {
    val access = access(FeatureFlagValue(enabledGroupNames = setOf("Pilot Group")))

    assertTrue(access.canUse(bot, "jdoe"))
    assertTrue(access.canUse(bot, "jdoe"))

    verify(directory, times(1)).getMembersByUsernameList(any())
  }

  @Test
  fun `an audience restriction still checks the member's groups`() = runBlocking<Unit> {
    val staffBot = bot.copy(audience = BotAudience.AdGroupSuffix("Missing Suffix"))

    assertFalse(access(FeatureFlagValue(enabled = true)).canUse(staffBot, "jdoe"))
    verify(directory, times(1)).getMembersByUsernameList(any())
  }
}
