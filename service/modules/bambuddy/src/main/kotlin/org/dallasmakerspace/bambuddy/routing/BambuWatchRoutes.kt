package org.dallasmakerspace.bambuddy.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.dallasmakerspace.bambuddy.notifications.BambuWatchException
import org.dallasmakerspace.bambuddy.notifications.CreateBambuWatchRequest
import org.dallasmakerspace.bambuddy.notifications.EphemeralNotificationService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.FeatureFlagService
import org.dallasmakerspace.core.api.ApiResponse
import org.dallasmakerspace.core.api.Status

/**
 * One-shot Bambu print watches (design §8). Registered inside the service's existing
 * authenticate(ApiKeyAuthProvider.X_API_KEY) + authorize(Permission.MANAGE_MEMBERS) block alongside
 * [bambuddyRoutes]. The actor is always the X-Username header (set by the UI from the session);
 * the request DTO has no identity field.
 */
fun Route.bambuWatchRoutes(
    watchService: EphemeralNotificationService,
    featureFlagService: FeatureFlagService,
) {
  post("/bambu/watches") {
    val actor = call.actorOrRespond() ?: return@post
    if (!call.flagEnabled(featureFlagService, actor)) return@post
    val request =
        try {
          call.receive<CreateBambuWatchRequest>()
        } catch (e: Exception) {
          call.respondError(HttpStatusCode.BadRequest, "Invalid watch request")
          return@post
        }
    try {
      val watch = watchService.arm(actor, request)
      call.respond(ApiResponse(Status.SUCCESS, "Watch armed", watch))
    } catch (e: BambuWatchException) {
      call.respondError(HttpStatusCode.fromValue(e.statusCode), e.message ?: "Watch failed")
    }
  }

  get("/bambu/watches") {
    val actor = call.actorOrRespond() ?: return@get
    if (!call.flagEnabled(featureFlagService, actor)) return@get
    val watches = watchService.listArmed(actor)
    call.respond(ApiResponse(Status.SUCCESS, "Armed watches: ${watches.size}", watches))
  }

  delete("/bambu/watches/{id}") {
    val actor = call.actorOrRespond() ?: return@delete
    if (!call.flagEnabled(featureFlagService, actor)) return@delete
    val id =
        call.parameters["id"]?.toLongOrNull()
            ?: run {
              call.respondError(HttpStatusCode.BadRequest, "Watch id is required")
              return@delete
            }
    val watch = watchService.cancel(actor, id)
    if (watch == null) {
      call.respondError(HttpStatusCode.NotFound, "Watch not found")
    } else {
      call.respond(ApiResponse(Status.SUCCESS, "Watch ${watch.status.lowercase()}", watch))
    }
  }
}

private suspend fun ApplicationCall.actorOrRespond(): String? {
  val actor = request.headers["X-Username"]
  if (actor.isNullOrBlank()) {
    respondError(HttpStatusCode.BadRequest, "X-Username header is required")
    return null
  }
  return actor
}

private suspend fun ApplicationCall.flagEnabled(
    featureFlagService: FeatureFlagService,
    actor: String,
): Boolean {
  if (featureFlagService.isEnabledForUsername(ConfigRegistry.BAMBU_NOTIFICATIONS_ENABLED, actor)) {
    return true
  }
  respondError(HttpStatusCode.Forbidden, "Bambu notifications are not enabled for you yet")
  return false
}

private suspend fun ApplicationCall.respondError(code: HttpStatusCode, message: String) =
    respond(code, ApiResponse<String?>(Status.ERROR, message, null))
