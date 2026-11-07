package org.dallasmakerspace.doorcontroller

import java.time.Instant

/** One badge swipe at a door; [username] is null when the badge isn't linked to a member. */
data class DoorSwipe(
    val time: Instant,
    val door: String,
    val granted: Boolean,
    val username: String?,
    val badgeTail: String,
)

/** A door as a controller serial number and door number, with its friendly [name]. */
data class DoorPlace(val name: String, val serial: Long, val slot: Int)
