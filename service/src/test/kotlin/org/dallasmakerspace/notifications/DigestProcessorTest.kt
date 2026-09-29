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
import org.dallasmakerspace.notifications.templates.SystemTestTemplate
import org.dallasmakerspace.notifications.templates.TemplateRegistry
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

class DigestProcessorTest {

  private lateinit var mockRepository: NotificationsRepository
  private lateinit var mockNotifier: Notifier
  private lateinit var mockConfigOverride: ConfigOverrideService
  private lateinit var processor: DigestProcessor

  private val utcZone = ZoneId.of("UTC")

  private fun notification(
      id: Long,
      memberUsername: String = "jdoe",
      channel: Channel = Channel.EMAIL,
      attempts: Int = 0,
      body: String = "<p>Test body $id</p>",
  ) =
      Notification(
          id = id,
          memberUsername = memberUsername,
          topic = SystemTopic,
          channel = channel,
          aggregation = Aggregation.DAILY,
          templateName = "system-test",
          payload = """{"message":"Item $id"}""",
          subject = "Test $id",
          body = body,
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
    mockConfigOverride = mock()
    val mockLoggerFactory = mock<LoggerFactory>()
    val mockLogger = mock<Logger>()
    whenever(mockLoggerFactory.create(any<Class<*>>())).thenReturn(mockLogger)
    whenever(mockNotifier.channel).thenReturn(Channel.EMAIL)
    whenever(mockNotifier.supportsDigest).thenReturn(true)
    runBlocking {
      whenever(mockConfigOverride.get(eq(ConfigRegistry.NOTIFICATIONS_ENABLED), eq(false)))
          .thenReturn(FeatureFlagValue(enabled = true))
      whenever(mockRepository.claim(any(), any())).thenReturn(true)
    }

    processor =
        DigestProcessor(
            mockRepository,
            NotifierRegistry(mapOf(Channel.EMAIL to mockNotifier)),
            TemplateRegistry(setOf(SystemTestTemplate())),
            mockConfigOverride,
            AppConfig(),
            mockLoggerFactory)
  }

  private suspend fun givenDue(vararg rows: Notification) {
    val byMember = rows.groupBy { it.memberUsername }
    whenever(mockRepository.getMembersWithDueDigests(any(), any(), anyOrNull()))
        .thenReturn(byMember.keys.toList())
    byMember.forEach { (member, memberRows) ->
      whenever(mockRepository.getDueDigestRows(eq(member), any())).thenReturn(memberRows)
    }
  }

  @Test
  fun `processDigests should do nothing when notifications disabled in site config`() =
      runBlocking {
        whenever(mockConfigOverride.get(eq(ConfigRegistry.NOTIFICATIONS_ENABLED), eq(false)))
            .thenReturn(FeatureFlagValue(enabled = false))

        val summary = processor.processDigests()

        assertTrue(summary.contains("paused=true"))
        verify(mockRepository, never()).getMembersWithDueDigests(any(), any(), anyOrNull())
        Unit
      }

  @Test
  fun `processDigests should query only allowlisted testers when globally disabled`() =
      runBlocking {
        whenever(mockConfigOverride.get(eq(ConfigRegistry.NOTIFICATIONS_ENABLED), eq(false)))
            .thenReturn(FeatureFlagValue(enabled = false, enabledUsernames = setOf("jdoe")))
        givenDue(notification(id = 1L))
        whenever(mockNotifier.send(any(), any())).thenReturn(SendResult.Success("digest-txn"))

        processor.processDigests()

        verify(mockRepository).getMembersWithDueDigests(any(), any(), eq(setOf("jdoe")))
        verify(mockRepository).markSent(eq(1L), eq("digest-txn"), any())
      }

  @Test
  fun `processDigests should send one digest per member and mark every row sent`() = runBlocking {
    givenDue(
        notification(id = 1L, memberUsername = "jdoe"),
        notification(id = 2L, memberUsername = "jdoe"),
        notification(id = 3L, memberUsername = "asmith"))
    whenever(mockNotifier.send(any(), any())).thenReturn(SendResult.Success("digest-txn"))

    val summary = processor.processDigests()

    assertTrue(summary.contains("sent=3"))
    verify(mockNotifier, times(1)).send(eq("jdoe"), any())
    verify(mockNotifier, times(1)).send(eq("asmith"), any())
    listOf(1L, 2L, 3L).forEach {
      verify(mockRepository).claim(eq(it), eq(listOf(NotificationStatus.IN_DIGEST)))
      verify(mockRepository).markSent(eq(it), eq("digest-txn"), any())
    }
  }

