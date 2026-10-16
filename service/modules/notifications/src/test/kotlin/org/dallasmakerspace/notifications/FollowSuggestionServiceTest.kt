package org.dallasmakerspace.notifications

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.calendar.CalendarService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.db.ProfileSubscriptionsRepository
import org.junit.Before
import org.junit.Test
import org.slf4j.Logger
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

private class MutableClock(var now: Instant) : Clock() {
  override fun getZone(): ZoneOffset = ZoneOffset.UTC

  override fun withZone(zone: java.time.ZoneId): Clock = fixed(now, zone)

  override fun instant(): Instant = now
}

class FollowSuggestionServiceTest {

  private lateinit var calendar: CalendarService
  private lateinit var repository: ProfileSubscriptionsRepository

  private lateinit var loggerFactory: LoggerFactory

  private fun serviceAt(instant: String) =
      FollowSuggestionService(
          calendar, repository, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), loggerFactory)

  @Before
  fun setUp() {
    calendar = mock()
    repository = mock()
    loggerFactory = mock()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(mock<Logger>())
    runBlocking {
      whenever(repository.getFollowing(any())).thenReturn(emptyList())
      whenever(calendar.getClassmates(any())).thenReturn(emptyList())
      whenever(calendar.getTopOrganizers(any())).thenReturn(emptyList())
    }
  }

  @Test
  fun `classmate follows come before organizers`() = runBlocking {
    whenever(calendar.getClassmates("jdoe")).thenReturn(listOf("cwu", "dlee"))
    whenever(repository.getMostFollowedBy(listOf("cwu", "dlee"), 30))
        .thenReturn(listOf("asmith", "bkim"))
    whenever(calendar.getTopOrganizers(30)).thenReturn(listOf("org1", "org2", "org3"))

    val result = serviceAt("2026-10-16T15:00:00Z").suggest("jdoe", 6)

    assertEquals(listOf("asmith", "bkim"), result.take(2))
    assertEquals(setOf("org1", "org2", "org3"), result.drop(2).toSet())
  }

  @Test
  fun `falls back to organizers when there are no classmates`() = runBlocking {
    whenever(calendar.getTopOrganizers(30)).thenReturn(listOf("org1", "org2"))

    val result = serviceAt("2026-10-16T15:00:00Z").suggest("jdoe", 6)

    assertEquals(setOf("org1", "org2"), result.toSet())
  }

  @Test
  fun `excludes self, already followed, and duplicates`() = runBlocking {
    whenever(repository.getFollowing("jdoe")).thenReturn(listOf("org1"))
    whenever(calendar.getClassmates("jdoe")).thenReturn(listOf("cwu"))
    whenever(repository.getMostFollowedBy(listOf("cwu"), 30)).thenReturn(listOf("jdoe", "org2"))
    whenever(calendar.getTopOrganizers(30)).thenReturn(listOf("org1", "org2", "jdoe", "org3"))

    val result = serviceAt("2026-10-16T15:00:00Z").suggest("jdoe", 6)

    assertEquals(listOf("org2", "org3"), result.sorted())
    assertEquals(result.distinct(), result)
  }

  @Test
  fun `respects the limit`() = runBlocking {
    whenever(calendar.getTopOrganizers(30)).thenReturn((1..20).map { "org$it" })

    assertEquals(4, serviceAt("2026-10-16T15:00:00Z").suggest("jdoe", 4).size)
  }

  @Test
  fun `a new follow drops out immediately but the pool is cached for the day`() = runBlocking {
    whenever(calendar.getTopOrganizers(30)).thenReturn(listOf("org1", "org2", "org3"))
    val service = serviceAt("2026-10-16T15:00:00Z")

    val first = service.suggest("jdoe", 6)
    whenever(repository.getFollowing("jdoe")).thenReturn(listOf(first.first()))
    val second = service.suggest("jdoe", 6)

    assertEquals(first.drop(1), second)
    verify(calendar, times(1)).getClassmates("jdoe")
    Unit
  }

  @Test
  fun `order is stable within a day and the pool is rebuilt the next day`() = runBlocking {
    whenever(calendar.getTopOrganizers(30)).thenReturn((1..30).map { "org$it" })

    val clock = MutableClock(Instant.parse("2026-10-16T15:00:00Z"))
    val service = FollowSuggestionService(calendar, repository, clock, loggerFactory)

    val first = service.suggest("jdoe")
    clock.now = Instant.parse("2026-10-16T20:00:00Z")
    assertEquals(first, service.suggest("jdoe"))
    verify(calendar, times(1)).getClassmates("jdoe")

    clock.now = Instant.parse("2026-10-17T15:00:00Z")
    service.suggest("jdoe")
    verify(calendar, times(2)).getClassmates("jdoe")
    Unit
  }

  @Test
  fun `explain reports the breakdown and the cache state`() = runBlocking {
    whenever(calendar.getClassmates("jdoe")).thenReturn(listOf("cwu", "dlee"))
    whenever(repository.getMostFollowedBy(listOf("cwu", "dlee"), 30)).thenReturn(listOf("asmith"))
    whenever(calendar.getTopOrganizers(30)).thenReturn(listOf("org1", "org2"))
    val service = serviceAt("2026-10-16T15:00:00Z")

    val first = service.explain("jdoe")
    val second = service.explain("jdoe")

    assertEquals(false, first.debug.cached)
    assertEquals(true, second.debug.cached)
    assertEquals(2, second.debug.classmateCount)
    assertEquals(listOf("asmith"), second.debug.tierOne)
    assertEquals(setOf("org1", "org2"), second.debug.tierTwo.toSet())
    assertEquals("2026-10-16", second.debug.date)
    assertEquals(first.usernames, service.suggest("jdoe"))
    Unit
  }

  @Test
  fun `empty results are not cached`() = runBlocking {
    val service = serviceAt("2026-10-16T15:00:00Z")

    assertTrue(service.suggest("jdoe").isEmpty())
    whenever(calendar.getTopOrganizers(30)).thenReturn(listOf("org1"))

    assertEquals(listOf("org1"), service.suggest("jdoe"))
  }
}
