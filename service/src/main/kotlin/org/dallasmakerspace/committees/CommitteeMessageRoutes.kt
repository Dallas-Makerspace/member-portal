package org.dallasmakerspace.committees

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.ZoneOffset
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.auth.Permission
import org.dallasmakerspace.auth.authorize
import org.dallasmakerspace.core.api.ApiResponse
import org.dallasmakerspace.core.api.Status

/**
 * A chair's message to a committee's followers. The acting member is `X-Username` (set by the UI for
 * the logged-in user); chair status is checked by [CommitteeMessageService], not by the caller.
 */
fun Route.committeeMessageRoutes(service: CommitteeMessageService) {
  authenticate(ApiKeyAuthProvider.X_API_KEY) {
    authorize(Permission.MANAGE_MEMBERS) {
      get("/committees/{id}/message-eligibility") {
        val (actor, committeeId) = call.actorAndCommittee() ?: return@get
        call.respond(
            ApiResponse(
                Status.SUCCESS, "Message eligibility", service.eligibility(actor, committeeId)))
      }

      post("/committees/{id}/messages") {
        val (actor, committeeId) = call.actorAndCommittee() ?: return@post
        val request = call.receive<CommitteeMessageRequest>()
        try {
          call.respond(
              ApiResponse(Status.SUCCESS, "Message sent", service.send(actor, committeeId, request)))
        } catch (e: CommitteeMessageException) {
          call.respondMessageError(e)
        }
      }
    }
  }
}

private suspend fun ApplicationCall.actorAndCommittee(): Pair<String, Int>? {
  val actor = request.headers["X-Username"]
  val committeeId = parameters["id"]?.toIntOrNull()
  if (actor.isNullOrBlank() || committeeId == null) {
    respond(
        HttpStatusCode.BadRequest,
        ApiResponse<String?>(Status.ERROR, "X-Username header and committee id are required", null))
    return null
  }
  return actor to committeeId
}

private suspend fun ApplicationCall.respondMessageError(e: CommitteeMessageException) {
  val code =
      when (e.error) {
        CommitteeMessageError.UNKNOWN_COMMITTEE -> HttpStatusCode.NotFound
        CommitteeMessageError.NOT_ENABLED,
        CommitteeMessageError.NOT_CHAIR -> HttpStatusCode.Forbidden
        CommitteeMessageError.INVALID,
        CommitteeMessageError.BAD_THREAD -> HttpStatusCode.BadRequest
        CommitteeMessageError.TALK_NOT_LINKED,
        CommitteeMessageError.NO_CATEGORY -> HttpStatusCode.Conflict
        CommitteeMessageError.RATE_LIMITED -> HttpStatusCode.TooManyRequests
        CommitteeMessageError.UNAVAILABLE -> HttpStatusCode.BadGateway
      }
  respond(
      code,
      ApiResponse(
          Status.ERROR,
          e.message ?: "Message not sent",
          mapOf(
              "error" to e.error.name,
              "nextAllowedAt" to e.nextAllowedAt?.toInstant(ZoneOffset.UTC)?.toString())))
}
