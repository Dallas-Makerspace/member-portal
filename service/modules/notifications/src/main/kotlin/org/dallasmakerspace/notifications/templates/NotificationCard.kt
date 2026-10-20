package org.dallasmakerspace.notifications.templates

/**
 * What the portal inbox shows for a notification: a card built from the template's payload, so the
 * UI never parses payload JSON or knows which template produced it. Blank strings mean "absent".
 */
data class NotificationCard(
    /** Icon family for the card: event, class, project or course. */
    val kind: String,
    val title: String,
    val excerpt: String = "",
    val imageUrl: String = "",
    /** Where the card links; blank when there is nothing to open. */
    val url: String = "",
    /** Text of the link affordance, e.g. "View class". */
    val ctaLabel: String = "",
    /** Who it is from: "@username" for a member, a committee name for a committee. */
    val sender: String = "",
    /** The date the thing happens, already formatted (e.g. "Sat Nov 8, 10:00 AM"); may be blank. */
    val whenLine: String = "",
)

/** A member's profile as a same-site path, so the inbox keeps members on whichever portal they're on. */
fun portalProfilePath(username: String): String = "/profile/@$username"

object CardKinds {
  const val EVENT = "event"
  const val CLASS = "class"
  const val PROJECT = "project"
  const val COURSE = "course"
}