  @Test
  fun `processDigests should leave out rows another processor already claimed`() = runBlocking {
    givenDue(notification(id = 1L), notification(id = 2L))
    whenever(mockRepository.claim(eq(2L), any())).thenReturn(false)
    whenever(mockNotifier.send(any(), any())).thenReturn(SendResult.Success("digest-txn"))

    processor.processDigests()

    val capture = argumentCaptor<OutgoingMessage>()
    verify(mockNotifier).send(eq("jdoe"), capture.capture())
    assertTrue(capture.lastValue.html.contains("Test body 1"))
    assertTrue(!capture.lastValue.html.contains("Test body 2"))
    verify(mockRepository, never()).markSent(eq(2L), anyOrNull(), any())
    Unit
  }

  @Test
  fun `processDigests should not send when every row was claimed elsewhere`() = runBlocking {
    givenDue(notification(id = 1L))
    whenever(mockRepository.claim(any(), any())).thenReturn(false)

    processor.processDigests()

    verify(mockNotifier, never()).send(any(), any())
    Unit
  }

  @Test
  fun `processDigests should build the digest from each item's rendered text and html`() =
      runBlocking {
        givenDue(notification(id = 1L), notification(id = 2L))
        whenever(mockNotifier.send(any(), any())).thenReturn(SendResult.Success("digest-txn"))

        processor.processDigests()

        val capture = argumentCaptor<OutgoingMessage>()
        verify(mockNotifier).send(any(), capture.capture())
        val message = capture.lastValue
        assertEquals("Your Dallas Makerspace daily digest: 2 updates", message.subject)
        assertTrue(message.text.contains("Item 1") && message.text.contains("Item 2"))
        assertTrue(!message.text.contains("<p>"))
        assertTrue(message.html.contains("Test body 1") && message.html.contains("Test body 2"))
      }

  @Test
  fun `processDigests should dead-letter rows whose channel has no digest-capable notifier`() =
      runBlocking {
        givenDue(notification(id = 1L, channel = Channel.DISCORD))

        val summary = processor.processDigests()

        assertTrue(summary.contains("dead-letter=1"))
        verify(mockRepository).markDeadLetter(eq(1L), contains("does not support digest delivery"))
        verify(mockNotifier, never()).send(any(), any())
        Unit
      }

  @Test
  fun `processDigests should dead-letter on permanent failure`() = runBlocking {
    givenDue(notification(id = 1L), notification(id = 2L))
    whenever(mockNotifier.send(any(), any()))
        .thenReturn(SendResult.PermanentFailure("No email address on file for jdoe"))

    val summary = processor.processDigests()

    assertTrue(summary.contains("dead-letter=2"))
    verify(mockRepository).markDeadLetter(1L, "No email address on file for jdoe")
    verify(mockRepository).markDeadLetter(2L, "No email address on file for jdoe")
  }

  @Test
  fun `processDigests should retry transient failure as a digest, never individually`() =
      runBlocking {
        givenDue(notification(id = 1L), notification(id = 2L))
        whenever(mockNotifier.send(any(), any()))
            .thenReturn(SendResult.TransientFailure("Rate limited"))

        val summary = processor.processDigests()

        assertTrue(summary.contains("transient=2"))
        val capture = argumentCaptor<LocalDateTime>()
        verify(mockRepository, times(2))
            .markDigestRetry(any(), eq("Rate limited"), capture.capture())
        assertTrue(capture.allValues.all { it.isAfter(LocalDateTime.now(utcZone)) })
        verify(mockRepository, never()).markFailed(any(), any(), any())
        Unit
      }

  @Test
  fun `processDigests should dead-letter when retries exhausted`() = runBlocking {
    givenDue(notification(id = 1L, attempts = 4))
    whenever(mockNotifier.send(any(), any())).thenReturn(SendResult.TransientFailure("Rate limited"))

    val summary = processor.processDigests()

    assertTrue(summary.contains("dead-letter=1"))
    verify(mockRepository).markDeadLetter(eq(1L), contains("Retries exhausted"))
  }
}
