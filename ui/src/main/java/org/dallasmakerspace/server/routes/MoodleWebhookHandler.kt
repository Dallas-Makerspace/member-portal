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
 * Public landing point for the DMS Learn (Moodle) `local_addad` plugin, which tells the portal
 * that a course may have been published. The plugin signs each delivery:
 * `X-Addad-Signature: sha256=<hex HMAC-SHA256 of the raw body>`, keyed with the shared secret set
 * in the plugin's settings. This verifies that signature, then proxies the event to the backend
 * service (only reachable inside the network) wrapped as `{"event": ..., "payload": <raw body>}` -
 * same pattern as [DiscourseWebhookHandler].
 *
 * While the secret is still the placeholder default the route refuses everything (except in
 * development), so a missing production setting can't leave it open to forged signatures.
 */
class MoodleWebhookHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    private val appConfig: AppConfig,
) : IRouteHandler {
  private val log = loggerFactory.create(javaClass)
  private val secret = appConfig.requireStringProperty("app.moodle.webhook-secret")
  private val serviceUrl = appConfig.requireStringProperty("app.member-service.url")
  private val client = HttpClient(DmsHttpEngine)

  @Suppress("TooGenericExceptionCaught")
  override suspend fun handle(call: ApplicationCall) {
    if (!isSecretUsable(secret, appConfig.isDevelopmentMode())) {
      log.warn("Rejected Moodle webhook: ENV_MOODLE_WEBHOOK_SECRET is not set")
      call.respond(HttpStatusCode.Forbidden)
      return
    }
    val body = call.receiveText()
    if (!WebhookHmac.isValidSignature(secret, body, call.request.header(SIGNATURE_HEADER))) {
      log.warn("Rejected Moodle webhook with missing or invalid signature")
      call.respond(HttpStatusCode.Forbidden)
      return
    }
    val event = eventOf(body)
    try {
      val response =
          client.post("$serviceUrl/webhook/moodle-course") {
            contentType(ContentType.Application.Json)
            setBody(WebhookHmac.wrapEnvelope(event, body))
          }
      call.respond(response.status)
    } catch (e: Exception) {
      log.error("Failed to forward Moodle webhook to service", e)
      call.respond(HttpStatusCode.InternalServerError)
    }
  }

  companion object {
    const val SIGNATURE_HEADER = "X-Addad-Signature"
    internal const val PLACEHOLDER_SECRET = WebhookHmac.PLACEHOLDER_SECRET

    internal fun isSecretUsable(secret: String, developmentMode: Boolean): Boolean =
        WebhookHmac.isSecretUsable(secret, developmentMode)

    /** The `event` field of the plugin's JSON body, or "unknown" if it isn't there. */
    internal fun eventOf(body: String): String =
        runCatching {
              com.google.gson.JsonParser.parseString(body).asJsonObject["event"].asString
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "unknown"
  }
}
