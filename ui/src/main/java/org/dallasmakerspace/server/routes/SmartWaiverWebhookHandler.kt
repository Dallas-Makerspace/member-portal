package org.dallasmakerspace.server.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import java.net.URLDecoder
import javax.inject.Inject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.DmsHttpEngine
import org.dallasmakerspace.server.common.logging.LoggerFactory

class SmartWaiverWebhookHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    appConfig: AppConfig,
) : IRouteHandler {
  private val log = loggerFactory.create(javaClass)
  private val expectedToken = appConfig.requireStringProperty("app.smartwaiver.webhook-token")
  private val serviceUrl = appConfig.requireStringProperty("app.member-service.url")
  private val client = HttpClient(DmsHttpEngine)

  @Suppress("TooGenericExceptionCaught")
  override suspend fun handle(call: ApplicationCall) {
    val token = call.request.queryParameters["token"]
    if (token != expectedToken) {
      call.respond(HttpStatusCode.Forbidden)
      return
    }

    val body = call.receiveText()
    try {
      val params =
          body.split("&").associate { param ->
            val idx = param.indexOf('=')
            if (idx >= 0)
                URLDecoder.decode(param.substring(0, idx), "UTF-8") to
                    URLDecoder.decode(param.substring(idx + 1), "UTF-8")
            else param to ""
          }
      val uniqueId = params["unique_id"].orEmpty()
      val event = params["event"] ?: "new-waiver"
      log.info("SmartWaiver webhook received: unique_id=$uniqueId event=$event")
      val jsonBody = Json.encodeToString(mapOf("unique_id" to uniqueId, "event" to event))
      val response =
          client.post("$serviceUrl/webhook/smartwaiver") {
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
          }
      call.respond(response.status)
    } catch (e: Exception) {
      log.error("Failed to forward SmartWaiver webhook to service", e)
      call.respond(HttpStatusCode.InternalServerError)
    }
  }
}
