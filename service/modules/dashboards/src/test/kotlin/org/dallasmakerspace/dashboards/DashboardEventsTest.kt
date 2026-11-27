package org.dallasmakerspace.dashboards

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DashboardEventsTest {
  @Test
  fun `a quiet stream still sends a heartbeat so the reader can tell it is alive`() = runBlocking {
    val blocks = withTimeout(TIMEOUT_MS) { DashboardEvents().stream(Duration.ofMillis(10)).take(2).toList() }

    assertEquals(listOf(": ping\n\n", ": ping\n\n"), blocks)
  }

  @Test
  fun `an event arrives as a data block between heartbeats`() = runBlocking {
    val events = DashboardEvents()
    val seen = async { withTimeout(TIMEOUT_MS) { events.stream(Duration.ofSeconds(30)).take(1).toList() } }
    withTimeout(TIMEOUT_MS) { events.subscriptionCount.first { it > 0 } }

    events.reload(13)

    val block = seen.await().single()
    assertTrue(block.startsWith("data: "), block)
    assertTrue(block.contains("\"reload\"") && block.contains("13"), block)
    assertTrue(block.endsWith("\n\n"), block)
  }

  private companion object {
    const val TIMEOUT_MS = 5_000L
  }
}
