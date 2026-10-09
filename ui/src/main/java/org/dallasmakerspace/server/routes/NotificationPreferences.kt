package org.dallasmakerspace.server.routes

import io.ktor.http.Parameters
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.memberservice.NotificationPreferenceUpdate

/** The notification preferences section of the member settings page. */
class NotificationPreferences
@Inject
constructor(private val memberServiceClient: MemberServiceClient) {

  /** Template model for the preferences card; the inbox feeds the daily-digest suggestion. */
  suspend fun model(username: String, sessionId: String?): Map<String, Any> = coroutineScope {
    val notificationsDeferred = async {
      memberServiceClient.getNotifications(username, SUGGESTION_LOOKBACK_LIMIT, sessionId)
    }
    val preferencesDeferred = async {
      memberServiceClient.getNotificationPreferences(username, sessionId)
    }
    val statusDeferred = async {
      memberServiceClient.getNotificationDeliveryStatus(username, sessionId)
    }
    val preferenceTopics = groupPreferences(preferencesDeferred.await())
    val status = statusDeferred.await()
    mapOf(
        "preference_topics" to preferenceTopics,
        "suggest_daily_digest" to
            shouldSuggestDailyDigest(
                notificationsDeferred.await(), preferenceTopics, LocalDateTime.now(UTC_ZONE)),
        "suggestion_threshold" to DIGEST_SUGGESTION_EMAILS,
        "aggregation_options" to AGGREGATION_DISPLAY_NAMES.entries.toList(),
        "suppressed" to (status["suppressed"] == true),
        "digest_schedule" to (status["digestSchedule"] as? String ?: ""),
    )
  }

  /**
   * Saves exactly the topics the form rendered a row for: every row carries `aggregation_$topic`,
   * so a topic the member never saw isn't read as unchecked. One PUT per topic (no bulk endpoint).
   */
  suspend fun save(username: String, sessionId: String?, params: Parameters) {
    params
        .names()
        .filter { it.startsWith(AGGREGATION_FIELD_PREFIX) }
        .map { it.removePrefix(AGGREGATION_FIELD_PREFIX) }
        .forEach { topic ->
          val enabled = params["enabled_$topic"] == "true"
          val aggregation =
              params["aggregation_$topic"]?.takeIf { it in AGGREGATION_DISPLAY_NAMES.keys }
          memberServiceClient.updateNotificationPreference(
              username,
              sessionId,
              NotificationPreferenceUpdate(topic, EMAIL_CHANNEL, enabled, aggregation))
        }
  }

  companion object {
    /** Hidden `form` field value that routes a settings POST here. */
    const val FORM_NAME = "notifications"
    private const val AGGREGATION_FIELD_PREFIX = "aggregation_"
    private const val SUGGESTION_LOOKBACK_LIMIT = 50
    private val UTC_ZONE: ZoneId = ZoneId.of("UTC")
  }
}
