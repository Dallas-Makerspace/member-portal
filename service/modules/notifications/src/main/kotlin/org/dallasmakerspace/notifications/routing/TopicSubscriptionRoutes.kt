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
import org.dallasmakerspace.core.api.ApiResponse
import org.dallasmakerspace.core.api.Status
import org.dallasmakerspace.notifications.subscriptions.SubscriptionResult
import org.dallasmakerspace.notifications.subscriptions.TopicSubscriptionService

private typealias SubscriptionAction =
    suspend (topicKey: String, actor: String, args: Map<String, String>) -> SubscriptionResult

/**
 * Subscribe/unsubscribe the acting member (`X-Username`, set by the UI for the logged-in user) to a
 * notification topic. The topic's own args (e.g. `username` for following a member) travel as query
 * parameters, so DELETE needs no body.
 */
fun Route.topicSubscriptionRoutes(service: TopicSubscriptionService) {
  authenticate(ApiKeyAuthProvider.X_API_KEY) {
    authorize(Permission.MANAGE_MEMBERS) {
      get("/subscriptions/{topicKey}") { call.handleSubscription(service::status) }
      post("/subscriptions/{topicKey}") { call.handleSubscription(service::subscribe) }
      delete("/subscriptions/{topicKey}") { call.handleSubscription(service::unsubscribe) }
    }
  }
}

private suspend fun ApplicationCall.handleSubscription(action: SubscriptionAction) {
  val topicKey =
      parameters["topicKey"] ?: return respondError(HttpStatusCode.BadRequest, "Topic is required")
  val actor =
      request.headers["X-Username"]
          ?: return respondError(HttpStatusCode.BadRequest, "X-Username header is required")
  val params = request.queryParameters
  if (params.names().any { params.getAll(it).orEmpty().size > 1 }) {
    return respondError(HttpStatusCode.BadRequest, "Repeated arguments are not allowed")
  }
  val args = params.names().associateWith { params[it].orEmpty() }
  when (val result = action(topicKey, actor, args)) {
    is SubscriptionResult.Ok ->
        respond(ApiResponse(Status.SUCCESS, "Subscription status for $topicKey", result.status))
    is SubscriptionResult.NotFound -> respondError(HttpStatusCode.NotFound, result.message)
    is SubscriptionResult.Forbidden -> respondError(HttpStatusCode.Forbidden, result.message)
    is SubscriptionResult.BadRequest -> respondError(HttpStatusCode.BadRequest, result.message)
  }
}

private suspend fun ApplicationCall.respondError(code: HttpStatusCode, message: String) =
    respond(code, ApiResponse<String?>(Status.ERROR, message, null))
