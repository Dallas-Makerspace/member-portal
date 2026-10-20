package org.dallasmakerspace.models

import kotlinx.serialization.Serializable

/**
 * Represents the preferred way other members may contact a member. This is about member-to-member
 * contact, not official DMS communications.
 *
 * Email and SMS are opt-in: choosing them makes the member's contact details (personal email /
 * phone number) visible to other members, so the default is "unset" (absent) rather than a specific
 * method. [NONE] is a deliberate "don't list a contact method" answer, distinct from unset.
 */
@Serializable
enum class CommunicationPreference(val label: String) {
  EMAIL("Email"),
  DISCOURSE("Talk"),
  DISCORD("Discord"),
  SMS("Text/SMS"),
  NONE("None listed");

  companion object {
    fun fromValue(value: String): CommunicationPreference =
        entries.find { it.name == value }
            ?: throw IllegalArgumentException("Unknown CommunicationPreference value: $value")
  }
}
