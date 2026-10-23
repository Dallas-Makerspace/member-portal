package org.dallasmakerspace.notifications.topics

import org.dallasmakerspace.models.TopicKeys
import org.dallasmakerspace.notifications.NotificationTopic

/** "Your Bambu print failed / was stopped" — produced only by a member's one-shot watch firing. */
object BambuPrintActionRequiredTopic : NotificationTopic {
  override val key = TopicKeys.BAMBU_PRINT_ACTION_REQUIRED
  override val displayName = "Bambu print needs attention"
  override val visibleInPreferences = true

  override fun combinedSubject(count: Int) = "$count 3D print updates"
}
