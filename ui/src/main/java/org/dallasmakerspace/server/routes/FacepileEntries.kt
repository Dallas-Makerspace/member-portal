package org.dallasmakerspace.server.routes

import org.dallasmakerspace.models.DMSMember
import org.dallasmakerspace.models.displayAvatarUrl
import org.dallasmakerspace.models.normalizeAvatarUrl

/** Rows for `fragments/facepile`: username, display name and avatar URL per avatar. */
object FacepileEntries {
  private const val AVATAR_SIZE = 90
  private const val PROFILE_PIC_FALLBACK = "/static/images/profile-pic-not-set.svg"

  /** A lookup miss (e.g. a stale account) still yields a usable row. */
  fun build(
      usernames: List<String>,
      members: Map<String, DMSMember>,
  ): List<Map<String, String>> =
      usernames.map { username ->
        val member = members[username]
        mapOf(
            "username" to username,
            "display_name" to (member?.displayName?.takeIf { it.isNotBlank() } ?: "@$username"),
            "avatar_url" to
                normalizeAvatarUrl(member?.displayAvatarUrl(), AVATAR_SIZE).ifEmpty {
                  PROFILE_PIC_FALLBACK
                },
        )
      }
}
