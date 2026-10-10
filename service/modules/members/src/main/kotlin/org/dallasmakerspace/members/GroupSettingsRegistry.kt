package org.dallasmakerspace.members

/**
 * Registry of all known group settings. Adding a new setting here makes it available via the API.
 *
 * Settings are stored, audited, and editable by `MANAGE_GROUPS` holders. Each description notes
 * where (if anywhere) the setting is enforced.
 */
object GroupSettingsRegistry {

  val ALLOW_SELF_ADD_REMOVE =
      GroupSettingsKey<Boolean>(
          key = "allow_self_add_remove",
          description =
              "Whether members may join/leave this group themselves without admin action. " +
                  "Enforced by the /groups/{slug}/self routes (GroupSelfMembershipRoutes).",
          deserialize = { it.toBooleanStrict() },
          validate = { raw -> raw == "true" || raw == "false" },
      )

  val ALLOW_FOLLOW =
      GroupSettingsKey<Boolean>(
          key = "allow_follow",
          description =
              "Whether members may \"Follow\" this group for updates. NOT YET ENFORCED — " +
                  "framework only. Enforcement will plug into a future group-follow feature (not " +
                  "yet built) by checking this flag before exposing/accepting a Follow action.",
          deserialize = { it.toBooleanStrict() },
          validate = { raw -> raw == "true" || raw == "false" },
      )

  val ALL: List<GroupSettingsKey<*>> = listOf(ALLOW_SELF_ADD_REMOVE, ALLOW_FOLLOW)

  fun findByKey(key: String): GroupSettingsKey<*>? = ALL.find { it.key == key }
}
