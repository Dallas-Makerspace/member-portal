package org.dallasmakerspace.notifications.topics

import org.dallasmakerspace.models.TopicKeys
import org.dallasmakerspace.notifications.NotificationTopic

/**
 * Lifecycle notices for a member's one-shot Bambu print watch (overdue / expired-final). Hidden
 * from Notification Preferences: these belong to a specific click, not a standing preference — a
 * toggle would imply control over future watches that doesn't exist.
 */
object BambuWatchTopic : NotificationTopic {
  override val key = TopicKeys.BAMBU_WATCH
  override val displayName = "Bambu print watch updates"
  override val visibleInPreferences = false

  override fun combinedSubject(count: Int) = "$count 3D print updates"
}
