package org.dallasmakerspace.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.dallasmakerspace.server.common.logging.ElasticsearchClientManager
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.plugins.UserSession

class ActionTrackingHandler @Inject constructor(loggerFactory: LoggerFactory) : IRouteHandler {
  private val log = loggerFactory.create(javaClass)

  @Suppress("TooGenericExceptionCaught", "SwallowedException")
  override suspend fun handle(call: ApplicationCall) {
    if (call.request.httpMethod != HttpMethod.Post) {
      call.respond(HttpStatusCode.MethodNotAllowed)
      return
    }

    try {
      val payload = call.receive<Map<String, Any?>>()

      val actionType = payload["actionType"] as? String
      if (actionType.isNullOrBlank()) {
        call.respond(HttpStatusCode.BadRequest)
        return
      }

      val session = call.sessions.get<UserSession>()
      val logEntry = buildMap {
        put("@timestamp", Instant.now().toString())
        put("app", "member-profile-ui")
        put("host", call.request.host())
        put("ip", call.request.origin.remoteAddress)
        put("sessionid", session?.sessionId ?: "NO_SESSION")
        put("userid", session?.userId ?: "NO_USER")
        put("userAgent", call.request.userAgent())
        putAll(payload)
      }

      // Respond immediately, then log asynchronously
      call.respond(HttpStatusCode.NoContent)

      call.application.launch(Dispatchers.IO) {
        try {
          log.debug("Logging user action to Elasticsearch: $logEntry")
          ElasticsearchClientManager.client
              .index { i -> i.index("member-portal-logs").document(logEntry) }
              .await()
        } catch (e: IOException) {
          ElasticsearchClientManager.enqueueForRetry(logEntry)
        }
      }
    } catch (e: Exception) {
      log.warn("Invalid tracking payload: ${e.message}")
      call.respond(HttpStatusCode.BadRequest)
    }
  }
}
