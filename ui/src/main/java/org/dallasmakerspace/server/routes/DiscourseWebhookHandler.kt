package org.dallasmakerspace.server.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.DmsHttpEngine
import org.dallasmakerspace.server.common.logging.LoggerFactory

/**
 * Public landing point for Discourse's post webhook (talk.dallasmakerspace.org). Discourse signs
 * each delivery: `X-Discourse-Event-Signature: sha256=<hex HMAC-SHA256 of the raw body>`, keyed
 * with the secret configured on the webhook. This verifies that signature, then proxies the event
 * to the backend service (only reachable inside the network) wrapped as
 * `{"event": <X-Discourse-Event>, "payload": <raw body>}` - same pattern as
 * [SparkPostWebhookHandler].
 */
class DiscourseWebhookHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    appConfig: AppConfig,
) : IRouteHandler {
  private val log = loggerFactory.create(javaClass)
  private val secret = appConfig.requireStringProperty("app.discourse.webhook-secret")
  private val serviceUrl = appConfig.requireStringProperty("app.member-service.url")
  private val client = HttpClient(DmsHttpEngine)

  @Suppress("TooGenericExceptionCaught")
  override suspend fun handle(call: ApplicationCall) {
    val body = call.receiveText()
    if (!isValidSignature(secret, body, call.request.header(SIGNATURE_HEADER))) {
      log.warn("Rejected Discourse webhook with missing or invalid signature")
      call.respond(HttpStatusCode.Forbidden)
      return
    }
    val event = call.request.header(EVENT_HEADER)
    if (event.isNullOrBlank()) {
      call.respond(HttpStatusCode.BadRequest)
      return
    }
    try {
      val response =
          client.post("$serviceUrl/webhook/discourse-post") {
            contentType(ContentType.Application.Json)
            setBody(wrapEnvelope(event, body))
          }
      call.respond(response.status)
    } catch (e: Exception) {
      log.error("Failed to forward Discourse webhook to service", e)
      call.respond(HttpStatusCode.InternalServerError)
    }
  }

  companion object {
    const val SIGNATURE_HEADER = "X-Discourse-Event-Signature"
    const val EVENT_HEADER = "X-Discourse-Event"
    private const val SIGNATURE_PREFIX = "sha256="

    /** Hex HMAC-SHA256 of [body] keyed with [secret]. */
    internal fun sign(secret: String, body: String): String {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
      return mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Constant-time check of a `sha256=<hex>` [header] against the body's HMAC. */
    internal fun isValidSignature(secret: String, body: String, header: String?): Boolean {
      if (header == null || !header.startsWith(SIGNATURE_PREFIX)) return false
      val expected = sign(secret, body).toByteArray(Charsets.UTF_8)
      val actual = header.removePrefix(SIGNATURE_PREFIX).lowercase().toByteArray(Charsets.UTF_8)
      return MessageDigest.isEqual(expected, actual)
    }

    /** `{"event": ..., "payload": <raw body as a JSON string>}`. */
    internal fun wrapEnvelope(event: String, rawBody: String): String =
        com.google.gson.JsonObject()
            .apply {
              addProperty("event", event)
              addProperty("payload", rawBody)
            }
            .toString()
  }
}
