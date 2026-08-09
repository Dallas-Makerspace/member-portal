package org.dallasmakerspace.members

/**
 * Registry of all known group settings. Adding a new setting here makes it available via the API.
 *
 * This is a framework-only feature today: the settings below are stored, audited, and editable by
 * `MANAGE_GROUPS` holders, but nothing in the codebase yet checks their values. Each description
 * notes the not-yet-built feature it is intended to gate.
 */
object GroupSettingsRegistry {

  val ALLOW_SELF_ADD_REMOVE =
      GroupSettingsKey<Boolean>(
          key = "allow_self_add_remove",
          description =
              "Whether members may add/remove themselves from this group without admin action. " +
                  "NOT YET ENFORCED — framework only. Enforcement will plug into a future " +
                  "self-service join/leave flow (not yet built) by checking this flag before " +
                  "allowing a member-initiated add/remove.",
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
