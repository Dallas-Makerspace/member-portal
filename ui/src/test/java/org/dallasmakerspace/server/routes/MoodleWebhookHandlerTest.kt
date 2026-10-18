package org.dallasmakerspace.server.routes

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class MoodleWebhookHandlerTest {

  // Reference values computed independently with `openssl dgst -sha256 -hmac s3cret-key`; the
  // plugin's PHP hash_hmac('sha256', ...) must produce the same, so they pin both sides together.
  private val secret = "s3cret-key"
  private val signedBody = """{"event":"manual","courseid":0,"categoryid":0,"time":1,"note":"café"}"""
  private val bodyHmac = "debe50de98d94077c60fdbee13fb60621fbc6ca71d636df05f162580d36e121f"
  private val emptyBodyHmac = "03b5d47de6f8548b83940d24f99f8345cb156d953238ce3a1b7cb6ece090dcd5"

  @Test
  fun `signing matches the reference HMAC, including non-ASCII and empty bodies`() {
    assertEquals(bodyHmac, WebhookHmac.sign(secret, signedBody))
    assertEquals(emptyBodyHmac, WebhookHmac.sign(secret, ""))
    assertTrue(WebhookHmac.isValidSignature(secret, signedBody, "sha256=$bodyHmac"))
  }

  @Test
  fun `a signature for any other signedBody, secret, or shape is rejected`() {
    assertFalse(WebhookHmac.isValidSignature(secret, "$signedBody ", "sha256=$bodyHmac"))
    assertFalse(WebhookHmac.isValidSignature("other", signedBody, "sha256=$bodyHmac"))
    assertFalse(WebhookHmac.isValidSignature(secret, signedBody, bodyHmac))
    assertFalse(WebhookHmac.isValidSignature(secret, signedBody, null))
    assertFalse(WebhookHmac.isValidSignature(secret, signedBody, "sha256="))
  }

  @Test
  fun `a blank secret is never usable and the placeholder only while developing`() {
    assertFalse(MoodleWebhookHandler.isSecretUsable("", developmentMode = true))
    assertFalse(MoodleWebhookHandler.isSecretUsable("  ", developmentMode = true))
    assertFalse(MoodleWebhookHandler.isSecretUsable(MoodleWebhookHandler.PLACEHOLDER_SECRET, false))
    assertTrue(MoodleWebhookHandler.isSecretUsable(MoodleWebhookHandler.PLACEHOLDER_SECRET, true))
    assertTrue(MoodleWebhookHandler.isSecretUsable("a-real-secret", false))
  }

  @Test
  fun `the event name is read from the signedBody, defaulting to unknown`() {
    assertEquals("course_updated", MoodleWebhookHandler.eventOf("""{"event":"course_updated"}"""))
    assertEquals("unknown", MoodleWebhookHandler.eventOf("{}"))
    assertEquals("unknown", MoodleWebhookHandler.eventOf("""{"event":" "}"""))
    assertEquals("unknown", MoodleWebhookHandler.eventOf("not json"))
  }

  private fun handler(secretValue: String, development: Boolean = false): MoodleWebhookHandler {
    val config = mock<AppConfig>()
    whenever(config.requireStringProperty("app.moodle.webhook-secret")).thenReturn(secretValue)
    whenever(config.requireStringProperty("app.member-service.url")).thenReturn("http://127.0.0.1:1")
    whenever(config.isDevelopmentMode()).thenReturn(development)
    return MoodleWebhookHandler(LoggerFactory(), config)
  }

  private fun relay(handler: MoodleWebhookHandler, block: suspend (io.ktor.client.HttpClient) -> Unit) =
      testApplication {
        application { routing { post("/webhook/moodle") { handler.handle(call) } } }
        block(client)
      }

  @Test
  fun `without a configured secret every request is refused, even a validly signed one`() {
    relay(handler(MoodleWebhookHandler.PLACEHOLDER_SECRET)) { client ->
      val response =
          client.post("/webhook/moodle") {
            header(
                MoodleWebhookHandler.SIGNATURE_HEADER,
                "sha256=" + WebhookHmac.sign(MoodleWebhookHandler.PLACEHOLDER_SECRET, signedBody))
            setBody(signedBody)
          }
      assertEquals(HttpStatusCode.Forbidden, response.status)
    }
  }

  @Test
  fun `a missing or wrong signature is refused before anything is forwarded`() {
    relay(handler(secret)) { client ->
      assertEquals(HttpStatusCode.Forbidden, client.post("/webhook/moodle") { setBody(signedBody) }.status)
      assertEquals(
          HttpStatusCode.Forbidden,
          client
              .post("/webhook/moodle") {
                header(MoodleWebhookHandler.SIGNATURE_HEADER, "sha256=" + "0".repeat(64))
                setBody(signedBody)
              }
              .status)
    }
  }

  @Test
  fun `a valid signature is accepted and forwarding trouble is reported as a server error`() {
    // The service URL points nowhere, so reaching the forward step proves the signature passed.
    relay(handler(secret)) { client ->
      val response =
          client.post("/webhook/moodle") {
            header(MoodleWebhookHandler.SIGNATURE_HEADER, "sha256=$bodyHmac")
            setBody(signedBody)
          }
      assertEquals(HttpStatusCode.InternalServerError, response.status)
    }
  }
}
