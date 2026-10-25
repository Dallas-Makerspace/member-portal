package org.dallasmakerspace.notifications.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.auth.Permission
import org.dallasmakerspace.auth.authorize
import org.dallasmakerspace.core.api.ApiResponse
import org.dallasmakerspace.core.api.Status
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.notifications.CommitteeFollowersService

private const val DEFAULT_FOLLOWERS_LIMIT = 24
private const val MAX_FOLLOWERS_LIMIT = 100

/** Who follows a committee. Following and unfollowing go through [topicSubscriptionRoutes]. */
fun Route.committeeFollowersRoutes(service: CommitteeFollowersService) {
  authenticate(ApiKeyAuthProvider.X_API_KEY) {
    authorize(Permission.MANAGE_MEMBERS) {
      get("/committees/{id}/followers") {
        val committee =
            call.parameters["id"]?.toIntOrNull()?.let { Committees.findById(it) }
                ?: return@get call.respond(
                    HttpStatusCode.NotFound, ApiResponse(Status.ERROR, "Unknown committee", null))
        val limit =
            (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_FOLLOWERS_LIMIT)
                .coerceIn(1, MAX_FOLLOWERS_LIMIT)
        val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val page = service.getPage(committee.id, limit, offset)
        call.respond(
            ApiResponse(Status.SUCCESS, "${committee.name} has ${page.total} followers", page))
      }
    }
  }
}
