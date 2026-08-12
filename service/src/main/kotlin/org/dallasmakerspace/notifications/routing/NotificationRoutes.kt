package org.dallasmakerspace.notifications.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.auth.Permission
import org.dallasmakerspace.auth.authorize
import org.dallasmakerspace.notifications.Aggregation
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.CreateNotificationRequest
import org.dallasmakerspace.notifications.MemberPreferenceView
import org.dallasmakerspace.notifications.Notification
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.NotificationStatus
import org.dallasmakerspace.plugins.ApiResponse
import org.dallasmakerspace.plugins.Status

/** API view of a notification (dates as ISO-8601 strings). */
data class NotificationView(
    val id: Long,
    val memberUsername: String,
    val topic: String,
    val topicDisplayName: String,
    val channel: String,
    val aggregation: String,
    val subject: String,
    val status: String,
    val attempts: Int,
    val providerId: String?,
    val lastError: String?,
    val sentAt: String?,
    val createdAt: String?,
) {
  companion object {
    fun from(notification: Notification): NotificationView =
        NotificationView(
            id = notification.id,
            memberUsername = notification.memberUsername,
            topic = notification.topic.key,
            topicDisplayName = notification.topic.displayName,
            channel = notification.channel.name,
            aggregation = notification.aggregation.name,
            subject = notification.subject,
            status = notification.status.name,
            attempts = notification.attempts,
            providerId = notification.providerId,
            lastError = notification.lastError,
            sentAt = notification.sentAt?.toString(),
            createdAt = notification.createdAt.toString(),
        )
  }
}

/** API view of a member's effective preference for one topic/channel. */
data class PreferenceView(
    val memberUsername: String,
    val topic: String,
    val topicDisplayName: String,
    val channel: String,
    val enabled: Boolean,
    val aggregationOverride: String?,
    val effectiveAggregation: String,
    /** Mirrors [org.dallasmakerspace.notifications.NotificationTopic.visibleInPreferences] - the
     * topic's own say on whether it's ready to be offered as a toggle, so callers don't need their
     * own list of what's WIP. */
    val visibleInPreferences: Boolean,
)

/**
 * Wire format for POST /notifications - [topic] is a string key rather than [NotificationTopic]
 * directly, resolved via [NotificationService.findTopic] before reaching the service. Gson (this
 * route's content negotiation) can't deserialize an interface-typed field on its own, so this
 * mirrors [UpdatePreferenceRequest]'s existing string-then-resolve shape.
 */
data class CreateNotificationRequestBody(
    val memberUsername: String,
    val topic: String,
    val templateName: String,
    val payload: String? = null,
    val channel: Channel = Channel.EMAIL,
    val dedupeKey: String? = null,
)

/** Request body for PUT /notifications/preferences/{username}. */
data class UpdatePreferenceRequest(
    val topic: String,
    val channel: String,
    val enabled: Boolean = true,
    /** Aggregation override to store; null/absent clears the override (use channel default). */
    val aggregation: String? = null,
)

/** Notification REST API (emit, outbox listing, member preferences). */
fun Route.notificationRoutes(notificationService: NotificationService) {
  authenticate(ApiKeyAuthProvider.X_API_KEY) { protectedNotificationRoutes(notificationService) }
}

private fun Route.protectedNotificationRoutes(notificationService: NotificationService) {
  authorize(Permission.MANAGE_MEMBERS) {
    emitNotificationRoute(notificationService)
    listNotificationsRoute(notificationService)
    getPreferencesRoute(notificationService)
    updatePreferenceRoute(notificationService)
  }
}

/** Emit a notification for a member (returns the stored notification). */
private fun Route.emitNotificationRoute(notificationService: NotificationService) {
  post("/notifications") {
    val body = call.receive<CreateNotificationRequestBody>()
    val topic =
        notificationService.findTopic(body.topic)
            ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse(Status.ERROR, "Invalid topic: ${body.topic}", null))
    val created =
        notificationService.create(
            CreateNotificationRequest(
                memberUsername = body.memberUsername,
                topic = topic,
                templateName = body.templateName,
                payload = body.payload,
                channel = body.channel,
                dedupeKey = body.dedupeKey))
    call.respond(
        ApiResponse(
            Status.SUCCESS, "Notification ${created.id} created", NotificationView.from(created)))
  }
}

/** Outbox listing, newest first. Filters: username, status, limit (default 50, max 500). */
private fun Route.listNotificationsRoute(notificationService: NotificationService) {
  get("/notifications") {
    val username = call.request.queryParameters["username"]
    val statusParam = call.request.queryParameters["status"]
    val status =
        statusParam?.let {
          runCatching { NotificationStatus.valueOf(it) }
              .getOrElse {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse(Status.ERROR, "Invalid status: $it", null))
              }
        }
    val limit =
        (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIST_LIMIT).coerceIn(
            MIN_LIST_LIMIT, MAX_LIST_LIMIT)
    val notifications = notificationService.list(limit, username, status)
    call.respond(
        ApiResponse(
            Status.SUCCESS,
            "Notifications: ${notifications.size}",
            notifications.map(NotificationView::from),
        ))
  }
}

/** Effective preferences for a member (all topic/channel combinations). */
private fun Route.getPreferencesRoute(notificationService: NotificationService) {
  get("/notifications/preferences/{username}") {
    val username =
        call.parameters["username"] ?: return@get call.respondBadRequest("Username required")
    val views = notificationService.getPreferences(username)
    call.respond(
        ApiResponse(
            Status.SUCCESS,
            "Preferences for $username: ${views.size}",
            views.map(MemberPreferenceView::toView),
        ))
  }
}

/** Update a member's preference for one topic/channel. */
private fun Route.updatePreferenceRoute(notificationService: NotificationService) {
  put("/notifications/preferences/{username}") {
    val username =
        call.parameters["username"] ?: return@put call.respondBadRequest("Username required")
    val body = call.receive<UpdatePreferenceRequest>()
    val topic =
        notificationService.findTopic(body.topic)
            ?: return@put call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse(Status.ERROR, "Invalid topic: ${body.topic}", null))
    val channel =
        runCatching { Channel.valueOf(body.channel) }
            .getOrElse {
              return@put call.respond(
                  HttpStatusCode.BadRequest,
                  ApiResponse(Status.ERROR, "Invalid channel: ${body.channel}", null))
            }
    val aggregation =
        body.aggregation?.let {
          runCatching { Aggregation.valueOf(it) }
              .getOrElse {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse(Status.ERROR, "Invalid aggregation: $it", null))
              }
        }
    val view =
        notificationService.setPreference(username, topic, channel, body.enabled, aggregation)
    call.respond(
        ApiResponse(
            Status.SUCCESS,
            "Preference updated for $username/${topic.key}/${channel.name}",
            view.toView(),
        ))
  }
}

private fun MemberPreferenceView.toView(): PreferenceView =
    PreferenceView(
        memberUsername = memberUsername,
        topic = topic.key,
        topicDisplayName = topic.displayName,
        channel = channel.name,
        enabled = enabled,
        aggregationOverride = aggregationOverride?.name,
        effectiveAggregation = effectiveAggregation.name,
        visibleInPreferences = topic.visibleInPreferences,
    )

private suspend fun io.ktor.server.application.ApplicationCall.respondBadRequest(message: String) =
    respond(HttpStatusCode.BadRequest, ApiResponse(Status.ERROR, message, null))

private const val DEFAULT_LIST_LIMIT = 50

private const val MIN_LIST_LIMIT = 1

private const val MAX_LIST_LIMIT = 500
