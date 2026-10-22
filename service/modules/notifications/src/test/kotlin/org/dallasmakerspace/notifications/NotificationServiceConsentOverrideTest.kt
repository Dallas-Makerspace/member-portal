package org.dallasmakerspace.notifications

import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.channels.MockNotifier
import org.dallasmakerspace.notifications.channels.NotifierRegistry
import org.dallasmakerspace.notifications.db.NotificationsRepository
import org.dallasmakerspace.notifications.templates.SystemTestTemplate
import org.dallasmakerspace.notifications.templates.TemplateRegistry
import org.dallasmakerspace.notifications.topics.SystemTopic
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

/**
 * consentOverride (design §5): a system-initiated notice (a watch the member armed, an
 * expiry notice) is delivered even when the member's per-topic preference is disabled — the
 * member asked for *this specific event*. The override lifts ONLY the preference check:
 * suppression and inactive-member rules still apply, and member aggregation still applies.
 */
class NotificationServiceConsentOverrideTest {

  private lateinit var mockLoggerFactory: LoggerFactory
  private lateinit var mockRepository: NotificationsRepository
  private lateinit var mockActiveDirectoryService: IActiveDirectoryService
  private lateinit var service: NotificationService
  private val storedRows = mutableListOf<Notification>()

  @Before
  fun setUp() {
    mockLoggerFactory = mock()
    mockRepository = mock()
    mockActiveDirectoryService = mock()
    storedRows.clear()
    whenever(mockLoggerFactory.create(any<Class<*>>())).thenReturn(mock<Logger>())
    runBlocking {
      whenever(mockRepository.getSuppression(any())).thenReturn(null)
      whenever(mockRepository.getByDedupeKey(any())).thenReturn(null)
      whenever(mockRepository.create(any())).thenAnswer {
        val row = it.getArgument<Notification>(0)
        storedRows += row
        row
      }
    }
    service =
        NotificationService(
            mockRepository,
            TemplateRegistry(setOf(SystemTestTemplate())),
            NotifierRegistry(mapOf(Channel.EMAIL to MockNotifier(mockLoggerFactory))),
            mockActiveDirectoryService,
            AppConfig(),
            mockLoggerFactory)
  }

