package org.dallasmakerspace.models

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/** Represents an Active Directory group in the system. */
@Serializable
data class Group(
    val id: Int,
    val name: String,
    val dn: String,
    val created: LocalDateTime,
    val objectGuid: String? = null,
    val status: String = "ACTIVE",
    val archivedAt: LocalDateTime? = null,
)
