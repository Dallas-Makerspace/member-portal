package org.dallasmakerspace.server.bambustatus

import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import javax.inject.Inject
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.routes.AuthenticatedHandler

// Same locally-scoped Gson as BackendApiHandler: whole numbers must stay whole (printerId: 1,
// not 1.0) when the parsed body is forwarded to the backend service.
private val bodyGson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
private val bodyMapType = object : TypeToken<Map<String, Any?>>() {}.type

/**
 * Dedicated proxy for the Bambu watch buttons (POST arm / DELETE cancel), mirroring why the
 * camera proxy is dedicated: the service endpoints act on X-Username (derived here from the
 * session, never from the body), are gated on both Bambu feature flags, and their errors
 * (403/404/409/422 + member-facing message) must reach the button verbatim — through the generic
 * /backend-api proxy they would collapse to a message-less 500.
 */
class BambuWatchApiHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val username = userInfo["preferred_username"] as? String
    val statusEnabled =
        username?.let {
          memberServiceClient.isFeatureEnabled("bambu-status.enabled", it, session.sessionId)
        } ?: false
    val notificationsEnabled =
        username?.let {
          memberServiceClient.isFeatureEnabled("bambu-notifications.enabled", it, session.sessionId)
        } ?: false
    if (username == null || !statusEnabled || !notificationsEnabled) {
      call.respond(HttpStatusCode.NotFound)
      return
    }

    when (call.request.httpMethod) {
      HttpMethod.Post -> {
        val body =
            receiveJsonBody(call)
                ?: run {
                  call.respond(
                      HttpStatusCode.BadRequest,
                      mapOf("status" to "ERROR", "message" to "Invalid watch request"))
                  return
                }
        val scope = body["scope"] as? String
        val printerId = (body["printerId"] as? Number)?.toInt()
        if (scope == null || printerId == null) {
          call.respond(
              HttpStatusCode.BadRequest, mapOf("status" to "ERROR", "message" to "Invalid watch request"))
          return
        }
        val forward = mutableMapOf<String, Any?>("scope" to scope, "printerId" to printerId)
        (body["queueId"] as? Number)?.toInt()?.let { forward["queueId"] = it }
        val result = memberServiceClient.createBambuWatch(session.sessionId, username, forward)
        respondResult(call, result.statusCode, result.message, result.data)
      }
      HttpMethod.Delete -> {
        val watchId = call.parameters["id"]
        if (watchId.isNullOrBlank()) {
          call.respond(HttpStatusCode.BadRequest)
          return
        }
        val result = memberServiceClient.deleteBambuWatch(session.sessionId, username, watchId)
        respondResult(call, result.statusCode, result.message, result.data)
      }
      else -> call.respond(HttpStatusCode.MethodNotAllowed)
    }
  }

  private suspend fun respondResult(
      call: ApplicationCall,
      statusCode: Int,
      message: String?,
      data: Map<String, Any?>?,
  ) {
    if (statusCode in 200..299) {
      call.respond(mapOf("status" to "SUCCESS", "message" to message, "data" to data))
    } else {
      call.respond(
          HttpStatusCode.fromValue(statusCode),
          mapOf("status" to "ERROR", "message" to (message ?: "Something went wrong. Please try again.")))
    }
  }

  /** The parsed JSON body, or null when it isn't valid JSON (the caller answers 400). */
  private suspend fun receiveJsonBody(call: ApplicationCall): Map<String, Any?>? {
    val text = call.receiveText()
    if (text.isBlank()) return emptyMap()
    return try {
      bodyGson.fromJson(text, bodyMapType) ?: emptyMap()
    } catch (e: Exception) {
      null
    }
  }
}
