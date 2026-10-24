package org.dallasmakerspace.notifications.topics

import org.dallasmakerspace.models.TopicKeys
import org.dallasmakerspace.notifications.NotificationTopic

/** "A Bambu printer you were waiting on is available" — one-shot printer-available watches. */
object BambuPrinterAvailableTopic : NotificationTopic {
  override val key = TopicKeys.BAMBU_PRINTER_AVAILABLE
  override val displayName = "Bambu printer available"
  // Hidden: only produced by a watch click (consent override), so a toggle would do nothing.
  override val visibleInPreferences = false

  override fun combinedSubject(count: Int) = "$count 3D print updates"
}
