package org.dallasmakerspace.server.routes

import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.thymeleaf.ThymeleafContent
import javax.inject.Inject
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.memberservice.NotificationPreferenceUpdate
import org.dallasmakerspace.server.plugins.AuthException

/** Member notification inbox and preference management page (/notifications). */
class NotificationsInboxHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val username =
        userInfo["preferred_username"] as? String
            ?: throw AuthException("No username found in session")
    if (call.request.httpMethod == HttpMethod.Post) {
      updatePreferences(call, username)
    } else {
      showInbox(call, username)
    }
  }

  private suspend fun updatePreferences(call: ApplicationCall, username: String) {
    val params = call.receiveParameters()
    val topic = params["topic"] ?: throw AuthException("No topic found in form")
    val channel = params["channel"] ?: throw AuthException("No channel found in form")
    val enabled = params["enabled"] == "true"
    val aggregation = params["aggregation"]?.takeIf { it.isNotBlank() }
    memberServiceClient.updateNotificationPreference(
        username,
        session.sessionId,
        NotificationPreferenceUpdate(topic, channel, enabled, aggregation))
    call.respondRedirect("${RouteFactory.Paths.NOTIFICATIONS.path}?updated=true", permanent = false)
  }

  private suspend fun showInbox(call: ApplicationCall, username: String) {
    val notifications =
        memberServiceClient
            .getNotifications(username, INBOX_LIMIT, session.sessionId)
            .map(::enrichNotification)
    val preferences = memberServiceClient.getNotificationPreferences(username, session.sessionId)
    val model =
        mutableMapOf<String, Any>(
            "preferred_username" to username,
            "notifications" to notifications,
            "preference_topics" to groupPreferences(preferences),
            "aggregation_options" to AGGREGATION_OPTIONS,
        )
    if (call.request.queryParameters["updated"] == "true") {
      log.info("Showing toast for successful preference update")
      model["toast_message"] = "Notification preference updated."
    }
    call.respond(ThymeleafContent("notifications", model))
  }

  private companion object {
    const val INBOX_LIMIT = 50
  }
}

/** Add display metadata (status label + bootstrap badge class) for the inbox table. */
fun enrichNotification(notification: Map<String, Any?>): Map<String, Any?> {
  val status = notification["status"] as? String ?: ""
  return notification +
      mapOf(
          "status_display" to (STATUS_DISPLAY_NAMES[status] ?: status),
          "status_class" to (STATUS_BADGE_CLASSES[status] ?: "secondary"),
      )
}

/** Group flat preference rows into topic blocks (topic -> channel rows) for the template. */
fun groupPreferences(preferences: List<Map<String, Any?>>): List<Map<String, Any?>> =
    TOPICS.map { (topic, topicDisplayName) ->
      val rows =
          CHANNELS.map { (channel, channelDisplayName) ->
            val pref = preferences.firstOrNull { it["topic"] == topic && it["channel"] == channel }
            mapOf(
                "channel" to channel,
                "channelDisplayName" to channelDisplayName,
                "enabled" to (pref?.get("enabled") as? Boolean ?: true),
                "effectiveAggregation" to (pref?.get("effectiveAggregation") as? String ?: ""),
                "aggregationOverride" to (pref?.get("aggregationOverride") as? String),
            )
          }
      mapOf("topic" to topic, "topicDisplayName" to topicDisplayName, "rows" to rows)
    }

val TOPICS =
    linkedMapOf(
        "EVENT_ANNOUNCEMENT" to "Event announcements",
        "ACCOUNT" to "Account",
        "SYSTEM" to "System",
    )

val CHANNELS =
    linkedMapOf(
        "EMAIL" to "Email",
        "SMS" to "SMS",
        "DISCORD" to "Discord",
        "DISCOURSE" to "Discourse",
    )

val AGGREGATION_OPTIONS =
    listOf(
        mapOf("value" to "IMMEDIATE", "label" to "Immediately"),
        mapOf("value" to "DAILY", "label" to "Daily digest"),
        mapOf("value" to "WEEKLY", "label" to "Weekly digest"),
    )

val STATUS_DISPLAY_NAMES =
    mapOf(
        "QUEUED" to "Queued",
        "IN_DIGEST" to "In digest",
        "SENDING" to "Sending",
        "SENT" to "Sent",
        "FAILED" to "Failed",
        "DEAD_LETTER" to "Dead letter",
        "SUPPRESSED" to "Suppressed",
        "SKIPPED" to "Skipped",
    )

val STATUS_BADGE_CLASSES =
    mapOf(
        "SENT" to "success",
        "SENDING" to "info",
        "QUEUED" to "warning",
        "IN_DIGEST" to "warning",
        "FAILED" to "danger",
        "DEAD_LETTER" to "danger",
        "SUPPRESSED" to "secondary",
        "SKIPPED" to "secondary",
    )
