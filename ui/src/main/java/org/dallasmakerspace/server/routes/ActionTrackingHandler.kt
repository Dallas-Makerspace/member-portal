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
        putAll(sanitize(payload))
        put("@timestamp", Instant.now().toString())
        put("app", "member-profile-ui")
        put("host", call.request.host())
        put("ip", call.request.origin.remoteAddress)
        put("sessionid", session?.sessionId ?: "NO_SESSION")
        put("userid", session?.userId ?: "NO_USER")
        put("userAgent", call.request.userAgent())
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

  companion object {
    private const val MAX_KEYS = 20
    private const val MAX_KEY_LENGTH = 64
    private const val MAX_VALUE_LENGTH = 1024

    /** Client fields only: scalars, capped in count and length. Server fields are added after. */
    internal fun sanitize(payload: Map<String, Any?>): Map<String, Any?> =
        payload.entries
            .filter { (k, v) -> k.length <= MAX_KEY_LENGTH && isScalar(v) }
            .take(MAX_KEYS)
            .associate { (k, v) -> k to if (v is String) v.take(MAX_VALUE_LENGTH) else v }

    private fun isScalar(v: Any?) = v == null || v is String || v is Number || v is Boolean
  }
}
