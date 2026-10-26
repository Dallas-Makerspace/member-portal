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
import javax.inject.Inject
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.DmsHttpEngine
import org.dallasmakerspace.server.common.logging.LoggerFactory

/**
 * Public landing point for Discourse's post and user webhooks (talk.dallasmakerspace.org).
 * Discourse signs each delivery: `X-Discourse-Event-Signature: sha256=<hex HMAC-SHA256 of the raw
 * body>`, keyed with the secret configured on the webhook. This verifies that signature, then
 * proxies the event to the backend service (only reachable inside the network) wrapped as
 * `{"event": <X-Discourse-Event>, "payload": <raw body>}` - same pattern as
 * [SparkPostWebhookHandler]. While the secret is still the placeholder default the route refuses
 * everything (except in development).
 */
class DiscourseWebhookHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    private val appConfig: AppConfig,
) : IRouteHandler {
  private val log = loggerFactory.create(javaClass)
  private val secret = appConfig.requireStringProperty("app.discourse.webhook-secret")
  private val serviceUrl = appConfig.requireStringProperty("app.member-service.url")
  private val client = HttpClient(DmsHttpEngine)

  @Suppress("TooGenericExceptionCaught")
  override suspend fun handle(call: ApplicationCall) {
    if (!WebhookHmac.isSecretUsable(secret, appConfig.isDevelopmentMode())) {
      log.warn("Rejected Discourse webhook: ENV_DISCOURSE_WEBHOOK_SECRET is not set")
      call.respond(HttpStatusCode.Forbidden)
      return
    }
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
    val handlerName = serviceHandlerFor(call.request.header(EVENT_TYPE_HEADER))
    try {
      val response =
          client.post("$serviceUrl/webhook/$handlerName") {
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
    const val EVENT_TYPE_HEADER = "X-Discourse-Event-Type"

    /** User events go to the auto-link handler; everything else keeps the post handler. */
    internal fun serviceHandlerFor(eventType: String?): String =
        if (eventType.equals("user", ignoreCase = true)) "discourse-user" else "discourse-post"

    internal fun sign(secret: String, body: String): String = WebhookHmac.sign(secret, body)

    internal fun isValidSignature(secret: String, body: String, header: String?): Boolean =
        WebhookHmac.isValidSignature(secret, body, header)

    internal fun wrapEnvelope(event: String, rawBody: String): String =
        WebhookHmac.wrapEnvelope(event, rawBody)
  }
}
