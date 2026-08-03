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
import org.dallasmakerspace.notifications.NotificationTopic
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
          topic = NotificationTopic.EVENT_ANNOUNCEMENT,
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
                  topic == NotificationTopic.EVENT_ANNOUNCEMENT &&
                  templateName == "event-announcement" &&
                  channel == Channel.EMAIL &&
                  dedupeKey == "solder-night-2026-08-02"
            })
    Unit
  }

  @Test
  fun `handleWebhook should default channel to email`() = runBlocking {
    val notificationService = mock<NotificationService>()
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
    val handler = NotificationWebhook(mock())

    val result =
        handler.handleWebhook(
            """{"memberUsername":"jdoe","topic":"NOPE","templateName":"system-test"}""")

    assertFalse(result.success)
    assertTrue(result.message.contains("NOPE"))
  }

  @Test
  fun `handleWebhook should reject unknown channel`() = runBlocking {
    val handler = NotificationWebhook(mock())

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
