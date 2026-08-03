package org.dallasmakerspace.server.routes

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import javax.inject.Inject
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.logging.LoggerFactory

/**
 * Public landing point for SparkPost's event webhook (delivery/bounce/open/click). The backend
 * service (which does the real handling) is only reachable inside the network, so this proxies the
 * raw event batch through to it - same pattern as [SmartWaiverWebhookHandler]. Registered with
 * SparkPost as `https://members.dallasmakerspace.org/webhook/sparkpost?token=<webhook-token>`.
 */
class SparkPostWebhookHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    appConfig: AppConfig,
) : IRouteHandler {
  private val log = loggerFactory.create(javaClass)
  private val expectedToken = appConfig.requireStringProperty("app.sparkpost.webhook-token")
  private val serviceUrl = appConfig.requireStringProperty("app.member-service.url")
  private val client = HttpClient(CIO)

  @Suppress("TooGenericExceptionCaught")
  override suspend fun handle(call: ApplicationCall) {
    val token = call.request.queryParameters["token"]
    if (token != expectedToken) {
      call.respond(HttpStatusCode.Forbidden)
      return
    }

    val body = call.receiveText()
    try {
      val response =
          client.post("$serviceUrl/webhook/sparkpost") {
            contentType(ContentType.Application.Json)
            setBody(body)
          }
      call.respond(response.status)
    } catch (e: Exception) {
      log.error("Failed to forward SparkPost webhook to service", e)
      call.respond(HttpStatusCode.InternalServerError)
    }
  }
}
