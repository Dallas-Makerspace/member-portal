package org.dallasmakerspace.notifications

/**
 * The app feature (topic) that produces notifications; members can opt in/out per topic.
 *
 * Deliberately not a closed enum: each concrete topic is its own singleton object (see the
 * `notifications.topics` package), declared by whichever producer module owns it and contributed
 * to the notification pipeline by having a [org.dallasmakerspace.notifications.templates.NotificationTemplate]
 * reference it - [org.dallasmakerspace.notifications.templates.TemplateRegistry] derives the set of
 * known topics from whatever templates got Dagger-registered. No shared file lists topic names, and
 * nothing here hardcodes which topics exist.
 */
interface NotificationTopic {
  /** Stable identifier persisted in the database and serialized over the API. */
  val key: String

  val displayName: String

  /**
   * Whether this topic may be sent to members whose Active Directory account is disabled
   * (membership lapsed). Defaults to false: notifications are for current members only unless a
   * topic explicitly opts in (e.g. account-status emails a lapsed member still needs to see).
   */
  val allowInactiveMembers: Boolean
    get() = false

  /**
   * Whether this topic has a producer wired up end-to-end and should therefore be offered as a
   * toggle in Notification Preferences. Defaults to false ("WIP"): a topic nothing emits yet would
   * be a no-op toggle with no visible effect, which reads as broken rather than "not built yet".
   */
  val visibleInPreferences: Boolean
    get() = false

  /** Subject for one email combining [count] (> 1) notifications of this topic. */
  fun combinedSubject(count: Int): String = "$count new updates: $displayName"
}

/**
 * Stand-in for a stored topic key whose producer/template no longer exists, so rows written by a
 * since-removed feature still load (and can be listed or dead-lettered) instead of throwing.
 */
data class UnknownTopic(override val key: String) : NotificationTopic {
  override val displayName: String
    get() = key
}
