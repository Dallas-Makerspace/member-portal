package org.dallasmakerspace.server.memberservice

/**
 * Ids of the collapsible cards on the settings page. Opening `/profile/@user/settings#<id>` expands
 * and scrolls to that card; the page script finds cards by markup, so this only names the targets
 * links point at.
 */
object SettingsSections {
  const val CONTACT_PREFERENCE = "contact-preference"
  const val LINKED_ACCOUNTS = "linked-accounts"
  const val NOTIFICATION_PREFERENCES = "notification-preferences"

  fun url(username: String, section: String): String = "/profile/@$username/settings#$section"
}
