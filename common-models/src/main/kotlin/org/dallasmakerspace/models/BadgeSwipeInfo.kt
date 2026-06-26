package org.dallasmakerspace.models

import kotlinx.serialization.Serializable

@Serializable
data class BadgeSwipeInfo(
    val swipeTime: String,
    val doorName: String,
    val granted: Boolean,
)
