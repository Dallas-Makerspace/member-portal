package org.dallasmakerspace.moodle

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Test
import org.mockito.kotlin.mock
import org.slf4j.Logger

class RefreshingSnapshotTest {

  private class FakeClock(var current: Instant = Instant.fromEpochSeconds(1_000_000)) : Clock {
    override fun now(): Instant = current
  }

  private val clock = FakeClock()

  private fun snapshot(loader: suspend () -> Int) =
      RefreshingSnapshot(
          name = "test",
          ttl = 1.hours,
          scope = CoroutineScope(Dispatchers.Unconfined),
          log = mock<Logger>(),
          clock = clock,
          loader = loader,
      )

  @Test
  fun `loads inline on first use and serves from cache within the ttl`() = runBlocking<Unit> {
    var calls = 0
    val s = snapshot { ++calls }

    assertEquals(1, s.get())
    assertEquals(1, s.get())
    assertEquals(1, calls)
  }

  @Test
  fun `keeps the last good value when a refresh fails`() = runBlocking<Unit> {
    var fail = false
    val s = snapshot { if (fail) error("down") else 42 }
    assertEquals(42, s.get())

    fail = true
    clock.current = clock.current.plus(2.hours)
    s.refresh()

    assertEquals(42, s.get())
  }

  @Test
  fun `refreshOrThrow loads fresh, replaces the cache and returns the value`() = runBlocking<Unit> {
    var calls = 0
    val s = snapshot { ++calls }
    assertEquals(1, s.get())

    assertEquals(2, s.refreshOrThrow())

    assertEquals(2, s.get())
    assertEquals(2, calls)
  }

  @Test
  fun `refreshOrThrow throws on failure and keeps the last good value`() = runBlocking<Unit> {
    var fail = false
    val s = snapshot { if (fail) error("down") else 42 }
    assertEquals(42, s.get())

    fail = true
    val failure = runCatching { s.refreshOrThrow() }.exceptionOrNull()

    assertEquals("down", failure?.message)
    assertEquals(42, s.get())
  }

  @Test
  fun `after an initial failure, waits before trying again`() = runBlocking<Unit> {
    var calls = 0
    var fail = true
    val s = snapshot {
      calls++
      if (fail) error("down") else 7
    }

    assertNull(s.get())
    assertNull(s.get())
    assertEquals(1, calls)

    fail = false
    clock.current = clock.current.plus(2.minutes)
    assertEquals(7, s.get())
  }
}
