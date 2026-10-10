package org.dallasmakerspace.members

import kotlinx.serialization.Serializable

@Serializable
data class ScannerStatusResponse(
    val username: String,
    val firstName: String?,
    val lastName: String?,
    val isActive: Boolean,
    val daysInCurrentStatus: Int?,
    val regDate: String?,
    val phone: String?,
    val email: String?,
    val discourseUsername: String?,
    val discordUserId: String?,
)
