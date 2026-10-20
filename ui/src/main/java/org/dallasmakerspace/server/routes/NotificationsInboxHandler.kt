package org.dallasmakerspace.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.thymeleaf.ThymeleafContent
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.plugins.AuthException

/**
 * Member notification inbox (/profile/@{preferred_username}/notifications). Preferences live on
 * the settings page (see [NotificationPreferences]).
 */
class NotificationsInboxHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

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
          memberServiceClient.isFeatureEnabled(FEATURE_FLAG_KEY, it, session.sessionId)
        } ?: false
    if (!flagEnabled) {
      call.respond(HttpStatusCode.NotFound)
      return
    }

    showInbox(
        call,
        requestedUsername,
        viewingOther = requestedUsername != currentUsername,
        filter = call.request.queryParameters["filter"],
    )
  }

  /**
   * The feed is built from the rows as they are on arrival (so what's new is highlighted), and
   * only then is the inbox marked read: opening your own inbox clears the unread dot, and an admin
   * looking at someone else's must not.
   */
  private suspend fun showInbox(
      call: ApplicationCall,
      username: String,
      viewingOther: Boolean,
      filter: String?,
  ) = coroutineScope {
    val showErrors = authz.can(Permission.MANAGE_MEMBERS.name)
    val notificationsDeferred = async {
      memberServiceClient.getNotifications(username, INBOX_LIMIT, session.sessionId)
    }
    val statusDeferred = async {
      memberServiceClient.getNotificationDeliveryStatus(username, session.sessionId)
    }
    val activeFilter = filter?.takeIf { it in INBOX_FILTER_TOPICS }
    val rows = notificationsDeferred.await()
    val now = LocalDateTime.now(ZoneId.of("America/Chicago"))
    val feed =
        buildInboxFeed(
            rows,
            today = now.toLocalDate(),
            filter = activeFilter,
            showErrors = showErrors,
            now = now,
            capped = rows.size >= INBOX_LIMIT)
    // Only the unfiltered view shows everything, so only it clears the unread dot.
    if (!viewingOther && activeFilter == null) {
      memberServiceClient.markAllNotificationsRead(username, session.sessionId)
    }
    val model =
        feed +
            mapOf<String, Any?>(
                "preferred_username" to username,
                "suppressed" to (statusDeferred.await()["suppressed"] == true),
                "viewing_other" to viewingOther,
                "filter" to (activeFilter ?: "all"),
            )
    call.respond(ThymeleafContent("notifications", model.filterValues { it != null } as Map<String, Any>))
  }

  companion object {
    /** Gates this page and the settings page's preferences card. */
    const val FEATURE_FLAG_KEY = "notifications.follow-feature-enabled"
    private const val INBOX_LIMIT = 200
  }
}

/** Self-or-admin access check for the profile-scoped notifications page. */
fun canViewNotificationsFor(
    requestedUsername: String,
    currentUsername: String?,
    canManageMembers: Boolean,
): Boolean = requestedUsername == currentUsername || canManageMembers

/**
 * Adds display metadata for the inbox table: a member-friendly status label/badge/explanation,
 * the topic label, and Central-time timestamps. Raw delivery errors (provider responses etc.) are
 * only exposed as `error_detail` when [showErrors] is set (admins).
 */
fun enrichNotification(
    notification: Map<String, Any?>,
    showErrors: Boolean = false,
): Map<String, Any?> {
  val status = notification["status"] as? String ?: ""
  val topic = notification["topic"] as? String ?: ""
  val lastError = (notification["lastError"] as? String)?.takeIf { it.isNotBlank() }
  val nextAttemptAt = notification["nextAttemptAt"] as? String
  return notification +
      mapOf(
          "status_display" to (STATUS_DISPLAY_NAMES[status] ?: status),
          "status_class" to (STATUS_BADGE_CLASSES[status] ?: "secondary"),
          "status_reason" to statusReason(status, lastError, nextAttemptAt),
          "error_detail" to lastError?.takeIf { showErrors },
          "topic_display" to ((notification["topicDisplayName"] as? String) ?: topic),
          "when" to toCentralIso(notification["sentAt"] as? String ?: notification["createdAt"] as? String),
      )
}

