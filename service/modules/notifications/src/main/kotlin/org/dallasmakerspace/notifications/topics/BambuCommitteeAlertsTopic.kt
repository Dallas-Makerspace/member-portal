package org.dallasmakerspace.notifications.topics

import org.dallasmakerspace.models.TopicKeys
import org.dallasmakerspace.notifications.NotificationTopic

/**
 * "A 3D printer went offline and stayed offline" — standing subscription for committee members
 * (see BambuCommitteeAlertsSubscriptionHandler in the bambuddy module).
 */
object BambuCommitteeAlertsTopic : NotificationTopic {
  override val key = TopicKeys.BAMBU_COMMITTEE_ALERTS
  override val displayName = "3D printer offline alerts"
  override val visibleInPreferences = true

  override fun combinedSubject(count: Int) = "$count 3D printer offline alerts"
}
