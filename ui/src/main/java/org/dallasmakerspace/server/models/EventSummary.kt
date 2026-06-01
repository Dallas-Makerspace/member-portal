package org.dallasmakerspace.server.models

import kotlinx.serialization.Serializable

@Serializable
data class EventSummary(
    val id: Int,
    val name: String,
    val eventStart: String,
    val status: String,
    val organizerUsername: String? = null,
    val fulfillsPrerequisiteGroup: String? = null,
)