/** Plain-language explanation of a status for the member (null when self-explanatory). */
fun statusReason(status: String, lastError: String?, nextAttemptAt: String?): String? =
    when (status) {
      "IN_DIGEST" -> {
        val due = formatCentral(nextAttemptAt)?.let { "on $it" } ?: "next"
        if (lastError?.startsWith(DAILY_CAP_REASON_PREFIX) == true) {
          "You reached today's email limit, so this will be in your digest $due."
        } else {
          "Will be included in your digest $due."
        }
      }
      "FAILED" -> "We hit a temporary problem sending this and will try again."
      "DEAD_LETTER" ->
          if (lastError?.contains("bounced", ignoreCase = true) == true) {
            "Your email provider rejected this message."
          } else {
            "We couldn't deliver this email."
          }
      "SUPPRESSED" -> "Emails to you are paused because an earlier email bounced."
      "SKIPPED" ->
          when {
            lastError?.contains("preferences", ignoreCase = true) == true ->
                "Not sent because this topic is turned off in your preferences."
            lastError?.contains("inactive", ignoreCase = true) == true ->
                "Not sent because your membership wasn't active at the time."
            else -> "Not sent."
          }
      else -> null
    }

private val UTC_ZONE: ZoneId = ZoneId.of("UTC")
private val CENTRAL_ZONE: ZoneId = ZoneId.of("America/Chicago")
private val DIGEST_DUE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a")

/** Service timestamps are UTC local date-times; convert to Central for display. */
internal fun toCentral(utc: String?): ZonedDateTime? =
    utc?.let { runCatching { LocalDateTime.parse(it).atZone(UTC_ZONE).withZoneSameInstant(CENTRAL_ZONE) }.getOrNull() }

internal fun toCentralIso(utc: String?): String? =
    toCentral(utc)?.toLocalDateTime()?.toString() ?: utc

internal fun formatCentral(utc: String?): String? = toCentral(utc)?.format(DIGEST_DUE_FORMAT)

/**
 * Collapses rows that were mailed out together in a single digest email into one parent row with
 * the underlying notifications nested as `children`, so the page mirrors what the member actually
 * received (one email) instead of listing each contributing event as its own delivery.
 *
 * Rows are grouped by (providerId, sentAt): the digest processor sends one message per member and
 * stamps that send's provider id onto every row it contained, so a shared provider id means "one
 * delivered email". sentAt rides along in case a provider id is ever reused. A "group" of exactly
 * one row is an ordinary individual send and is left standalone.
 */
fun groupDigests(notifications: List<Map<String, Any?>>): List<Map<String, Any?>> {
  fun deliveryKey(n: Map<String, Any?>): String? {
    if (n["status"] != "SENT") return null
    val providerId = n["providerId"] as? String
    val sentAt = n["sentAt"] as? String
    return if (providerId != null && sentAt != null) "$providerId|$sentAt" else null
  }

  val childrenByKey =
      notifications.filter { deliveryKey(it) != null }.groupBy { deliveryKey(it)!! }
  val emitted = mutableSetOf<String>()
  return notifications.mapNotNull { n ->
    val key = deliveryKey(n)
    val siblings = key?.let(childrenByKey::get).orEmpty()
    when {
      key == null || siblings.size <= 1 -> n + mapOf("isDigest" to false)
      !emitted.add(key) -> null
      else -> buildDigestGroup(siblings)
    }
  }
}

/** Builds the parent row shown for one digest email; [children] are already-enriched rows. */
private fun buildDigestGroup(children: List<Map<String, Any?>>): Map<String, Any?> {
  val first = children.first()
  val topicDisplays = children.mapNotNull { it["topicDisplayName"] as? String }.toSet()
  val topicDisplay = if (topicDisplays.size == 1) topicDisplays.first() else "Multiple topics"
  // Rows combined by the immediate hold window keep aggregation=IMMEDIATE; digests are DAILY/WEEKLY.
  val kind = if (children.all { it["aggregation"] == "IMMEDIATE" }) "Combined email" else "Digest email"
  return mapOf(
      "isDigest" to true,
      "subject" to "$kind (${children.size} update${if (children.size == 1) "" else "s"})",
      "topic_display" to topicDisplay,
      "when" to toCentralIso(first["sentAt"] as? String),
      // Stable per-group CSS class (not an id, since it targets every child <tr> at once via
      // Bootstrap's multi-target collapse) - the first child's id is unique enough to key it.
      "groupId" to "digest-${first["id"]}",
      "children" to children,
  )
}

