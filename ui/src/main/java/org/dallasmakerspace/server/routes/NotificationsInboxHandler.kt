package org.dallasmakerspace.server.routes

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.thymeleaf.ThymeleafContent
import javax.inject.Inject
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.memberservice.NotificationPreferenceUpdate
import org.dallasmakerspace.server.plugins.AuthException

/**
 * Member notification inbox and preference management page
 * (/profile/@{preferred_username}/notifications). Email-only: SMS/Discord/Discourse exist as
 * Channel values on the backend but have no notifier wired up, so this page doesn't show them.
 */
class NotificationsInboxHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val requestedUsername =
        call.parameters["preferred_username"]
            ?: throw AuthException("No username found in url path")
    val currentUsername = userInfo["preferred_username"] as? String

    // The service's /notifications routes are authorized by API-key client, not by end user
    // (NotificationRoutes.kt authorize(Permission.MANAGE_MEMBERS) checks the UI's own service
    // credentials) - this guard is the only thing stopping member A from reading and writing
    // member B's preferences now that the username comes from the path instead of the session.
    if (!canViewNotificationsFor(
        requestedUsername, currentUsername, authz.can(Permission.MANAGE_MEMBERS.name))) {
      call.respond(HttpStatusCode.Forbidden)
      return
    }

    // The page is part of the follow pilot; hide it entirely (not 403) when the viewer is
    // off-pilot, since EVENT_ANNOUNCEMENT from follows is the only live producer right now.
    val flagEnabled =
        currentUsername?.let {
          memberServiceClient.isFeatureEnabled(FOLLOW_FEATURE_FLAG_KEY, it, session.sessionId)
        } ?: false
    if (!flagEnabled) {
      call.respond(HttpStatusCode.NotFound)
      return
    }

    if (call.request.httpMethod == HttpMethod.Post) {
      updatePreferences(call, requestedUsername)
    } else {
      showInbox(call, requestedUsername)
    }
  }

  /**
   * One form, one Save button, covering every topic row at once. Fields are keyed per topic
   * (`enabled_$topic` / `aggregation_$topic`) since HTML forms are flat - there's no per-row
   * grouping to rely on. Saves each topic with its own PUT to the service (no bulk-update
   * endpoint exists); a partial failure partway through is an accepted risk for a low-stakes,
   * three-row preferences table.
   */
  private suspend fun updatePreferences(call: ApplicationCall, username: String) {
    val params = call.receiveParameters()
    TOPICS.keys.forEach { topic ->
      val enabled = params["enabled_$topic"] == "true"
      // Round-tripped verbatim from a hidden field in the template so toggling "enabled" never
      // clears an existing aggregation override - the PUT below treats a null aggregation as
      // "clear the override".
      val aggregation = params["aggregation_$topic"]?.takeIf { it.isNotBlank() }
      memberServiceClient.updateNotificationPreference(
          username,
          session.sessionId,
          NotificationPreferenceUpdate(topic, EMAIL_CHANNEL, enabled, aggregation))
    }
    call.respondRedirect(notificationsPathFor(username) + "?updated=true", permanent = false)
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
        )
    if (call.request.queryParameters["updated"] == "true") {
      log.info("Showing toast for successful preference update")
      model["toast_message"] = "Notification preference updated."
    }
    call.respond(ThymeleafContent("notifications", model))
  }

  private fun notificationsPathFor(username: String) =
      RouteFactory.Paths.NOTIFICATIONS.path.replace("{preferred_username}", username)

  private companion object {
    const val INBOX_LIMIT = 50
    const val FOLLOW_FEATURE_FLAG_KEY = "notifications.follow-feature-enabled"
  }
}

/** Self-or-admin access check for the profile-scoped notifications page. */
fun canViewNotificationsFor(
    requestedUsername: String,
    currentUsername: String?,
    canManageMembers: Boolean,
): Boolean = requestedUsername == currentUsername || canManageMembers

/**
 * Add display metadata (status label + bootstrap badge class + topic label) for the inbox table.
 */
fun enrichNotification(notification: Map<String, Any?>): Map<String, Any?> {
  val status = notification["status"] as? String ?: ""
  val topic = notification["topic"] as? String ?: ""
  return notification +
      mapOf(
          "status_display" to (STATUS_DISPLAY_NAMES[status] ?: status),
          "status_class" to (STATUS_BADGE_CLASSES[status] ?: "secondary"),
          "topic_display" to (TOPICS[topic] ?: topic),
      )
}

/**
 * One row per topic, email only - SMS/Discord/Discourse exist in the Channel enum but have no
 * notifier (DigestProcessor dead-letters them), so they're not worth showing here.
 */
fun groupPreferences(preferences: List<Map<String, Any?>>): List<Map<String, Any?>> =
    TOPICS.map { (topic, topicDisplayName) ->
      val pref = preferences.firstOrNull { it["topic"] == topic && it["channel"] == EMAIL_CHANNEL }
      val effective =
          (pref?.get("effectiveAggregation") as? String)?.takeIf { it.isNotBlank() }
              ?: DEFAULT_EMAIL_AGGREGATION
      mapOf(
          "topic" to topic,
          "topicDisplayName" to topicDisplayName,
          "enabled" to (pref?.get("enabled") as? Boolean ?: true),
          "aggregationOverride" to (pref?.get("aggregationOverride") as? String),
          "effectiveAggregation" to effective,
          "effectiveAggregationDisplay" to (AGGREGATION_DISPLAY_NAMES[effective] ?: effective),
      )
    }

val TOPICS =
    linkedMapOf(
        "EVENT_ANNOUNCEMENT" to "Event announcements",
        "ACCOUNT" to "Account",
        "SYSTEM" to "System",
    )

const val EMAIL_CHANNEL = "EMAIL"

/** Mirrors Channel.EMAIL.defaultAggregation on the service side. */
const val DEFAULT_EMAIL_AGGREGATION = "IMMEDIATE"

val AGGREGATION_DISPLAY_NAMES =
    linkedMapOf(
        "IMMEDIATE" to "Immediately",
        "DAILY" to "Daily digest",
        "WEEKLY" to "Weekly digest",
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
