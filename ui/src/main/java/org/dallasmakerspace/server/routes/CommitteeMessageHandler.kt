package org.dallasmakerspace.server.routes

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import javax.inject.Inject
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient

private val bodyGson = Gson()
private val bodyMapType = object : TypeToken<Map<String, Any?>>() {}.type

/**
 * `POST /committees/{slug}/message`: a chair's message to the committee's followers. A dedicated
 * handler rather than the generic /backend-api proxy because the service answers with member-facing
 * errors (not a chair, limit reached, Talk not linked) that must reach the compose form, and because
 * the acting member must come from the session, never from the request body. The service decides
 * who is a chair; nothing here is a security check beyond the same-origin guard.
 */
class CommitteeMessageHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  @Suppress("ReturnCount")
  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val committee = call.parameters["committee_slug"]?.let { Committees.findBySlug(it) }
    val username = userInfo["preferred_username"] as? String
    if (committee == null || username == null) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    // This action reaches every follower, so unlike older handlers a missing Origin is refused too.
    if (call.request.header("Origin") == null || !call.isSameOriginRequest()) {
      call.respond(HttpStatusCode.Forbidden, error("Cross-site request rejected"))
      return
    }

    val body = parseBody(call.receiveText())
    if (body == null) {
      call.respond(HttpStatusCode.BadRequest, error("Invalid message request"))
      return
    }
    // Only these fields are forwarded; the acting member is the session user.
    val forward =
        mapOf(
            "message" to ((body["message"] as? String) ?: ""),
            "talkUrl" to (body["talkUrl"] as? String)?.takeIf { it.isNotBlank() },
            "createThread" to (body["createThread"] == true))
    val result =
        memberServiceClient.sendCommitteeMessage(committee.id, username, forward, session.sessionId)
    if (result.statusCode in 200..299) {
      call.respond(mapOf("status" to "SUCCESS", "message" to result.message, "data" to result.data))
    } else {
      call.respond(
          HttpStatusCode.fromValue(result.statusCode),
          mapOf(
              "status" to "ERROR",
              "message" to (result.message ?: "Something went wrong. Please try again."),
              "data" to result.data))
    }
  }

  private fun error(message: String) = mapOf("status" to "ERROR", "message" to message)

  @Suppress("TooGenericExceptionCaught")
  private fun parseBody(text: String): Map<String, Any?>? =
      try {
        bodyGson.fromJson<Map<String, Any?>>(text, bodyMapType)
      } catch (e: Exception) {
        null
      }
}
