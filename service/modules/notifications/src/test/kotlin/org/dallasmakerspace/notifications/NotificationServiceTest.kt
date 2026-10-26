package org.dallasmakerspace.notifications

import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.channels.MockNotifier
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
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class NotificationServiceTest {

  private lateinit var mockLoggerFactory: LoggerFactory
  private lateinit var mockLogger: Logger
  private lateinit var mockRepository: NotificationsRepository
  private lateinit var mockActiveDirectoryService: IActiveDirectoryService
  private lateinit var service: NotificationService
  private lateinit var templateRegistry: TemplateRegistry

  @Before
  fun setUp() {
    mockLoggerFactory = mock()
    mockLogger = mock()
    mockRepository = mock()
    mockActiveDirectoryService = mock()

    whenever(mockLoggerFactory.create(any<Class<*>>())).thenReturn(mockLogger)
    whenever(mockActiveDirectoryService.getMembersByUsernameList(listOf("jdoe")))
        .thenReturn(mapOf("jdoe" to adUser(enabled = true)))
    runBlocking {
      whenever(mockRepository.getSuppression(any())).thenReturn(null)
      whenever(mockRepository.create(any())).thenAnswer { it.getArgument(0) as Notification }
    }

    templateRegistry = TemplateRegistry(setOf(EventAnnouncementTemplate(), SystemTestTemplate()))
    service = newService(mapOf(Channel.EMAIL to MockNotifier(mockLoggerFactory)))
  }

  private fun newService(notifiers: Map<Channel, Notifier>) =
      NotificationService(
          mockRepository,
          templateRegistry,
          NotifierRegistry(notifiers),
          mockActiveDirectoryService,
          AppConfig(),
          mockLoggerFactory)

  /** A channel notifier that can't batch digests. */
  private class NoDigestNotifier(override val channel: Channel) : Notifier {
    override suspend fun send(memberUsername: String, message: OutgoingMessage) =
        SendResult.Success(null)
  }

  private fun adUser(enabled: Boolean) =
      ADUser(
          sAMAccountName = "jdoe",
          mail = "jdoe@example.com",
          givenName = "Jane",
          sn = "Doe",
          telephoneNumber = null,
          employeeID = null,
          displayName = "Jane Doe",
          objectGuid = "guid",
          whenCreated = null,
          enabled = enabled,
          groups = emptyList())

  private fun createRequest(templateName: String = "system-test") =
      CreateNotificationRequest(
          memberUsername = "jdoe",
          topic = SystemTopic,
          templateName = templateName,
          payload = """{"message":"Test Message"}""",
          dedupeKey = "dedupe-1")

  @Test
  fun `create should queue notification for immediate delivery`() = runBlocking {
    whenever(mockRepository.getByDedupeKey(any())).thenReturn(null)
    whenever(mockRepository.getPreference("jdoe", SystemTopic, Channel.EMAIL)).thenReturn(null)

    val result = service.create(createRequest())

    assertEquals("jdoe", result.memberUsername)
    assertEquals(SystemTopic, result.topic)
    assertEquals(Channel.EMAIL, result.channel)
    assertEquals(Aggregation.IMMEDIATE, result.aggregation)
    assertEquals(NotificationStatus.QUEUED, result.status)
    assertEquals(result.createdAt.plusMinutes(5), result.nextAttemptAt, "held for the burst window")
    assertEquals("dedupe-1", result.dedupeKey)
    assertTrue(result.subject.isNotBlank())
    assertTrue(result.body!!.isNotBlank())
    assertTrue(result.body!!.contains("Test Message"))
    verify(mockRepository).create(result)
    Unit
  }

  @Test
  fun `create should return existing notification on duplicate dedupe key`() = runBlocking {
    val existing =
        Notification(
            id = 1L,
            memberUsername = "jdoe",
            topic = SystemTopic,
            channel = Channel.EMAIL,
            aggregation = Aggregation.IMMEDIATE,
            templateName = "system-test",
            dedupeKey = "dedupe-1",
            subject = "Existing",
            body = "Existing body",
            status = NotificationStatus.SENT,
            attempts = 0,
            nextAttemptAt = null,
            payload = null,
            providerId = null,
            lastError = null,
            sentAt = null,
            readAt = null,
            createdAt = java.time.LocalDateTime.now())
    whenever(mockRepository.getByDedupeKey("dedupe-1")).thenReturn(existing)

    val result = service.create(createRequest())

    assertEquals(existing, result)
    verify(mockRepository, never()).create(any())
    Unit
  }

  @Test
  fun `create should mark notification suppressed when suppression exists`() = runBlocking {
    whenever(mockRepository.getByDedupeKey(any())).thenReturn(null)
    whenever(mockRepository.getSuppression("jdoe"))
        .thenReturn(
            NotificationSuppression(
                id = 1L,
                memberUsername = "jdoe",
                email = "jdoe@example.com",
                reason = "User unsubscribed",
                createdAt = java.time.LocalDateTime.now()))

    val result = service.create(createRequest())

    assertEquals(NotificationStatus.SUPPRESSED, result.status)
    assertTrue(result.lastError!!.contains("User unsubscribed"))
  }

  @Test
  fun `create should mark notification skipped when channel disabled in preferences`() =
      runBlocking {
        whenever(mockRepository.getByDedupeKey(any())).thenReturn(null)
        whenever(mockRepository.getPreference("jdoe", SystemTopic, Channel.EMAIL))
            .thenReturn(
                NotificationPreference(
                    memberUsername = "jdoe",
                    topic = SystemTopic,
                    channel = Channel.EMAIL,
                    enabled = false,
                    aggregation = null))

        val result = service.create(createRequest())

        assertEquals(NotificationStatus.SKIPPED, result.status)
        assertTrue(result.lastError!!.contains("preferences"))
      }

  @Test
  fun `create should use daily aggregation from preference override`() = runBlocking {
    whenever(mockRepository.getByDedupeKey(any())).thenReturn(null)
    whenever(mockRepository.getPreference("jdoe", SystemTopic, Channel.EMAIL))
        .thenReturn(
            NotificationPreference(
                memberUsername = "jdoe",
                topic = SystemTopic,
                channel = Channel.EMAIL,
                enabled = true,
                aggregation = Aggregation.DAILY))

    val result = service.create(createRequest())

    assertEquals(Aggregation.DAILY, result.aggregation)
    assertEquals(NotificationStatus.IN_DIGEST, result.status)
    val dueAt = assertNotNull(result.nextAttemptAt)
    assertTrue(dueAt.isAfter(result.createdAt), "digest rows wait for their delivery window")
  }

  @Test
  fun `create should deliver immediately when the channel cannot batch digests`() = runBlocking {
    service = newService(mapOf(Channel.EMAIL to NoDigestNotifier(Channel.EMAIL)))
    whenever(mockRepository.getByDedupeKey(any())).thenReturn(null)
    whenever(mockRepository.getPreference("jdoe", SystemTopic, Channel.EMAIL))
        .thenReturn(
            NotificationPreference("jdoe", SystemTopic, Channel.EMAIL, true, Aggregation.WEEKLY))

    val result = service.create(createRequest())

    assertEquals(Aggregation.IMMEDIATE, result.aggregation)
    assertEquals(NotificationStatus.QUEUED, result.status)
  }

  @Test
  fun `create should skip sms until the member opts in for the topic`() = runBlocking {
    service = newService(mapOf(Channel.SMS to NoDigestNotifier(Channel.SMS)))
    whenever(mockRepository.getByDedupeKey(any())).thenReturn(null)
    whenever(mockRepository.getPreference("jdoe", SystemTopic, Channel.SMS)).thenReturn(null)
    whenever(mockRepository.getPreferences("jdoe")).thenReturn(emptyList())

    val result = service.create(createRequest().copy(channel = Channel.SMS))

    assertEquals(NotificationStatus.SKIPPED, result.status)
    assertEquals(false, service.getPreferences("jdoe").single { it.topic == SystemTopic }.enabled)
  }

  @Test
  fun `create should queue sms once the member opts in`() = runBlocking {
    service = newService(mapOf(Channel.SMS to NoDigestNotifier(Channel.SMS)))
    whenever(mockRepository.getByDedupeKey(any())).thenReturn(null)
    whenever(mockRepository.getPreference("jdoe", SystemTopic, Channel.SMS))
        .thenReturn(NotificationPreference("jdoe", SystemTopic, Channel.SMS, true, null))

    val result = service.create(createRequest().copy(channel = Channel.SMS))

    assertEquals(NotificationStatus.QUEUED, result.status)
    assertEquals(Aggregation.IMMEDIATE, result.aggregation, "sms can't batch, so its daily default falls back")
  }

  @Test
  fun `create should reject a channel with no notifier`() = runBlocking {
    assertFailsWith<IllegalArgumentException> {
      service.create(createRequest().copy(channel = Channel.DISCORD))
    }
    verify(mockRepository, never()).create(any())
    Unit
  }

  @Test
  fun `createForMembers should check membership once and skip existing dedupe keys`() =
      runBlocking {
        whenever(mockActiveDirectoryService.getMembersByUsernameList(listOf("jdoe", "asmith")))
            .thenReturn(mapOf("jdoe" to adUser(enabled = true), "asmith" to adUser(enabled = false)))
        whenever(mockRepository.getByDedupeKey("k-jdoe")).thenReturn(null)
        whenever(mockRepository.getByDedupeKey("k-asmith"))
            .thenReturn(existingNotification(providerId = null))

        val result =
            service.createForMembers(
                memberUsernames = listOf("jdoe", "asmith", "jdoe"),
                topic = SystemTopic,
                templateName = "system-test",
                payload = """{"message":"hi"}""",
                dedupeKey = { "k-$it" })

        assertEquals(FanOutResult(created = 1, duplicates = 1, failed = 0), result)
        verify(mockActiveDirectoryService, times(1)).getMembersByUsernameList(any())
        verify(mockRepository, times(1)).create(any())
        Unit
      }

  @Test
  fun `createForMembers should keep going when one recipient fails`() = runBlocking {
    whenever(mockActiveDirectoryService.getMembersByUsernameList(listOf("jdoe", "asmith")))
        .thenReturn(mapOf("jdoe" to adUser(enabled = true), "asmith" to adUser(enabled = true)))
    whenever(mockRepository.getSuppression("jdoe")).thenThrow(IllegalStateException("db down"))

    val result =
        service.createForMembers(
            memberUsernames = listOf("jdoe", "asmith"),
            topic = SystemTopic,
            templateName = "system-test",
            payload = null)

    assertEquals(FanOutResult(created = 1, duplicates = 0, failed = 1), result)
  }

  @Test
  fun `create should not store a read state for unread notification`() = runBlocking {
    whenever(mockRepository.getByDedupeKey(any())).thenReturn(null)
    whenever(mockRepository.getPreference("jdoe", SystemTopic, Channel.EMAIL)).thenReturn(null)

    val result = service.create(createRequest())

    assertNull(result.readAt)
    assertNull(result.sentAt)
  }

  @Test
  fun `create should mark notification skipped when member is inactive`() = runBlocking {
    whenever(mockRepository.getByDedupeKey(any())).thenReturn(null)
    whenever(mockRepository.getPreference("jdoe", SystemTopic, Channel.EMAIL)).thenReturn(null)
    whenever(mockActiveDirectoryService.getMembersByUsernameList(listOf("jdoe")))
        .thenReturn(mapOf("jdoe" to adUser(enabled = false)))

    val result = service.create(createRequest())

    assertEquals(NotificationStatus.SKIPPED, result.status)
    assertTrue(result.lastError!!.contains("inactive"))
  }

  @Test
  fun `create should mark notification skipped when member is unknown to AD`() = runBlocking {
    whenever(mockRepository.getByDedupeKey(any())).thenReturn(null)
    whenever(mockRepository.getPreference("jdoe", SystemTopic, Channel.EMAIL)).thenReturn(null)
    whenever(mockActiveDirectoryService.getMembersByUsernameList(listOf("jdoe")))
        .thenReturn(mapOf("jdoe" to null))

    val result = service.create(createRequest())

    assertEquals(NotificationStatus.SKIPPED, result.status)
  }

  @Test
  fun `create should throw for unknown template`() = runBlocking {
    whenever(mockRepository.getByDedupeKey(any())).thenReturn(null)

    assertFailsWith<IllegalArgumentException> {
      service.create(createRequest(templateName = "does-not-exist"))
    }
    Unit
  }

  @Test
  fun `create should throw when topic does not match template`() = runBlocking {
    whenever(mockRepository.getByDedupeKey(any())).thenReturn(null)

    assertFailsWith<IllegalArgumentException> {
      service.create(createRequest().copy(templateName = "event-announcement", topic = SystemTopic))
    }
    Unit
  }

  @Test
  fun `markRead should delegate to repository`() = runBlocking {
    whenever(mockRepository.markRead(eq(42L), eq("jdoe"), any())).thenReturn(true)

    val result = service.markRead(42L, "jdoe")

    assertEquals(true, result)
    verify(mockRepository).markRead(eq(42L), eq("jdoe"), any())
    Unit
  }

  @Test
  fun `getPreferences should return defaults when no stored preferences`() = runBlocking {
    whenever(mockRepository.getPreferences("jdoe")).thenReturn(emptyList())

    val views = service.getPreferences("jdoe")

    // Only channels with a notifier (EMAIL here) are offered.
    assertEquals(templateRegistry.topics.size, views.size)
    assertTrue(views.all { it.channel == Channel.EMAIL && it.digestSupported })
    assertTrue(views.all { it.enabled })
    views.forEach { view ->
      assertEquals(view.channel.defaultAggregation, view.effectiveAggregation)
      assertNull(view.aggregationOverride)
    }
    Unit
  }

  @Test
  fun `getPreferences should merge stored overrides with defaults`() = runBlocking {
    whenever(mockRepository.getPreferences("jdoe"))
        .thenReturn(
            listOf(
                NotificationPreference(
                    memberUsername = "jdoe",
                    topic = FollowingTopic,
                    channel = Channel.EMAIL,
                    enabled = false,
                    aggregation = Aggregation.DAILY)))

    val views = service.getPreferences("jdoe")

    val overridden =
        views.single { it.topic == FollowingTopic && it.channel == Channel.EMAIL }
    assertEquals(false, overridden.enabled)
    assertEquals(Aggregation.DAILY, overridden.aggregationOverride)
    assertEquals(Aggregation.DAILY, overridden.effectiveAggregation)
    assertTrue(
        views
            .filter { !(it.topic == FollowingTopic && it.channel == Channel.EMAIL) }
            .all { it.enabled })
    Unit
  }

  @Test
  fun `setPreference should persist and return the effective view`() = runBlocking {
    whenever(mockRepository.setPreference(any())).thenAnswer {
      it.getArgument(0) as NotificationPreference
    }

    val view =
        service.setPreference(
            "jdoe",
            SystemTopic,
            Channel.EMAIL,
            enabled = false,
            aggregationOverride = Aggregation.DAILY)

    assertEquals(false, view.enabled)
    assertEquals(Aggregation.DAILY, view.effectiveAggregation)
    verify(mockRepository)
        .setPreference(
            NotificationPreference(
                memberUsername = "jdoe",
                topic = SystemTopic,
                channel = Channel.EMAIL,
                enabled = false,
                aggregation = Aggregation.DAILY))
    Unit
  }

  @Test
  fun `list should delegate filters to repository`() = runBlocking {
    whenever(mockRepository.list(eq(25), eq("jdoe"), eq(NotificationStatus.QUEUED)))
        .thenReturn(emptyList())

    val result = service.list(25, "jdoe", NotificationStatus.QUEUED)

    assertEquals(emptyList<Notification>(), result)
    verify(mockRepository).list(eq(25), eq("jdoe"), eq(NotificationStatus.QUEUED))
    Unit
  }

  @Test
  fun `recordDelivery should mark the matching notification sent`() = runBlocking {
    val sentAt = LocalDateTime.of(2026, 8, 2, 12, 0)
    whenever(mockRepository.getAllByProviderId("txn-1"))
        .thenReturn(
            listOf(existingNotification("txn-1"), existingNotification("txn-1").copy(id = 100L)))

    service.recordDelivery("txn-1", sentAt)

    verify(mockRepository).markSent(99L, "txn-1", sentAt)
    verify(mockRepository).markSent(100L, "txn-1", sentAt)
    Unit
  }

  @Test
  fun `recordDelivery should ignore unknown provider id`() = runBlocking {
    whenever(mockRepository.getAllByProviderId("unknown")).thenReturn(emptyList())

    service.recordDelivery("unknown", LocalDateTime.now())

    verify(mockRepository, never()).markSent(any(), any(), any())
    Unit
  }

  @Test
  fun `recordBounce should dead-letter the notification and suppress the member`() = runBlocking {
    whenever(mockRepository.getAllByProviderId("txn-2"))
        .thenReturn(
            listOf(
                existingNotification(providerId = "txn-2"),
                existingNotification(providerId = "txn-2").copy(id = 100L)))

    service.recordBounce("txn-2", "jdoe@example.com", "Mailbox does not exist")

    verify(mockRepository).markDeadLetter(99L, "Email bounced: Mailbox does not exist")
    verify(mockRepository).markDeadLetter(100L, "Email bounced: Mailbox does not exist")
    verify(mockRepository, times(1)).insertSuppression("jdoe", "jdoe@example.com", "BOUNCE")
    Unit
  }

  @Test
  fun `recordBounce should ignore unknown provider id`() = runBlocking {
    whenever(mockRepository.getAllByProviderId("unknown")).thenReturn(emptyList())

    service.recordBounce("unknown", "jdoe@example.com", "reason")

    verify(mockRepository, never()).markDeadLetter(any(), any())
    verify(mockRepository, never()).insertSuppression(any(), any(), any())
    Unit
  }

  @Test
  fun `recordEngagement should not change notification status`() = runBlocking {
    whenever(mockRepository.getAllByProviderId("txn-3"))
        .thenReturn(listOf(existingNotification(providerId = "txn-3")))

    service.recordEngagement("txn-3", "click")

    verify(mockRepository, never()).markSent(any(), any(), any())
    verify(mockRepository, never()).markDeadLetter(any(), any())
    Unit
  }

  private fun existingNotification(providerId: String?) =
      Notification(
          id = 99L,
          memberUsername = "jdoe",
          topic = SystemTopic,
          channel = Channel.EMAIL,
          aggregation = Aggregation.IMMEDIATE,
          templateName = "system-test",
          payload = null,
          subject = "Existing",
          body = "Existing body",
          status = NotificationStatus.SENT,
          attempts = 1,
          nextAttemptAt = null,
          dedupeKey = null,
          providerId = providerId,
          lastError = null,
          sentAt = LocalDateTime.now(),
          readAt = null,
          createdAt = LocalDateTime.now())
}
