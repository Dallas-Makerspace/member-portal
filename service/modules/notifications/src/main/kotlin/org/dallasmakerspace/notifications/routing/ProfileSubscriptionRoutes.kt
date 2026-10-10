package org.dallasmakerspace.notifications.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.auth.Permission
import org.dallasmakerspace.auth.authorize
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.FeatureFlagService
import org.dallasmakerspace.core.api.ApiResponse
import org.dallasmakerspace.core.api.Status
import org.dallasmakerspace.notifications.ProfileSubscriptionService

/**
 * Follow/unfollow another member, and who-follows-who listings. `{username}` is always the target
 * (the member being followed / whose lists are being read); the acting member (follower) comes from
 * the `X-Username` header set by the UI for the logged-in user - matching the convention used by
 * other self-service member routes in `Routing.kt` (e.g. `/members/{username}/settings`).
 *
 * The follow/unfollow writes are gated behind [ConfigRegistry.FOLLOW_FEATURE_ENABLED] for the
 * acting member as defense-in-depth - the UI already hides the feature when disabled, but this
 * stops a direct API call from bypassing that.
 */
fun Route.profileSubscriptionRoutes(
    service: ProfileSubscriptionService,
    featureFlagService: FeatureFlagService,
) {
  authenticate(ApiKeyAuthProvider.X_API_KEY) {
    authorize(Permission.MANAGE_MEMBERS) {
      post("/members/{username}/follow") {
        val target = call.parameters["username"] ?: return@post call.respondBadRequest()
        val actor = call.actorUsername() ?: return@post call.respondMissingActor()
        if (!featureFlagService.isEnabledForUsername(
            ConfigRegistry.FOLLOW_FEATURE_ENABLED, actor)) {
          return@post call.respond(
              HttpStatusCode.Forbidden,
              ApiResponse(Status.ERROR, "Follow feature is not enabled for $actor", null))
        }
        try {
          service.follow(actor, target)
          call.respond(ApiResponse(Status.SUCCESS, "$actor now follows $target", null))
        } catch (e: IllegalArgumentException) {
          call.respond(
              HttpStatusCode.BadRequest,
              ApiResponse(Status.ERROR, e.message ?: "Invalid follow request", null))
        } catch (e: IllegalStateException) {
          // Thrown by ProfileResolver when actor/target has no profile row.
          call.respond(
              HttpStatusCode.BadRequest,
              ApiResponse(Status.ERROR, e.message ?: "Invalid follow request", null))
        }
      }

      delete("/members/{username}/follow") {
        val target = call.parameters["username"] ?: return@delete call.respondBadRequest()
        val actor = call.actorUsername() ?: return@delete call.respondMissingActor()
        if (!featureFlagService.isEnabledForUsername(
            ConfigRegistry.FOLLOW_FEATURE_ENABLED, actor)) {
          return@delete call.respond(
              HttpStatusCode.Forbidden,
              ApiResponse(Status.ERROR, "Follow feature is not enabled for $actor", null))
        }
        service.unfollow(actor, target)
        call.respond(ApiResponse(Status.SUCCESS, "$actor unfollowed $target", null))
      }

      get("/members/{username}/following") {
        val username = call.parameters["username"] ?: return@get call.respondBadRequest()
        val following = service.getFollowing(username)
        call.respond(ApiResponse(Status.SUCCESS, "$username follows ${following.size}", following))
      }

      get("/members/{username}/followers") {
        val username = call.parameters["username"] ?: return@get call.respondBadRequest()
        val followers = service.getFollowers(username)
        call.respond(
            ApiResponse(Status.SUCCESS, "$username has ${followers.size} followers", followers))
      }
    }
  }
}

private fun ApplicationCall.actorUsername(): String? = request.headers["X-Username"]

private suspend fun ApplicationCall.respondBadRequest() =
    respond(HttpStatusCode.BadRequest, ApiResponse(Status.ERROR, "Username is required", null))

private suspend fun ApplicationCall.respondMissingActor() =
    respond(
        HttpStatusCode.BadRequest, ApiResponse(Status.ERROR, "X-Username header is required", null))
