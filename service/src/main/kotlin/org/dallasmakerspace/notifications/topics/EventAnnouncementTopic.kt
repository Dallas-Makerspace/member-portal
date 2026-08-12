package org.dallasmakerspace.notifications.topics

import org.dallasmakerspace.notifications.NotificationTopic

/**
 * "An organizer you follow posted a new event" - produced by
 * [org.dallasmakerspace.webhook.handlers.CalendarEventWebhook] via
 * [org.dallasmakerspace.notifications.templates.EventAnnouncementTemplate], the only topic with a
 * live producer today.
 */
object EventAnnouncementTopic : NotificationTopic {
  override val key = "EVENT_ANNOUNCEMENT"
  override val displayName = "Event announcements"
  override val visibleInPreferences = true
}
