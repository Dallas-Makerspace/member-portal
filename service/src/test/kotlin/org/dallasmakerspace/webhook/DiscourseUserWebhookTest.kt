package org.dallasmakerspace.webhook

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.dallasmakerspace.discourse.DiscourseAutoLinkService
import org.dallasmakerspace.webhook.handlers.DiscourseUserWebhook
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DiscourseUserWebhookTest {
  private val service = mock<DiscourseAutoLinkService>()
  private val handler = DiscourseUserWebhook(service)

  private fun body(event: String, userJson: String) =
      buildJsonObject {
            put("event", event)
            put("payload", """{"user": $userJson}""")
          }
          .toString()

  private fun handle(event: String, userJson: String) = runBlocking {
    handler.handleWebhook(body(event, userJson))
  }

  @Test
  fun `handler is registered as discourse-user`() {
    assertEquals("discourse-user", handler.getName())
  }

  @Test
  fun `events other than created and confirmed email are ignored`() {
    val result = handle("user_updated", """{"id": 5, "username": "talk-jdoe"}""")

    assertTrue(result.success)
    runBlocking { verify(service, never()).linkFromTalkEvent(any(), any(), any()) }
  }

  @Test
  fun `a malformed envelope or payload fails without echoing the content`() {
    val badEnvelope = runBlocking { handler.handleWebhook("not json with jdoe@example.invalid") }
    val badUser =
        runBlocking {
          handler.handleWebhook(
              buildJsonObject {
                    put("event", "user_confirmed_email")
                    put("payload", """{"user": {"username": "jdoe@example.invalid"}}""")
                  }
                  .toString())
        }

    assertFalse(badEnvelope.success)
    assertFalse(badUser.success)
    assertFalse(badEnvelope.message.contains("example.invalid"))
    assertFalse(badUser.message.contains("example.invalid"))
  }

  @Test
  fun `a staged user or a missing email never reaches the service`() {
    val staged =
        handle(
            "user_created",
            """{"id": 5, "username": "talk-jdoe", "email": "jdoe@example.invalid", "staged": true}""")
    val noEmail = handle("user_confirmed_email", """{"id": 5, "username": "talk-jdoe"}""")

    assertTrue(staged.success)
    assertTrue(noEmail.success)
    runBlocking { verify(service, never()).linkFromTalkEvent(any(), any(), any()) }
  }

  @Test
  fun `a confirmed email is passed to the service and its outcome reported without the email`() {
    runBlocking {
      whenever(service.linkFromTalkEvent(5, "talk-jdoe", "jdoe@example.invalid")).thenReturn("LINKED")
    }

    val result =
        handle(
            "user_confirmed_email",
            """{"id": 5, "username": "talk-jdoe", "email": "jdoe@example.invalid"}""")

    assertTrue(result.success)
    assertTrue(result.message.contains("LINKED"))
    assertFalse(result.message.contains("example.invalid"))
  }

  @Test
  fun `a downstream failure is deferred, not failed, and leaks no email`() {
    runBlocking {
      whenever(service.linkFromTalkEvent(5, "talk-jdoe", "jdoe@example.invalid"))
          .doThrow(IllegalStateException("lookup for jdoe@example.invalid failed"))
    }

    val result =
        handle(
            "user_confirmed_email",
            """{"id": 5, "username": "talk-jdoe", "email": "jdoe@example.invalid"}""")

    assertTrue(result.success)
    assertTrue(result.message.contains("deferred"))
    assertFalse(result.message.contains("example.invalid"))
  }
}
