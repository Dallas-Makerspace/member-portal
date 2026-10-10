package org.dallasmakerspace.doorcontroller

import java.time.LocalDateTime

/** Represents a door badge swipe event from an RFID controller */
data class DoorEvent(
    val controllerId: Int,
    val slotNumber: Int,
    val dbindex: Int,
    val dbindextype: Int = 0,
    val accessRequest: Int = 0,
    val granted: Boolean,
    val status: Int,
    val options: Int,
    val cardNumber: String,
    val cardCN: String,
    val userId: Int? = null,
    val date: LocalDateTime,
    val rawData: String,
    val note: String
)
