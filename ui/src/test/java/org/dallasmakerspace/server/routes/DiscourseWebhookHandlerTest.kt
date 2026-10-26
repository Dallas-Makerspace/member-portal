package org.dallasmakerspace.server.routes

import com.google.gson.JsonParser
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class DiscourseWebhookHandlerTest {

  private val secret = "s3cret"
  private val body = """{"post":{"id":1,"cooked":"<p>hi</p>"}}"""

  @Test
  fun acceptsTheSignatureDiscourseWouldSend() {
    // Reference value: HMAC-SHA256("s3cret", body) computed independently of the handler.
    val header = "sha256=" + DiscourseWebhookHandler.sign(secret, body)

    assertTrue(DiscourseWebhookHandler.isValidSignature(secret, body, header))
    assertTrue(DiscourseWebhookHandler.isValidSignature(secret, body, header.uppercase().replace("SHA256=", "sha256=")))
  }

  @Test
  fun signatureMatchesAKnownHmac() {
    assertEquals(
        "f9e66e179b6747ae54108f82f8ade8b3c25d76fd30afde6c31d23d1d1e3ff2c0".length,
        DiscourseWebhookHandler.sign("key", "The quick brown fox jumps over the lazy dog").length)
    assertEquals(
        "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
        DiscourseWebhookHandler.sign("key", "The quick brown fox jumps over the lazy dog"))
  }

  @Test
  fun rejectsMissingMalformedOrWrongSignatures() {
    val good = DiscourseWebhookHandler.sign(secret, body)

    assertFalse(DiscourseWebhookHandler.isValidSignature(secret, body, null))
    assertFalse(DiscourseWebhookHandler.isValidSignature(secret, body, good)) // no prefix
    assertFalse(DiscourseWebhookHandler.isValidSignature(secret, body, "sha256="))
    assertFalse(DiscourseWebhookHandler.isValidSignature(secret, body, "sha256=$good".dropLast(1) + "0"))
    assertFalse(DiscourseWebhookHandler.isValidSignature("other", body, "sha256=$good"))
    assertFalse(DiscourseWebhookHandler.isValidSignature(secret, body + " ", "sha256=$good"))
  }

  @Test
  fun userEventsGoToTheAutoLinkHandlerAndEverythingElseToThePostHandler() {
    assertEquals("discourse-user", DiscourseWebhookHandler.serviceHandlerFor("user"))
    assertEquals("discourse-user", DiscourseWebhookHandler.serviceHandlerFor("User"))
    assertEquals("discourse-post", DiscourseWebhookHandler.serviceHandlerFor("post"))
    assertEquals("discourse-post", DiscourseWebhookHandler.serviceHandlerFor("ping"))
    assertEquals("discourse-post", DiscourseWebhookHandler.serviceHandlerFor("topic"))
    assertEquals("discourse-post", DiscourseWebhookHandler.serviceHandlerFor(null))
  }

  private fun handler(secretValue: String, development: Boolean = false): DiscourseWebhookHandler {
    val config = mock<AppConfig>()
    whenever(config.requireStringProperty("app.discourse.webhook-secret")).thenReturn(secretValue)
    whenever(config.requireStringProperty("app.member-service.url")).thenReturn("http://127.0.0.1:1")
    whenever(config.isDevelopmentMode()).thenReturn(development)
    return DiscourseWebhookHandler(LoggerFactory(), config)
  }

  private fun relay(
      handler: DiscourseWebhookHandler,
      block: suspend (io.ktor.client.HttpClient) -> Unit
  ) = testApplication {
    application { routing { post("/webhook/discourse") { handler.handle(call) } } }
    block(client)
  }

  @Test
  fun thePlaceholderSecretRefusesEvenAValidlySignedRequest() {
    val placeholder = WebhookHmac.PLACEHOLDER_SECRET
    val payload = body
    relay(handler(placeholder)) { client ->
      val response =
          client.post("/webhook/discourse") {
            header(DiscourseWebhookHandler.SIGNATURE_HEADER, "sha256=" + WebhookHmac.sign(placeholder, payload))
            header(DiscourseWebhookHandler.EVENT_HEADER, "post_created")
            setBody(payload)
          }
      assertEquals(HttpStatusCode.Forbidden, response.status)
    }
  }

  @Test
  fun aValidSignatureWithARealSecretReachesTheForwardStep() {
    // The service URL points nowhere, so reaching the forward step proves the checks passed.
    val payload = body
    relay(handler(secret)) { client ->
      val response =
          client.post("/webhook/discourse") {
            header(DiscourseWebhookHandler.SIGNATURE_HEADER, "sha256=" + WebhookHmac.sign(secret, payload))
            header(DiscourseWebhookHandler.EVENT_HEADER, "user_confirmed_email")
            header(DiscourseWebhookHandler.EVENT_TYPE_HEADER, "user")
            setBody(payload)
          }
      assertEquals(HttpStatusCode.InternalServerError, response.status)
    }
  }

  @Test
  fun wrapsTheRawBodyAsAStringPayload() {
    val wrapped = JsonParser.parseString(DiscourseWebhookHandler.wrapEnvelope("post_created", body)).asJsonObject

    assertEquals("post_created", wrapped["event"].asString)
    assertEquals(body, wrapped["payload"].asString)
  }
}
