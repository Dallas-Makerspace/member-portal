package org.dallasmakerspace.notifications

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.FeatureFlagValue
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.channels.Notifier
import org.dallasmakerspace.notifications.channels.SendResult
import org.dallasmakerspace.notifications.db.NotificationsRepository
import org.dallasmakerspace.notifications.topics.SystemTopic
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.contains
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class NotificationWorkerTest {

  private lateinit var mockRepository: NotificationsRepository
  private lateinit var mockNotifier: Notifier
  private lateinit var mockActiveDirectory: IActiveDirectoryService
  private lateinit var mockConfigOverride: ConfigOverrideService
  private lateinit var worker: NotificationWorker
  private lateinit var appConfig: AppConfig

  private val utcZone = ZoneId.of("UTC")

  private fun notification(
      id: Long,
      status: NotificationStatus = NotificationStatus.QUEUED,
      attempts: Int = 0,
      aggregation: Aggregation = Aggregation.IMMEDIATE,
      channel: Channel = Channel.EMAIL,
  ) =
      Notification(
          id = id,
          memberUsername = "jdoe",
          topic = SystemTopic,
          channel = channel,
          aggregation = aggregation,
          templateName = "system-test",
          payload = null,
          subject = "Test",
          body = "Test body",
          status = status,
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
    mockActiveDirectory = mock()
    mockConfigOverride = mock()
    val mockLoggerFactory = mock<LoggerFactory>()
    val mockLogger = mock<Logger>()
    whenever(mockLoggerFactory.create(any<Class<*>>())).thenReturn(mockLogger)
    runBlocking {
      whenever(mockConfigOverride.get(eq(ConfigRegistry.NOTIFICATIONS_ENABLED), eq(false)))
          .thenReturn(FeatureFlagValue(enabled = true))
      whenever(
              mockConfigOverride.get(
                  eq(ConfigRegistry.NOTIFICATIONS_PER_MEMBER_IMMEDIATE_DAILY_CAP), eq(false)))
          .thenReturn(50)
      whenever(mockRepository.countImmediateSentSince(any(), any())).thenReturn(0L)
    }
    appConfig = AppConfig()

    worker =
        NotificationWorker(
            mockRepository,
            mockNotifier,
            mockActiveDirectory,
            mockConfigOverride,
            appConfig,
            mockLoggerFactory)
  }

  @Test
  fun `processPending should do nothing when notifications disabled in site config`() =
      runBlocking {
        whenever(mockConfigOverride.get(eq(ConfigRegistry.NOTIFICATIONS_ENABLED), eq(false)))
            .thenReturn(FeatureFlagValue(enabled = false))

        val summary = worker.processPending()

        assertTrue(summary.contains("paused=true"))
        verify(mockRepository, org.mockito.kotlin.never()).getPending(any(), any())
        Unit
      }

  @Test
  fun `processPending should send to allowlisted testers when globally disabled`() = runBlocking {
    whenever(mockConfigOverride.get(eq(ConfigRegistry.NOTIFICATIONS_ENABLED), eq(false)))
        .thenReturn(FeatureFlagValue(enabled = false, enabledUsernames = setOf("jdoe")))
    whenever(mockRepository.getPending(any(), any())).thenReturn(listOf(notification(id = 1L)))
    whenever(mockNotifier.send(any(), anyOrNull())).thenReturn(SendResult.Success("txn-1"))

    val summary = worker.processPending()

    assertTrue(summary.contains("sent=1"))
    assertTrue(summary.contains("skipped=0"))
    verify(mockRepository).markSent(eq(1L), eq("txn-1"), any())
  }

  @Test
  fun `processPending should skip non-allowlisted members when globally disabled`() = runBlocking {
    whenever(mockConfigOverride.get(eq(ConfigRegistry.NOTIFICATIONS_ENABLED), eq(false)))
        .thenReturn(FeatureFlagValue(enabled = false, enabledUsernames = setOf("someoneelse")))
    whenever(mockRepository.getPending(any(), any())).thenReturn(listOf(notification(id = 1L)))

    val summary = worker.processPending()

    assertTrue(summary.contains("processed=0"))
    assertTrue(summary.contains("skipped=1"))
    verify(mockRepository, org.mockito.kotlin.never()).markSending(any())
    verify(mockNotifier, org.mockito.kotlin.never()).send(any(), anyOrNull())
    Unit
  }

  @Test
  fun `processPending should mark notification sent on success`() = runBlocking {
    whenever(mockRepository.getPending(any(), any())).thenReturn(listOf(notification(id = 1L)))
    whenever(mockNotifier.send(any(), anyOrNull())).thenReturn(SendResult.Success("txn-123"))

    val summary = worker.processPending()

    assertTrue(summary.contains("sent=1"))
    verify(mockRepository).markSending(1L)
    verify(mockRepository).markSent(eq(1L), eq("txn-123"), any())
  }

  @Test
  fun `processPending should dead-letter on permanent failure`() = runBlocking {
    whenever(mockRepository.getPending(any(), any())).thenReturn(listOf(notification(id = 1L)))
    whenever(mockNotifier.send(any(), anyOrNull()))
        .thenReturn(SendResult.PermanentFailure("Bad recipient"))

    worker.processPending()

    verify(mockRepository).markDeadLetter(1L, "Bad recipient")
  }

  @Test
  fun `processPending should retry transient failure with backoff`() = runBlocking {
    whenever(mockRepository.getPending(any(), any())).thenReturn(listOf(notification(id = 1L)))
    whenever(mockNotifier.send(any(), anyOrNull()))
        .thenReturn(SendResult.TransientFailure("Rate limited"))

    worker.processPending()

    verify(mockRepository).markFailed(eq(1L), eq("Rate limited"), any())
    val capture = org.mockito.kotlin.argumentCaptor<LocalDateTime>()
    org.mockito.kotlin.verify(mockRepository).markFailed(any(), any(), capture.capture())
    assertTrue(capture.lastValue.isAfter(LocalDateTime.now(utcZone)))
  }

  @Test
  fun `processPending should dead-letter when retries exhausted`() = runBlocking {
    whenever(mockRepository.getPending(any(), any()))
        .thenReturn(listOf(notification(id = 1L, attempts = 4)))
    whenever(mockNotifier.send(any(), anyOrNull()))
        .thenReturn(SendResult.TransientFailure("Rate limited"))

    worker.processPending()

    verify(mockRepository).markDeadLetter(eq(1L), contains("Retries exhausted"))
  }

  @Test
  fun `processPending should defer to digest when member daily cap reached`() = runBlocking {
    whenever(mockRepository.getPending(any(), any())).thenReturn(listOf(notification(id = 1L)))
    whenever(mockRepository.countImmediateSentSince(eq("jdoe"), any())).thenReturn(50L)

    val summary = worker.processPending()

    assertTrue(summary.contains("deferred-to-digest=1"))
    verify(mockRepository)
        .markInDigest(1L, "Per-member immediate daily cap (50) reached; deferred to digest")
    verify(mockNotifier, org.mockito.kotlin.never()).send(any(), anyOrNull())
    Unit
  }

  @Test
  fun `processPending should not apply cap when configured to zero`() = runBlocking {
    whenever(
            mockConfigOverride.get(
                eq(ConfigRegistry.NOTIFICATIONS_PER_MEMBER_IMMEDIATE_DAILY_CAP), eq(false)))
        .thenReturn(0)
    whenever(mockRepository.getPending(any(), any())).thenReturn(listOf(notification(id = 1L)))
    whenever(mockNotifier.send(any(), anyOrNull())).thenReturn(SendResult.Success("txn-1"))

    worker.processPending()

    verify(mockRepository, org.mockito.kotlin.never()).markInDigest(any(), any())
    verify(mockRepository).markSent(eq(1L), eq("txn-1"), any())
  }

  @Test
  fun `processPending should resolve email from active directory for email channel`() =
      runBlocking {
        whenever(mockRepository.getPending(any(), any())).thenReturn(listOf(notification(id = 1L)))
        whenever(mockActiveDirectory.getMembersByUsernameList(listOf("jdoe")))
            .thenReturn(
                mapOf(
                    "jdoe" to
                        ADUser(
                            "jdoe",
                            "jdoe@example.com",
                            null,
                            null,
                            null,
                            null,
                            null,
                            "guid",
                            null,
                            true,
                            emptyList())))
        whenever(mockNotifier.send(any(), anyOrNull())).thenReturn(SendResult.Success("txn-1"))

        worker.processPending()

        val capture = org.mockito.kotlin.argumentCaptor<String>()
        org.mockito.kotlin.verify(mockNotifier).send(any(), capture.capture())
        assertEquals("jdoe@example.com", capture.lastValue)
      }
}
