package org.dallasmakerspace.notifications

import java.time.LocalDateTime
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.db.NotificationsRepository
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class MonthlySendCapTest {

  private lateinit var repository: NotificationsRepository
  private lateinit var configOverride: ConfigOverrideService
  private lateinit var logger: Logger
  private lateinit var cap: MonthlySendCap

  // Mid-month, mid-day Central (UTC-5 in October).
  private val now = LocalDateTime.of(2026, 10, 15, 17, 0)

  @Before
  fun setUp() {
    repository = mock()
    configOverride = mock()
    logger = mock()
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(logger)
    givenCaps(email = 3, sms = 2)
    cap = MonthlySendCap(repository, configOverride, loggerFactory)
  }

  private fun givenCaps(email: Int, sms: Int) = runBlocking {
    whenever(configOverride.get(eq(ConfigRegistry.NOTIFICATIONS_MONTHLY_EMAIL_CAP), eq(false)))
        .thenReturn(email)
    whenever(configOverride.get(eq(ConfigRegistry.NOTIFICATIONS_MONTHLY_SMS_CAP), eq(false)))
        .thenReturn(sms)
  }

  private fun givenSent(channel: Channel, count: Int) = runBlocking {
    whenever(repository.countMessagesSentSince(eq(channel), any())).thenReturn(count)
  }

  @Test
  fun `allows sends up to the cap, counting in memory between reloads`() = runBlocking {
    givenSent(Channel.EMAIL, 1)

    assertTrue(cap.tryAcquire(Channel.EMAIL, now))
    assertTrue(cap.tryAcquire(Channel.EMAIL, now.plusMinutes(1)))
    assertFalse(cap.tryAcquire(Channel.EMAIL, now.plusMinutes(2)))

    verify(repository, times(1)).countMessagesSentSince(eq(Channel.EMAIL), any())
    Unit
  }

  @Test
  fun `release gives a reservation back`() = runBlocking {
    givenSent(Channel.EMAIL, 2)

    assertTrue(cap.tryAcquire(Channel.EMAIL, now))
    cap.release(Channel.EMAIL)

    assertTrue(cap.tryAcquire(Channel.EMAIL, now))
  }

  @Test
  fun `reloads from the database so other instances' sends count`() = runBlocking {
    givenSent(Channel.EMAIL, 0)
    assertTrue(cap.tryAcquire(Channel.EMAIL, now))

    givenSent(Channel.EMAIL, 3)

    assertFalse(cap.tryAcquire(Channel.EMAIL, now.plusMinutes(11)))
  }

  @Test
  fun `a new month starts from the new month's count`() = runBlocking {
    givenSent(Channel.EMAIL, 3)
    assertFalse(cap.tryAcquire(Channel.EMAIL, now))

    givenSent(Channel.EMAIL, 0)

    assertTrue(cap.tryAcquire(Channel.EMAIL, LocalDateTime.of(2026, 11, 1, 6, 0)))
  }

  @Test
  fun `channels have separate caps`() = runBlocking {
    givenSent(Channel.EMAIL, 3)
    givenSent(Channel.SMS, 1)

    assertFalse(cap.tryAcquire(Channel.EMAIL, now))
    assertTrue(cap.tryAcquire(Channel.SMS, now))
    assertFalse(cap.tryAcquire(Channel.SMS, now))
  }

  @Test
  fun `a zero cap or a channel without one is unlimited and never counts`() = runBlocking {
    givenCaps(email = 0, sms = 0)

    assertTrue(cap.tryAcquire(Channel.EMAIL, now))
    assertTrue(cap.tryAcquire(Channel.DISCORD, now))

    verify(repository, never()).countMessagesSentSince(any(), any())
    Unit
  }

  @Test
  fun `logs the cap being reached once per month`() = runBlocking {
    givenSent(Channel.EMAIL, 3)

    repeat(3) { cap.tryAcquire(Channel.EMAIL, now.plusMinutes(it * 20L)) }

    verify(logger, times(1)).error(any<String>())
    Unit
  }
}