/**
 * One row per topic the member can actually act on: an EMAIL preference the topic itself marks
 * `visibleInPreferences` (see `NotificationTopic.visibleInPreferences` on the service side), so a
 * WIP topic nothing produces yet stays hidden without this page keeping its own list. The service
 * only returns channels that have a notifier, and email is the only one today.
 */
fun groupPreferences(preferences: List<Map<String, Any?>>): List<Map<String, Any?>> =
    preferences
        .filter { it["channel"] == EMAIL_CHANNEL && it["visibleInPreferences"] == true }
        .map { pref ->
          val topic = pref["topic"] as? String ?: ""
          val effective =
              (pref["effectiveAggregation"] as? String)?.takeIf { it.isNotBlank() }
                  ?: DEFAULT_EMAIL_AGGREGATION
          mapOf(
              "topic" to topic,
              "topicDisplayName" to ((pref["topicDisplayName"] as? String) ?: topic),
              "enabled" to (pref["enabled"] as? Boolean ?: true),
              "digestSupported" to (pref["digestSupported"] as? Boolean ?: false),
              "effectiveAggregation" to effective,
              "effectiveAggregationDisplay" to (AGGREGATION_DISPLAY_NAMES[effective] ?: effective),
          )
        }

/**
 * Whether to suggest switching to a daily digest: some visible topic is still delivered
 * immediately and the member got at least [DIGEST_SUGGESTION_EMAILS] immediate emails (a combined
 * email counts once) in the last [DIGEST_SUGGESTION_DAYS] days. [nowUtc] matches the service's
 * UTC timestamps.
 */
fun shouldSuggestDailyDigest(
    notifications: List<Map<String, Any?>>,
    preferenceTopics: List<Map<String, Any?>>,
    nowUtc: LocalDateTime,
): Boolean {
  val anyImmediate =
      preferenceTopics.any {
        it["enabled"] == true && it["digestSupported"] == true && it["effectiveAggregation"] == "IMMEDIATE"
      }
  if (!anyImmediate) return false
  val since = nowUtc.minusDays(DIGEST_SUGGESTION_DAYS)
  val emails =
      notifications
          .filter { it["status"] == "SENT" && it["aggregation"] == "IMMEDIATE" }
          .filter { n ->
            (n["sentAt"] as? String)
                ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                ?.isAfter(since) == true
          }
          .map { it["providerId"] as? String ?: "row-${it["id"]}" }
          .distinct()
  return emails.size >= DIGEST_SUGGESTION_EMAILS
}

const val DIGEST_SUGGESTION_EMAILS = 10
const val DIGEST_SUGGESTION_DAYS = 7L

/** Mirrors `NotificationWorker.DAILY_CAP_REASON` on the service side. */
private const val DAILY_CAP_REASON_PREFIX = "Daily email limit reached"

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
        "QUEUED" to "Sending soon",
        "IN_DIGEST" to "In next digest",
        "SENDING" to "Sending",
        "SENT" to "Sent",
        "FAILED" to "Retrying",
        "DEAD_LETTER" to "Not delivered",
        "SUPPRESSED" to "Paused",
        "SKIPPED" to "Not sent",
    )

val STATUS_BADGE_CLASSES =
    mapOf(
        "SENT" to "success",
        "SENDING" to "info",
        "QUEUED" to "info",
        "IN_DIGEST" to "info",
        "FAILED" to "warning",
        "DEAD_LETTER" to "danger",
        "SUPPRESSED" to "secondary",
        "SKIPPED" to "secondary",
    )
