package org.dallasmakerspace.models

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Represents the type of action performed on a group membership. Maps to Active Directory event
 * IDs: 4728/4732/4756 for member added, 4729/4733/4757 for member removed.
 */
@Serializable
enum class ActionType(val value: Int) {
  ADD_USER(0),
  REMOVE_USER(1);

  companion object {
    fun fromValue(value: Int): ActionType =
        entries.find { it.value == value }
            ?: throw IllegalArgumentException("Unknown ActionType value: $value")

    /**
     * Maps Active Directory event IDs to ActionType, covering all three security group scopes:
     * global (4728 added / 4729 removed), domain-local (4732 added / 4733 removed), and universal
     * (4756 added / 4757 removed). Throws on unrecognized event IDs rather than guessing.
     */
    fun fromEventId(eventId: String): ActionType =
        when (eventId) {
          "4728",
          "4732",
          "4756" -> ADD_USER
          "4729",
          "4733",
          "4757" -> REMOVE_USER
          else -> throw IllegalArgumentException("Unknown group membership event ID: $eventId")
        }
  }
}

/**
 * Represents a group membership history record in the system. Tracks changes to group memberships
 * by recording who made the change, which member was affected, and when.
 */
@Serializable
data class GroupHistory(
    val actorUsername: String,
    val memberUsername: String,
    val actionType: ActionType,
    val eventTimestamp: LocalDateTime,
    val actorDisplayName: String? = null,
    val memberDisplayName: String? = null,
)
