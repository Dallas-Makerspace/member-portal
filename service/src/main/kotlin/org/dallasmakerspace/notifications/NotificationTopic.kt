package org.dallasmakerspace.notifications

/** The app feature (topic) that produces notifications; members can opt in/out per topic. */
enum class NotificationTopic(val displayName: String) {
  EVENT_ANNOUNCEMENT("Event announcements"),
  ACCOUNT("Account"),
  SYSTEM("System");

  /**
   * Whether this topic may be sent to members whose Active Directory account is disabled
   * (membership lapsed). Defaults to false: notifications are for current members only unless a
   * topic explicitly opts in (e.g. account-status emails a lapsed member still needs to see).
   */
  val allowInactiveMembers: Boolean
    get() =
        when (this) {
          ACCOUNT -> true
          EVENT_ANNOUNCEMENT,
          SYSTEM -> false
        }
}
