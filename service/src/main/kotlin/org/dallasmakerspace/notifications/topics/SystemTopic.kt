package org.dallasmakerspace.notifications.topics

import org.dallasmakerspace.notifications.NotificationTopic

/**
 * System-level notices. Today the only thing targeting this topic is
 * [org.dallasmakerspace.notifications.templates.SystemTestTemplate], a manual tool for exercising
 * the notification pipeline - not a real producer - so this stays hidden from Notification
 * Preferences (interface defaults: `allowInactiveMembers = false`, `visibleInPreferences = false`).
 * A future real system-notice producer should reference this same object rather than declaring a
 * second "System" topic.
 */
object SystemTopic : NotificationTopic {
  override val key = "SYSTEM"
  override val displayName = "System"
}
