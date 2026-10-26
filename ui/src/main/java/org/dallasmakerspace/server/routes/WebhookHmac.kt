package org.dallasmakerspace.server.routes

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC-SHA256 signing shared by the public webhook relays that verify a signature header. */
internal object WebhookHmac {
  private const val SIGNATURE_PREFIX = "sha256="
  const val PLACEHOLDER_SECRET = "change-me-in-production"

  /** A blank or placeholder secret is only acceptable while developing locally. */
  fun isSecretUsable(secret: String, developmentMode: Boolean): Boolean =
      secret.isNotBlank() && (secret != PLACEHOLDER_SECRET || developmentMode)

  /** Hex HMAC-SHA256 of [body] keyed with [secret]. */
  fun sign(secret: String, body: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  }

  /** Constant-time check of a `sha256=<hex>` [header] against the body's HMAC. */
  fun isValidSignature(secret: String, body: String, header: String?): Boolean {
    if (header == null || !header.startsWith(SIGNATURE_PREFIX)) return false
    val expected = sign(secret, body).toByteArray(Charsets.UTF_8)
    val actual = header.removePrefix(SIGNATURE_PREFIX).lowercase().toByteArray(Charsets.UTF_8)
    return MessageDigest.isEqual(expected, actual)
  }

  /** `{"event": ..., "payload": <raw body as a JSON string>}`. */
  fun wrapEnvelope(event: String, rawBody: String): String =
      com.google.gson.JsonObject()
          .apply {
            addProperty("event", event)
            addProperty("payload", rawBody)
          }
          .toString()
}
