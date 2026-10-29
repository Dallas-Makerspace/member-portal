package org.dallasmakerspace.itbot

import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.slf4j.Logger

class RepoMirrorLazyStartTest {
  private val loggerFactory = mock<LoggerFactory> { on { create(any()) } doReturn mock<Logger>() }
  private val appConfig =
      mock<AppConfig> {
        on { requireBooleanProperty(any()) } doReturn false
        on { getStringProperty(any(), any()) } doReturn System.getProperty("java.io.tmpdir") + "/lazy-start-test"
      }

  private fun mirror(config: ConfigOverrideService) =
      RepoMirrorService(config, GitRunner(), appConfig, loggerFactory)

  @Test
  fun `nothing is read or cloned until the first use, and later uses do not start again`() = runBlocking<Unit> {
    val config = mock<ConfigOverrideService> { onBlocking { get(ConfigRegistry.AGENT_IT_BOT_REPOS, false) } doReturn "" }
    val mirror = mirror(config)

    verifyBlocking(config, times(0)) { get(ConfigRegistry.AGENT_IT_BOT_REPOS, false) }

    mirror.awaitReady()
    mirror.awaitReady()
    mirror.ensureStarted()

    verifyBlocking(config, times(1)) { get(ConfigRegistry.AGENT_IT_BOT_REPOS, false) }
  }

  @Test
  fun `waiting for the first sync gives up after the timeout`() = runBlocking<Unit> {
    val config =
        mock<ConfigOverrideService> {
          onBlocking { get(ConfigRegistry.AGENT_IT_BOT_REPOS, false) } doSuspendableAnswer
              {
                delay(5_000)
                ""
              }
        }
    val started = System.currentTimeMillis()

    mirror(config).awaitReady(timeoutMillis = 100)

    assertTrue(System.currentTimeMillis() - started < 3_000)
  }
}
