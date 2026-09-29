package org.dallasmakerspace.server.routes

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
  fun wrapsTheRawBodyAsAStringPayload() {
    val wrapped = JsonParser.parseString(DiscourseWebhookHandler.wrapEnvelope("post_created", body)).asJsonObject

    assertEquals("post_created", wrapped["event"].asString)
    assertEquals(body, wrapped["payload"].asString)
  }
}
