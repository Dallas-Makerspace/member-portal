package org.dallasmakerspace.members

import org.dallasmakerspace.models.CommunicationPreference

/**
 * Registry of all known member settings. Adding a new setting here makes it available via the API.
 */
object MemberSettingsRegistry {

  val COMMUNICATION_PREFERENCE =
      MemberSettingsKey<CommunicationPreference?>(
          key = "communication_preference",
          description =
              "How other members may contact this member. Unset by default; Email and " +
                  "SMS are opt-in because they make contact details visible to other members.",
          deserialize = { raw ->
            if (raw.isBlank()) {
              null
            } else {
              CommunicationPreference.fromValue(raw)
            }
          },
          validate = { raw ->
            raw.isBlank() || CommunicationPreference.entries.any { it.name == raw }
          },
      )

  val ALL: List<MemberSettingsKey<*>> = listOf(COMMUNICATION_PREFERENCE)

  fun findByKey(key: String): MemberSettingsKey<*>? = ALL.find { it.key == key }
}
