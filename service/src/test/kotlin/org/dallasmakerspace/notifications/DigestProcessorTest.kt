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

class DigestProcessorTest {

  private lateinit var mockRepository: NotificationsRepository
  private lateinit var mockNotifier: Notifier
  private lateinit var mockActiveDirectory: IActiveDirectoryService
  private lateinit var mockConfigOverride: ConfigOverrideService
  private lateinit var processor: DigestProcessor

  private val utcZone = ZoneId.of("UTC")

  private fun notification(
      id: Long,
      memberUsername: String = "jdoe",
      channel: Channel = Channel.EMAIL,
      attempts: Int = 0,
  ) =
      Notification(
          id = id,
          memberUsername = memberUsername,
          topic = NotificationTopic.SYSTEM,
          channel = channel,
          aggregation = Aggregation.DAILY,
          templateName = "system-test",
          payload = null,
          subject = "Test $id",
          body = "<p>Test body $id</p>",
          status = NotificationStatus.IN_DIGEST,
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
    }

    processor =
        DigestProcessor(
            mockRepository,
            mockNotifier,
            mockActiveDirectory,
            mockConfigOverride,
            AppConfig(),
            mockLoggerFactory)
  }

  @Test
  fun `processDigests should do nothing when notifications disabled in site config`() =
      runBlocking {
        whenever(mockConfigOverride.get(eq(ConfigRegistry.NOTIFICATIONS_ENABLED), eq(false)))
            .thenReturn(FeatureFlagValue(enabled = false))

        val summary = processor.processDigests()

        assertTrue(summary.contains("paused=true"))
        verify(mockRepository, org.mockito.kotlin.never()).getInDigest(any(), anyOrNull())
        Unit
      }

  @Test
  fun `processDigests should send to allowlisted testers when globally disabled`() = runBlocking {
    whenever(mockConfigOverride.get(eq(ConfigRegistry.NOTIFICATIONS_ENABLED), eq(false)))
        .thenReturn(FeatureFlagValue(enabled = false, enabledUsernames = setOf("jdoe")))
    whenever(mockRepository.getInDigest(any(), anyOrNull()))
        .thenReturn(
            listOf(
                notification(id = 1L, memberUsername = "jdoe"),
                notification(id = 2L, memberUsername = "asmith")))
    whenever(mockActiveDirectory.getMembersByUsernameList(listOf("jdoe")))
        .thenReturn(mapOf("jdoe" to adUser("jdoe", "jdoe@example.com")))
    whenever(mockNotifier.sendDigest(any(), anyOrNull(), any(), any(), any()))
        .thenReturn(SendResult.Success("digest-txn"))

    val summary = processor.processDigests()

    assertTrue(summary.contains("sent=1"))
    assertTrue(summary.contains("skipped=1"))
    verify(mockNotifier).sendDigest(eq("jdoe"), eq("jdoe@example.com"), any(), any(), any())
    verify(mockNotifier, org.mockito.kotlin.never())
        .sendDigest(eq("asmith"), anyOrNull(), any(), any(), any())
    verify(mockRepository).markSent(eq(1L), eq("digest-txn"), any())
    verify(mockRepository, org.mockito.kotlin.never()).markSending(2L)
    Unit
  }

  @Test
  fun `processDigests should skip all when disabled and no members allowlisted`() = runBlocking {
    whenever(mockConfigOverride.get(eq(ConfigRegistry.NOTIFICATIONS_ENABLED), eq(false)))
        .thenReturn(FeatureFlagValue(enabled = false, enabledUsernames = setOf("someoneelse")))
    whenever(mockRepository.getInDigest(any(), anyOrNull()))
        .thenReturn(listOf(notification(id = 1L, memberUsername = "jdoe")))

    val summary = processor.processDigests()

    assertTrue(summary.contains("processed=0"))
    assertTrue(summary.contains("skipped=1"))
    verify(mockRepository, org.mockito.kotlin.never()).markSending(any())
    verify(mockNotifier, org.mockito.kotlin.never())
        .sendDigest(any(), anyOrNull(), any(), any(), any())
    Unit
  }

  @Test
  fun `processDigests should send one digest email per member and mark rows sent`() = runBlocking {
    whenever(mockRepository.getInDigest(any(), anyOrNull()))
        .thenReturn(
            listOf(
                notification(id = 1L, memberUsername = "jdoe"),
                notification(id = 2L, memberUsername = "jdoe"),
                notification(id = 3L, memberUsername = "asmith")))
    whenever(mockActiveDirectory.getMembersByUsernameList(listOf("jdoe")))
        .thenReturn(mapOf("jdoe" to adUser("jdoe", "jdoe@example.com")))
    whenever(mockActiveDirectory.getMembersByUsernameList(listOf("asmith")))
        .thenReturn(mapOf("asmith" to adUser("asmith", "asmith@example.com")))
    whenever(mockNotifier.sendDigest(any(), anyOrNull(), any(), any(), any()))
        .thenReturn(SendResult.Success("digest-txn"))

    val summary = processor.processDigests()

    assertTrue(summary.contains("sent=3"))
    verify(mockNotifier).sendDigest(eq("jdoe"), eq("jdoe@example.com"), any(), any(), any())
    verify(mockNotifier).sendDigest(eq("asmith"), eq("asmith@example.com"), any(), any(), any())
    verify(mockRepository).markSending(1L)
    verify(mockRepository).markSending(2L)
    verify(mockRepository).markSending(3L)
    verify(mockRepository).markSent(eq(1L), eq("digest-txn"), any())
    verify(mockRepository).markSent(eq(2L), eq("digest-txn"), any())
    verify(mockRepository).markSent(eq(3L), eq("digest-txn"), any())
  }

  @Test
  fun `processDigests should build digest subject from item count`() = runBlocking {
    whenever(mockRepository.getInDigest(any(), anyOrNull()))
        .thenReturn(listOf(notification(id = 1L), notification(id = 2L)))
    whenever(mockNotifier.sendDigest(any(), anyOrNull(), any(), any(), any()))
        .thenReturn(SendResult.Success("digest-txn"))

    processor.processDigests()

    val capture = org.mockito.kotlin.argumentCaptor<String>()
    org.mockito.kotlin
        .verify(mockNotifier)
        .sendDigest(any(), anyOrNull(), capture.capture(), any(), any())
    assertEquals("Dallas Makerspace digest: 2 updates", capture.lastValue)
  }

  @Test
  fun `processDigests should dead-letter non-email rows in digest`() = runBlocking {
    whenever(mockRepository.getInDigest(any(), anyOrNull()))
        .thenReturn(listOf(notification(id = 1L, channel = Channel.DISCORD)))
    whenever(mockNotifier.sendDigest(any(), anyOrNull(), any(), any(), any()))
        .thenReturn(SendResult.Success("digest-txn"))

    val summary = processor.processDigests()

    assertTrue(summary.contains("dead-letter=1"))
    verify(mockRepository).markDeadLetter(eq(1L), contains("does not support digest delivery"))
    verify(mockNotifier, org.mockito.kotlin.never())
        .sendDigest(any(), anyOrNull(), any(), any(), any())
    Unit
  }

  @Test
  fun `processDigests should dead-letter on permanent failure`() = runBlocking {
    whenever(mockRepository.getInDigest(any(), anyOrNull()))
        .thenReturn(listOf(notification(id = 1L), notification(id = 2L)))
    whenever(mockNotifier.sendDigest(any(), anyOrNull(), any(), any(), any()))
        .thenReturn(SendResult.PermanentFailure("No email address on file for jdoe"))

    val summary = processor.processDigests()

    assertTrue(summary.contains("dead-letter=2"))
    verify(mockRepository).markDeadLetter(1L, "No email address on file for jdoe")
    verify(mockRepository).markDeadLetter(2L, "No email address on file for jdoe")
  }

  @Test
  fun `processDigests should retry transient failure with backoff`() = runBlocking {
    whenever(mockRepository.getInDigest(any(), anyOrNull()))
        .thenReturn(listOf(notification(id = 1L), notification(id = 2L)))
    whenever(mockNotifier.sendDigest(any(), anyOrNull(), any(), any(), any()))
        .thenReturn(SendResult.TransientFailure("Rate limited"))

    val summary = processor.processDigests()

    assertTrue(summary.contains("transient=2"))
    val capture = org.mockito.kotlin.argumentCaptor<LocalDateTime>()
    org.mockito.kotlin
        .verify(mockRepository, org.mockito.kotlin.times(2))
        .markFailed(any(), eq("Rate limited"), capture.capture())
    assertTrue(capture.allValues.all { it.isAfter(LocalDateTime.now(utcZone)) })
  }

  @Test
  fun `processDigests should dead-letter when retries exhausted`() = runBlocking {
    whenever(mockRepository.getInDigest(any(), anyOrNull()))
        .thenReturn(listOf(notification(id = 1L, attempts = 4)))
    whenever(mockNotifier.sendDigest(any(), anyOrNull(), any(), any(), any()))
        .thenReturn(SendResult.TransientFailure("Rate limited"))

    val summary = processor.processDigests()

    assertTrue(summary.contains("dead-letter=1"))
    verify(mockRepository).markDeadLetter(eq(1L), contains("Retries exhausted"))
  }

  private fun adUser(username: String, email: String) =
      ADUser(username, email, null, null, null, null, null, "guid", null, true, emptyList())
}
