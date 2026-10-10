package org.dallasmakerspace.doorcontroller

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

/** Exposed table definition for AccessControl.controllers */
@Suppress("MagicNumber")
object ControllersTable : Table("AccessControl.controllers") {
  val id = integer("id").autoIncrement()
  val name = varchar("name", 255)
  val typeId = integer("typeId")
  val sn = long("sn").uniqueIndex()
  val ipAddress = varchar("ipAddress", 45)

  override val primaryKey = PrimaryKey(id)
}

/** Exposed table definition for AccessControl.events */
@Suppress("MagicNumber")
object DoorEventsTable : Table("AccessControl.events") {
  val id = integer("id").autoIncrement()
  val controllerId = integer("controllerId")
  val slotNumber = integer("slotNumber")
  val dbindex = integer("dbindex")
  val dbindextype = integer("dbindextype").default(0)
  val accessRequest = integer("accessRequest")
  val granted = integer("granted")
  val status = integer("status")
  val options = integer("options")
  val cardNumber = varchar("cardNumber", 64)
  val cardCN = varchar("cardCN", 64).nullable()
  val userId = integer("userId").nullable()
  val date = datetime("date")
  val rawData = text("rawData")
  val created = datetime("created").clientDefault { java.time.LocalDateTime.now() }
  val note = varchar("note", 255)

  override val primaryKey = PrimaryKey(id)

  init {
    uniqueIndex("uniqueindex", controllerId, slotNumber, dbindex, date)
  }
}