  private fun adUser(enabled: Boolean, username: String = "jdoe") =
      ADUser(
          sAMAccountName = username,
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

  private fun stubMember(enabled: Boolean) {
    runBlocking {
      whenever(mockActiveDirectoryService.getMembersByUsernameList(listOf("jdoe")))
          .thenReturn(mapOf("jdoe" to adUser(enabled)))
    }
  }

  private fun stubPreference(enabled: Boolean, aggregation: Aggregation? = null) {
    runBlocking {
      whenever(mockRepository.getPreference("jdoe", SystemTopic, Channel.EMAIL))
          .thenReturn(
              NotificationPreference(
                  memberUsername = "jdoe",
                  topic = SystemTopic,
                  channel = Channel.EMAIL,
                  enabled = enabled,
                  aggregation = aggregation))
    }
  }

  private fun stubPreferenceFor(
      username: String,
      enabled: Boolean,
      aggregation: Aggregation? = null,
  ) {
    runBlocking {
      whenever(mockRepository.getPreference(username, SystemTopic, Channel.EMAIL))
          .thenReturn(
              NotificationPreference(
                  memberUsername = username,
                  topic = SystemTopic,
                  channel = Channel.EMAIL,
                  enabled = enabled,
                  aggregation = aggregation))
    }
  }

  private fun request(consentOverride: Boolean) =
      CreateNotificationRequest(
          memberUsername = "jdoe",
          topic = SystemTopic,
          templateName = "system-test",
          payload = """{"message":"Test Message"}""",
          dedupeKey = "dedupe-1",
          consentOverride = consentOverride)

  @Test
  fun `disabled preference skips without the override (control)`() = runBlocking {
    stubMember(enabled = true)
    stubPreference(enabled = false)

    val result = service.create(request(consentOverride = false))

    assertEquals(NotificationStatus.SKIPPED, result.status)
  }

  @Test
  fun `override delivers despite a disabled preference`() = runBlocking {
    stubMember(enabled = true)
    stubPreference(enabled = false)

    val result = service.create(request(consentOverride = true))

    assertEquals(NotificationStatus.QUEUED, result.status)
    assertEquals(Aggregation.IMMEDIATE, result.aggregation)
  }

  @Test
  fun `override still honours the member's aggregation preference`() = runBlocking {
    stubMember(enabled = true)
    stubPreference(enabled = false, aggregation = Aggregation.DAILY)

    val result = service.create(request(consentOverride = true))

    assertEquals(Aggregation.DAILY, result.aggregation)
    assertEquals(NotificationStatus.IN_DIGEST, result.status)
  }

  @Test
  fun `override does not lift a suppression`() = runBlocking {
    stubMember(enabled = true)
    stubPreference(enabled = false)
    whenever(mockRepository.getSuppression("jdoe"))
        .thenReturn(
            NotificationSuppression(
                id = 1L,
                memberUsername = "jdoe",
                email = "jdoe@example.com",
                reason = "User unsubscribed",
                createdAt = java.time.LocalDateTime.now()))

    val result = service.create(request(consentOverride = true))

    assertEquals(NotificationStatus.SUPPRESSED, result.status)
  }

  @Test
  fun `override does not deliver to an inactive member`() = runBlocking {
    stubMember(enabled = false)
    stubPreference(enabled = false)

    val result = service.create(request(consentOverride = true))

    assertEquals(NotificationStatus.SKIPPED, result.status)
  }

  @Test
  fun `createForMembers threads the override to every recipient`() = runBlocking {
    stubMember(enabled = true)
    stubPreference(enabled = false)

    val withOverride =
        service.createForMembers(
            memberUsernames = listOf("jdoe"),
            topic = SystemTopic,
            templateName = "system-test",
            payload = """{"message":"hi"}""",
            consentOverride = true)
    assertEquals(FanOutResult(created = 1, duplicates = 0, failed = 0), withOverride)
    // The fan-out counts stored rows as created either way; the override's effect is the
    // stored row's status (deliverable QUEUED vs preference-SKIPPED).
    assertEquals(NotificationStatus.QUEUED, storedRows.single().status)

    storedRows.clear()
    val withoutOverride =
        service.createForMembers(
            memberUsernames = listOf("jdoe"),
            topic = SystemTopic,
            templateName = "system-test",
            payload = """{"message":"hi"}""")
    assertEquals(FanOutResult(created = 1, duplicates = 0, failed = 0), withoutOverride)
    assertEquals(NotificationStatus.SKIPPED, storedRows.single().status)
  }

  @Test
  fun `createForMembers applies the override per recipient - disabled gets queued, digest stays digest`() =
      runBlocking {
        // QA F9: two recipients, one fan-out. jdoe explicitly disabled the topic (the
        // override defeats only that drop → QUEUED); asmith is enabled but on a daily
        // digest (aggregation is never overridden → IN_DIGEST).
        runBlocking {
          whenever(
                  mockActiveDirectoryService.getMembersByUsernameList(listOf("jdoe", "asmith")))
              .thenReturn(
                  mapOf("jdoe" to adUser(true, "jdoe"), "asmith" to adUser(true, "asmith")))
        }
        stubPreferenceFor("jdoe", enabled = false)
        stubPreferenceFor("asmith", enabled = true, aggregation = Aggregation.DAILY)

        val result =
            service.createForMembers(
                memberUsernames = listOf("jdoe", "asmith"),
                topic = SystemTopic,
                templateName = "system-test",
                payload = """{"message":"hi"}""",
                consentOverride = true)

        assertEquals(FanOutResult(created = 2, duplicates = 0, failed = 0), result)
        val byMember = storedRows.associateBy { it.memberUsername }
        assertEquals(NotificationStatus.QUEUED, byMember["jdoe"]?.status)
        assertEquals(NotificationStatus.IN_DIGEST, byMember["asmith"]?.status)
      }
}
