package org.dallasmakerspace.notifications

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.FeatureFlagValue
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.channels.Notifier
import org.dallasmakerspace.notifications.channels.NotifierRegistry
import org.dallasmakerspace.notifications.channels.OutgoingMessage
import org.dallasmakerspace.notifications.channels.SendResult
import org.dallasmakerspace.notifications.db.NotificationsRepository
import org.dallasmakerspace.notifications.templates.EventAnnouncementTemplate
import org.dallasmakerspace.notifications.templates.SystemTestTemplate
import org.dallasmakerspace.notifications.templates.TemplateRegistry
import org.dallasmakerspace.notifications.topics.FollowingTopic
import org.dallasmakerspace.notifications.topics.SystemTopic
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.contains
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class NotificationWorkerTest {

  private lateinit var mockRepository: NotificationsRepository
  private lateinit var mockNotifier: Notifier
  private lateinit var mockConfigOverride: ConfigOverrideService
  private lateinit var worker: NotificationWorker

  private val utcZone = ZoneId.of("UTC")

  private fun notification(
      id: Long,
      attempts: Int = 0,
      channel: Channel = Channel.EMAIL,
      topic: NotificationTopic = SystemTopic,
      subject: String = "Test $id",
  ) =
      Notification(
          id = id,
          memberUsername = "jdoe",
          topic = topic,
          channel = channel,
          aggregation = Aggregation.IMMEDIATE,
          templateName = if (topic == SystemTopic) "system-test" else "event-announcement",
          payload = """{"message":"Hello $id"}""",
          subject = subject,
          body = "<p>Hello $id</p>",
          status = NotificationStatus.QUEUED,
          attempts = attempts,
          nextAttemptAt = null,
          dedupeKey = null,
          providerId = null,
          lastError = null,
          sentAt = null,
          readAt = null,
          createdAt = LocalDateTime.now(utcZone))

  @Before
  fun setUp() {
    mockRepository = mock()
    mockNotifier = mock()
    mockConfigOverride = mock()
    val mockLoggerFactory = mock<LoggerFactory>()
    whenever(mockLoggerFactory.create(any<Class<*>>())).thenReturn(mock<Logger>())
    whenever(mockNotifier.channel).thenReturn(Channel.EMAIL)
    whenever(mockNotifier.supportsDigest).thenReturn(true)
    runBlocking {
      whenever(mockConfigOverride.get(eq(ConfigRegistry.NOTIFICATIONS_ENABLED), eq(false)))
          .thenReturn(FeatureFlagValue(enabled = true))
      givenMonthlyEmailCap(0)
      whenever(
              mockConfigOverride.get(
                  eq(ConfigRegistry.NOTIFICATIONS_PER_MEMBER_IMMEDIATE_DAILY_CAP), eq(false)))
          .thenReturn(15)
      whenever(mockRepository.countImmediateEmailsSince(any(), any())).thenReturn(0)
      whenever(mockRepository.claim(any(), any())).thenReturn(true)
      whenever(mockRepository.requeueStaleSending(any())).thenReturn(0)
    }

    worker =
        NotificationWorker(
            mockRepository,
            NotifierRegistry(mapOf(Channel.EMAIL to mockNotifier)),
            TemplateRegistry(setOf(SystemTestTemplate(), EventAnnouncementTemplate())),
            mockConfigOverride,
            MonthlySendCap(mockRepository, mockConfigOverride, mockLoggerFactory),
            AppConfig(),
            mockLoggerFactory)
  }

  private suspend fun givenMonthlyEmailCap(cap: Int, sentThisMonth: Int = 0) {
    whenever(mockConfigOverride.get(eq(ConfigRegistry.NOTIFICATIONS_MONTHLY_EMAIL_CAP), eq(false)))
        .thenReturn(cap)
    whenever(mockRepository.countMessagesSentSince(eq(Channel.EMAIL), any()))
        .thenReturn(sentThisMonth)
  }

  private suspend fun givenBurst(vararg rows: Notification) {
    whenever(mockRepository.getMembersWithDueImmediate(any(), any(), anyOrNull()))
        .thenReturn(if (rows.isEmpty()) emptyList() else listOf("jdoe"))
    whenever(mockRepository.getImmediateBurst(eq("jdoe"), any())).thenReturn(rows.toList())
  }

  private suspend fun sentMessage(): OutgoingMessage {
    val capture = argumentCaptor<OutgoingMessage>()
    verify(mockNotifier).send(eq("jdoe"), capture.capture())
    return capture.lastValue
  }

  @Test
  fun `processPending should do nothing when notifications disabled in site config`() =
      runBlocking {
        whenever(mockConfigOverride.get(eq(ConfigRegistry.NOTIFICATIONS_ENABLED), eq(false)))
            .thenReturn(FeatureFlagValue(enabled = false))

        val summary = worker.processPending()

        assertTrue(summary.contains("paused=true"))
        verify(mockRepository, never()).getMembersWithDueImmediate(any(), any(), anyOrNull())
        Unit
      }

  @Test
  fun `processPending should query only allowlisted testers when globally disabled`() =
      runBlocking {
        whenever(mockConfigOverride.get(eq(ConfigRegistry.NOTIFICATIONS_ENABLED), eq(false)))
            .thenReturn(FeatureFlagValue(enabled = false, enabledUsernames = setOf("jdoe")))
        givenBurst(notification(id = 1L))
        whenever(mockNotifier.send(any(), any())).thenReturn(SendResult.Success("txn-1"))

        worker.processPending()

        verify(mockRepository).getMembersWithDueImmediate(any(), any(), eq(setOf("jdoe")))
        verify(mockRepository).markSent(eq(1L), eq("txn-1"), any())
      }

  @Test
  fun `processPending should recover rows stuck in sending before each pass`() = runBlocking {
    givenBurst()

    worker.processPending()

    val capture = argumentCaptor<LocalDateTime>()
    verify(mockRepository).requeueStaleSending(capture.capture())
    assertTrue(capture.lastValue.isBefore(LocalDateTime.now(utcZone).minusMinutes(10)))
  }

  @Test
  fun `a single notification is sent exactly as rendered`() = runBlocking {
    givenBurst(notification(id = 1L))
    whenever(mockNotifier.send(any(), any())).thenReturn(SendResult.Success("txn-1"))

    val summary = worker.processPending()

    assertTrue(summary.contains("sent=1"))
    assertEquals(OutgoingMessage("Test 1", "Hello 1", "<p>Hello 1</p>"), sentMessage())
  }

  @Test
  fun `a burst is combined into one email and every row marked sent`() = runBlocking {
    givenBurst(
        notification(id = 1L, topic = FollowingTopic),
        notification(id = 2L, topic = FollowingTopic),
        notification(id = 3L, topic = FollowingTopic))
    whenever(mockNotifier.send(any(), any())).thenReturn(SendResult.Success("txn-1"))

    val summary = worker.processPending()

    assertTrue(summary.contains("sent=3"))
    val message = sentMessage()
    assertEquals("3 new updates from members you follow", message.subject)
    assertTrue(listOf(1, 2, 3).all { message.html.contains("<p>Hello $it</p>") })
    listOf(1L, 2L, 3L).forEach { verify(mockRepository).markSent(eq(it), eq("txn-1"), any()) }
  }

  @Test
  fun `rows another worker already claimed are left out of the email`() = runBlocking {
    givenBurst(notification(id = 1L), notification(id = 2L))
    whenever(mockRepository.claim(eq(2L), any())).thenReturn(false)
    whenever(mockNotifier.send(any(), any())).thenReturn(SendResult.Success("txn-1"))

    val summary = worker.processPending()

    assertTrue(summary.contains("claimed-elsewhere=1"))
    assertEquals("Test 1", sentMessage().subject)
    verify(mockRepository, never()).markSent(eq(2L), anyOrNull(), any())
    Unit
  }

  @Test
  fun `the monthly cap dead-letters the burst without calling the provider`() = runBlocking {
    givenMonthlyEmailCap(cap = 100, sentThisMonth = 100)
    givenBurst(notification(id = 1L), notification(id = 2L))

    val summary = worker.processPending()

    assertTrue(summary.contains("monthly-cap=2"))
    verify(mockRepository).markDeadLetter(1L, MonthlySendCap.reason(Channel.EMAIL))
    verify(mockRepository).markDeadLetter(2L, MonthlySendCap.reason(Channel.EMAIL))
    verify(mockNotifier, never()).send(any(), any())
    Unit
  }

  @Test
  fun `a failed send gives its monthly reservation back`() = runBlocking {
    givenMonthlyEmailCap(cap = 1)
    whenever(mockNotifier.send(any(), any()))
        .thenReturn(SendResult.TransientFailure("busy"))
        .thenReturn(SendResult.Success("txn-1"))

    givenBurst(notification(id = 1L))
    worker.processPending()
    givenBurst(notification(id = 2L))
    worker.processPending()

    verify(mockRepository).markSent(eq(2L), eq("txn-1"), any())
    verify(mockRepository, never()).markDeadLetter(any(), eq(MonthlySendCap.reason(Channel.EMAIL)))
    Unit
  }

  @Test
  fun `rows for a channel with no notifier are dead-lettered`() = runBlocking {
    givenBurst(notification(id = 1L, channel = Channel.DISCORD))

    worker.processPending()

    verify(mockRepository).markDeadLetter(eq(1L), contains("No notifier configured"))
    verify(mockNotifier, never()).send(any(), any())
    Unit
  }

  @Test
  fun `permanent failure dead-letters every row in the email`() = runBlocking {
    givenBurst(notification(id = 1L), notification(id = 2L))
    whenever(mockNotifier.send(any(), any())).thenReturn(SendResult.PermanentFailure("Bad recipient"))

    worker.processPending()

    verify(mockRepository).markDeadLetter(1L, "Bad recipient")
    verify(mockRepository).markDeadLetter(2L, "Bad recipient")
  }

  @Test
  fun `transient failure retries rows with backoff, dead-lettering exhausted ones`() = runBlocking {
    givenBurst(notification(id = 1L), notification(id = 2L, attempts = 4))
    whenever(mockNotifier.send(any(), any())).thenThrow(IllegalStateException("boom"))

    val summary = worker.processPending()

    assertTrue(summary.contains("transient=1"))
    assertTrue(summary.contains("dead-letter=1"))
    val capture = argumentCaptor<LocalDateTime>()
    verify(mockRepository).markFailed(eq(1L), contains("boom"), capture.capture())
    assertTrue(capture.lastValue.isAfter(LocalDateTime.now(utcZone)))
    verify(mockRepository).markDeadLetter(eq(2L), contains("Retries exhausted"))
  }

  @Test
  fun `the email that reaches the daily cap says so`() = runBlocking {
    givenBurst(notification(id = 1L))
    whenever(mockRepository.countImmediateEmailsSince(eq("jdoe"), any())).thenReturn(14)
    whenever(mockNotifier.send(any(), any())).thenReturn(SendResult.Success("txn-1"))

    worker.processPending()

    val message = sentMessage()
    assertTrue(message.text.contains("today's limit of 15"))
    assertTrue(message.html.contains("/profile/@jdoe/notifications"))
  }

  @Test
  fun `emails below the cap carry no note`() = runBlocking {
    givenBurst(notification(id = 1L))
    whenever(mockRepository.countImmediateEmailsSince(eq("jdoe"), any())).thenReturn(3)
    whenever(mockNotifier.send(any(), any())).thenReturn(SendResult.Success("txn-1"))

    worker.processPending()

    assertTrue(!sentMessage().text.contains("limit"))
  }

  @Test
  fun `after the cap the burst moves to the next daily digest`() = runBlocking {
    givenBurst(notification(id = 1L), notification(id = 2L))
    whenever(mockRepository.countImmediateEmailsSince(eq("jdoe"), any())).thenReturn(15)

    val summary = worker.processPending()

    assertTrue(summary.contains("deferred-to-digest=2"))
    val deliverAt = argumentCaptor<LocalDateTime>()
    verify(mockRepository, times(2))
        .markInDigest(
            any(),
            eq("${NotificationWorker.DAILY_CAP_REASON} (15)"),
            eq(Aggregation.DAILY),
            deliverAt.capture())
    assertTrue(deliverAt.allValues.all { it.isAfter(LocalDateTime.now(utcZone)) })
    verify(mockNotifier, never()).send(any(), any())
    Unit
  }

  @Test
  fun `a cap of zero disables the limit`() = runBlocking {
    whenever(
            mockConfigOverride.get(
                eq(ConfigRegistry.NOTIFICATIONS_PER_MEMBER_IMMEDIATE_DAILY_CAP), eq(false)))
        .thenReturn(0)
    givenBurst(notification(id = 1L))
    whenever(mockNotifier.send(any(), any())).thenReturn(SendResult.Success("txn-1"))

    worker.processPending()

    verify(mockRepository, never()).countImmediateEmailsSince(any(), any())
    verify(mockRepository).markSent(eq(1L), eq("txn-1"), any())
  }
}
