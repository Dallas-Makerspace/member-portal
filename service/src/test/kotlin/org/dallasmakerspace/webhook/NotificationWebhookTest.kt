package org.dallasmakerspace.webhook

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.notifications.Aggregation
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.CreateNotificationRequest
import org.dallasmakerspace.notifications.Notification
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.NotificationStatus
import org.dallasmakerspace.notifications.topics.FollowingTopic
import org.dallasmakerspace.notifications.topics.SystemTopic
import org.dallasmakerspace.webhook.handlers.NotificationWebhook
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class NotificationWebhookTest {

  private val utcZone = ZoneId.of("UTC")

  private fun createdNotification() =
      Notification(
          id = 42,
          memberUsername = "jdoe",
          topic = FollowingTopic,
          channel = Channel.EMAIL,
          aggregation = Aggregation.IMMEDIATE,
          templateName = "event-announcement",
          payload = "{}",
          subject = "New event",
          body = "<p>New event</p>",
          status = NotificationStatus.QUEUED,
          attempts = 0,
          nextAttemptAt = null,
          dedupeKey = null,
          providerId = null,
          lastError = null,
          sentAt = null,
          readAt = null,
          createdAt = LocalDateTime.now(utcZone))

  @Test
  fun `handleWebhook should create notification on valid payload`() = runBlocking {
    val notificationService = mock<NotificationService>()
    whenever(notificationService.findTopic("EVENT_ANNOUNCEMENT")).thenReturn(FollowingTopic)
    whenever(notificationService.create(any())).thenReturn(createdNotification())
    val handler = NotificationWebhook(notificationService)

    val result =
        handler.handleWebhook(
            """
      {
        "memberUsername": "jdoe",
        "topic": "EVENT_ANNOUNCEMENT",
        "templateName": "event-announcement",
        "payload": "{\"title\":\"Solder night\"}",
        "channel": "EMAIL",
        "dedupeKey": "solder-night-2026-08-02"
      }
    """
                .trimIndent())

    assertTrue(result.success)
    assertTrue(result.message.contains("Notification 42 created"))
    verify(notificationService)
        .create(
            argThat<CreateNotificationRequest> {
              memberUsername == "jdoe" &&
                  topic == FollowingTopic &&
                  templateName == "event-announcement" &&
                  channel == Channel.EMAIL &&
                  dedupeKey == "solder-night-2026-08-02"
            })
    Unit
  }

  @Test
  fun `handleWebhook should default channel to email`() = runBlocking {
    val notificationService = mock<NotificationService>()
    whenever(notificationService.findTopic("SYSTEM")).thenReturn(SystemTopic)
    whenever(notificationService.create(any())).thenReturn(createdNotification())
    val handler = NotificationWebhook(notificationService)

    handler.handleWebhook(
        """{"memberUsername":"jdoe","topic":"SYSTEM","templateName":"system-test"}""")

    verify(notificationService)
        .create(argThat<CreateNotificationRequest> { channel == Channel.EMAIL })
    Unit
  }

  @Test
  fun `handleWebhook should reject unknown topic`() = runBlocking {
    // findTopic("NOPE") naturally returns null from an unstubbed mock, reproducing the same
    // "unknown topic" rejection the old NotificationTopic.valueOf(...) throw used to produce.
    val handler = NotificationWebhook(mock())

    val result =
        handler.handleWebhook(
            """{"memberUsername":"jdoe","topic":"NOPE","templateName":"system-test"}""")

    assertFalse(result.success)
    assertTrue(result.message.contains("NOPE"))
  }

  @Test
  fun `handleWebhook should reject unknown channel`() = runBlocking {
    // Topic is resolved before channel in CreateNotificationRequest's named-arg construction, so
    // the topic must resolve successfully for this test to actually reach the channel check.
    val notificationService = mock<NotificationService>()
    whenever(notificationService.findTopic("SYSTEM")).thenReturn(SystemTopic)
    val handler = NotificationWebhook(notificationService)

    val result =
        handler.handleWebhook(
            """{"memberUsername":"jdoe","topic":"SYSTEM","templateName":"system-test","channel":"FAX"}""")

    assertFalse(result.success)
    assertTrue(result.message.contains("FAX"))
  }

  @Test
  fun `handleWebhook should reject malformed json`() = runBlocking {
    val handler = NotificationWebhook(mock())

    val result = handler.handleWebhook("not json")

    assertFalse(result.success)
  }

  @Test
  fun `handleWebhook should surface service validation errors`() = runBlocking {
    val notificationService = mock<NotificationService>()
    whenever(notificationService.findTopic("SYSTEM")).thenReturn(SystemTopic)
    whenever(notificationService.create(any()))
        .thenThrow(IllegalArgumentException("Unknown notification template: nope"))
    val handler = NotificationWebhook(notificationService)

    val result =
        handler.handleWebhook(
            """{"memberUsername":"jdoe","topic":"SYSTEM","templateName":"nope"}""")

    assertFalse(result.success)
    assertTrue(result.message.contains("Unknown notification template: nope"))
  }

  @Test
  fun `getName should be notifications`() {
    assertEquals("notifications", NotificationWebhook(mock()).getName())
  }
}
