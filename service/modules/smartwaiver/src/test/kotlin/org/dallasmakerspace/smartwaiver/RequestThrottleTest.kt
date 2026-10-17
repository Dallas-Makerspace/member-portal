package org.dallasmakerspace.smartwaiver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class RequestThrottleTest {
  private var now = 1_000L
  private val sleeps = mutableListOf<Long>()

  private fun throttle(search: Long = 700, other: Long = 150) =
      RequestThrottle(
          mapOf(RequestCategory.SEARCH to search, RequestCategory.OTHER to other),
          nowMs = { now },
          sleep = {
            sleeps += it
            now += it
          },
      )

  @Test
  fun `first request goes straight through`() = runBlocking {
    throttle().acquire(RequestCategory.SEARCH)
    assertEquals(emptyList(), sleeps)
  }

  @Test
  fun `back to back requests in a category are spaced by its interval`() = runBlocking {
    val t = throttle()
    t.acquire(RequestCategory.SEARCH)
    t.acquire(RequestCategory.SEARCH)
    t.acquire(RequestCategory.SEARCH)
    assertEquals(listOf(700L, 700L), sleeps)
  }

  @Test
  fun `categories are throttled independently`() = runBlocking {
    val t = throttle()
    t.acquire(RequestCategory.SEARCH)
    t.acquire(RequestCategory.OTHER)
    assertEquals(emptyList(), sleeps)
  }

  @Test
  fun `a pause delays the next request in any category`() = runBlocking {
    val t = throttle()
    t.pauseFor(5_000)
    t.acquire(RequestCategory.OTHER)
    assertEquals(listOf(5_000L), sleeps)
  }

  @Test
  fun `time passing satisfies the interval`() = runBlocking {
    val t = throttle()
    t.acquire(RequestCategory.SEARCH)
    now += 700
    t.acquire(RequestCategory.SEARCH)
    assertEquals(emptyList(), sleeps)
  }

  @Test
  fun `retry after prefers the header then the body then a default`() {
    val body = """{"type":"rate_limit","rate_limit":{"requests":107,"max":100,"retryAfter":11}}"""
    assertEquals(3L, parseRetryAfterSeconds("3", body))
    assertEquals(11L, parseRetryAfterSeconds(null, body))
    assertEquals(10L, parseRetryAfterSeconds(null, "not json"))
    assertEquals(10L, parseRetryAfterSeconds("soon", "{}"))
  }

  @Test
  fun `retry after is clamped`() {
    assertEquals(1L, parseRetryAfterSeconds("0", ""))
    assertEquals(120L, parseRetryAfterSeconds("99999", ""))
  }
}
