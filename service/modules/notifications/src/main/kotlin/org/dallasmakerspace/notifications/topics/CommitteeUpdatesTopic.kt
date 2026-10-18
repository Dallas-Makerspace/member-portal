package org.dallasmakerspace.notifications.topics

import org.dallasmakerspace.models.TopicKeys
import org.dallasmakerspace.notifications.NotificationTopic

/**
 * "A committee you follow has news" - new classes today (via
 * [org.dallasmakerspace.webhook.handlers.CommitteeClassAnnouncer]); online courses and committee
 * meetings later. One topic, so members get a single preference toggle for everything they follow
 * on committees.
 */
object CommitteeUpdatesTopic : NotificationTopic {
  override val key = TopicKeys.COMMITTEE_UPDATES
  override val displayName = "Updates from committees you follow"
  override val visibleInPreferences = true

  override fun combinedSubject(count: Int) = "$count new updates from committees you follow"
}
