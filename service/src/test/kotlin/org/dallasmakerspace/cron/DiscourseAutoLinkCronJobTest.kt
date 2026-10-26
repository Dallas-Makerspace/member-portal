package org.dallasmakerspace.cron

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.discourse.DiscourseAutoLinkService
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class DiscourseAutoLinkCronJobTest {
  private val service = mock<DiscourseAutoLinkService>()

  private fun job(): DiscourseAutoLinkCronJob {
    val loggerFactory: LoggerFactory = mock()
    val logger: Logger = mock()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(logger)
    whenever(loggerFactory.createInMemoryLogger(any<Class<*>>())).thenReturn(logger)
    return DiscourseAutoLinkCronJob(loggerFactory, service)
  }

  @Test
  fun `params default to a dry run`() {
    assertEquals(true, DiscourseAutoLinkCronJobParams().dryRun)
  }

  @Test
  fun `the report from the service is returned as is`() = runBlocking<Unit> {
    whenever(service.runNightly(true)).thenReturn("mode=DRY_RUN")

    assertEquals("mode=DRY_RUN", job().run(DiscourseAutoLinkCronJobParams(dryRun = true)))

    verify(service).runNightly(true)
  }

  @Test
  fun `a second call while one is running is turned away`() = runBlocking<Unit> {
    val release = CompletableDeferred<Unit>()
    val started = CompletableDeferred<Unit>()
    whenever(service.runNightly(false)).thenAnswer {
      runBlocking {
        started.complete(Unit)
        release.await()
      }
      "first done"
    }
    val first = async(kotlinx.coroutines.Dispatchers.Default) {
      job().run(DiscourseAutoLinkCronJobParams(dryRun = false))
    }
    started.await()

    assertEquals("already running", job().run(DiscourseAutoLinkCronJobParams(dryRun = false)))

    release.complete(Unit)
    assertEquals("first done", first.await())
  }
}
