package org.dallasmakerspace.smartwaiver

import org.jetbrains.exposed.sql.Table

object SmartWaiverTable : Table("`dms-makermanager`.smartwaiver_data") {
  val firstName = varchar("firstName", 100)
  val lastName = varchar("lastName", 100)
  val email = varchar("email", 100).nullable()
  val birthDate = varchar("birthDate", 100)
  val dateCompleted = varchar("dateCompleted", 100)
  val waiverId = varchar("waiverId", 100)
  val waiverStatus = varchar("waiverStatus", 100)
  val documentTitle = varchar("documentTitle", 100).nullable()
  val checkinCount = integer("checkinCount")
  val referralType = varchar("referralType", 100).nullable()
  val referralSource = varchar("referralSource", 100).nullable()
  val requestedWaiverEmail = integer("requestedWaiverEmail")
  val guardianFirstName = varchar("guardianFirstName", 100).nullable()
  val guardianLastName = varchar("guardianLastName", 100).nullable()
}
