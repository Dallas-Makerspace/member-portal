package org.dallasmakerspace.notifications.topics

import org.dallasmakerspace.models.TopicKeys
import org.dallasmakerspace.notifications.NotificationTopic

/** "Your Bambu print finished" — produced only by a member's one-shot print watch firing. */
object BambuPrintFinishedTopic : NotificationTopic {
  override val key = TopicKeys.BAMBU_PRINT_FINISHED
  override val displayName = "Bambu print finished"
  override val visibleInPreferences = true

  override fun combinedSubject(count: Int) = "$count 3D print updates"
}
