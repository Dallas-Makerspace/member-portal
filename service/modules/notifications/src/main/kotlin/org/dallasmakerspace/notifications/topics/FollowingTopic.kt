package org.dallasmakerspace.notifications.topics

import org.dallasmakerspace.models.TopicKeys
import org.dallasmakerspace.notifications.NotificationTopic

/**
 * "A member you follow posted something" - new calendar events (via
 * [org.dallasmakerspace.webhook.handlers.CalendarEventWebhook]) and new Show & Tell projects (via
 * [org.dallasmakerspace.webhook.handlers.DiscoursePostWebhook]). One topic, so members get a single
 * preference toggle for everything they follow.
 */
object FollowingTopic : NotificationTopic {
  // Legacy key from when this only covered events; stored rows and preferences use it.
  override val key = TopicKeys.FOLLOWING
  override val displayName = "Updates from people you follow"
  override val visibleInPreferences = true

  override fun combinedSubject(count: Int) = "$count new updates from members you follow"
}
