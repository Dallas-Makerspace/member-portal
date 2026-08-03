package org.dallasmakerspace.webhook

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.webhook.handlers.SparkPostWebhook
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class SparkPostWebhookTest {

  @Test
  fun `handleWebhook should record delivery events`() = runBlocking {
    val notificationService = mock<NotificationService>()
    val handler = SparkPostWebhook(notificationService)

    val result =
        handler.handleWebhook(
            """
      [
        {
          "msys": {
            "message_event": {
              "type": "delivery",
              "transmission_id": "txn-1",
              "timestamp": "1454442600"
            }
          }
        }
      ]
      """
                .trimIndent())

    assertTrue(result.success)
    verify(notificationService).recordDelivery(eq("txn-1"), any())
    Unit
  }

  @Test
  fun `handleWebhook should record bounce events and suppress the recipient`() = runBlocking {
    val notificationService = mock<NotificationService>()
    val handler = SparkPostWebhook(notificationService)

    handler.handleWebhook(
        """
      [
        {
          "msys": {
            "message_event": {
              "type": "bounce",
              "transmission_id": "txn-2",
              "rcpt_to": "jdoe@example.com",
              "reason": "550 mailbox does not exist"
            }
          }
        }
      ]
      """
            .trimIndent())

    verify(notificationService)
        .recordBounce("txn-2", "jdoe@example.com", "550 mailbox does not exist")
    Unit
  }

  @Test
  fun `handleWebhook should record open and click as engagement`() = runBlocking {
    val notificationService = mock<NotificationService>()
    val handler = SparkPostWebhook(notificationService)

    handler.handleWebhook(
        """
      [
        { "msys": { "track_event": { "type": "open", "transmission_id": "txn-3" } } },
        { "msys": { "track_event": { "type": "click", "transmission_id": "txn-3" } } }
      ]
      """
            .trimIndent())

    verify(notificationService).recordEngagement("txn-3", "open")
    verify(notificationService).recordEngagement("txn-3", "click")
    Unit
  }

  @Test
  fun `handleWebhook should ignore unhandled event types`() = runBlocking {
    val notificationService = mock<NotificationService>()
    val handler = SparkPostWebhook(notificationService)

    val result =
        handler.handleWebhook(
            """[{"msys":{"message_event":{"type":"injection","transmission_id":"txn-4"}}}]""")

    assertTrue(result.success)
    verify(notificationService, never()).recordDelivery(any(), any())
    verify(notificationService, never()).recordBounce(any(), any(), any())
    verify(notificationService, never()).recordEngagement(any(), any())
    Unit
  }

  @Test
  fun `handleWebhook should skip events with no transmission id`() = runBlocking {
    val notificationService = mock<NotificationService>()
    val handler = SparkPostWebhook(notificationService)

    val result = handler.handleWebhook("""[{"msys":{"message_event":{"type":"delivery"}}}]""")

    assertTrue(result.success)
    assertTrue(result.message.contains("Processed 0 of 1"))
    Unit
  }

  @Test
  fun `handleWebhook should process a batch of multiple events`() = runBlocking {
    val notificationService = mock<NotificationService>()
    val handler = SparkPostWebhook(notificationService)

    val result =
        handler.handleWebhook(
            """
      [
        {"msys":{"message_event":{"type":"delivery","transmission_id":"a"}}},
        {"msys":{"message_event":{"type":"bounce","transmission_id":"b","reason":"bad"}}}
      ]
      """
                .trimIndent())

    assertTrue(result.success)
    assertTrue(result.message.contains("Processed 2 of 2"))
    Unit
  }

  @Test
  fun `handleWebhook should reject malformed json`() = runBlocking {
    val handler = SparkPostWebhook(mock())

    val result = handler.handleWebhook("not json")

    assertEquals(false, result.success)
  }

  @Test
  fun `getName should be sparkpost`() {
    assertEquals("sparkpost", SparkPostWebhook(mock()).getName())
  }
}
