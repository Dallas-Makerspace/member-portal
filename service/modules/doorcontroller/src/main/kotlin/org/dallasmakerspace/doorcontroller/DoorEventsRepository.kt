package org.dallasmakerspace.doorcontroller

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.db.master.suspendTransaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select

/** Repository for managing door badge swipe events in the AccessControl.events table */
@Singleton
class DoorEventsRepository @Inject constructor() {

  // Cache controller serial number -> database ID mappings
  private val controllerIdCache = mutableMapOf<Long, Int>()

  /**
   * Get or create controller ID by serial number Results are cached to avoid repeated database
   * lookups
   *
   * @param serialNumber Controller serial number
   * @param name Controller name
   * @param ipAddress Controller IP address
   * @return Controller database ID
   */
  suspend fun getOrCreateControllerId(serialNumber: Long, name: String, ipAddress: String): Int {
    // Check cache first
    controllerIdCache[serialNumber]?.let {
      return it
    }

    // Look up in database
    return suspendTransaction {
      // Try to find existing controller
      val existingId =
          ControllersTable.select { ControllersTable.sn eq serialNumber }
              .map { it[ControllersTable.id] }
              .firstOrNull()

      if (existingId != null) {
        controllerIdCache[serialNumber] = existingId
        return@suspendTransaction existingId
      }

      // Insert new controller (typeId = 2 for HTTP controllers, based on Python code)
      val newId =
          ControllersTable.insert {
            it[ControllersTable.name] = name
            it[typeId] = 2
            it[sn] = serialNumber
            it[ControllersTable.ipAddress] = ipAddress
          } get ControllersTable.id

      controllerIdCache[serialNumber] = newId
      newId
    }
  }

  /**
   * Returns the max dbindex seen per (controllerId, slotNumber) for the given controller IDs. Used
   * to filter out already-persisted events before inserting.
   *
   * @param controllerIds List of controller database IDs to query
   * @return Map of (controllerId, slotNumber) -> max dbindex
   */
  suspend fun getMaxDbindexPerSlot(controllerIds: List<Int>): Map<Pair<Int, Int>, Int> {
    if (controllerIds.isEmpty()) return emptyMap()

    val idList = controllerIds.joinToString(",")
    val sql =
        "SELECT controllerId, slotNumber, MAX(dbindex) AS maxDbindex " +
            "FROM `AccessControl`.`events` " +
            "WHERE controllerId IN ($idList) " +
            "GROUP BY controllerId, slotNumber"

    return suspendTransaction {
      val result = mutableMapOf<Pair<Int, Int>, Int>()
      exec(sql) { rs ->
        while (rs.next()) {
          val key = Pair(rs.getInt("controllerId"), rs.getInt("slotNumber"))
          result[key] = rs.getInt("maxDbindex")
        }
      }
      result
    }
  }

  /**
   * Insert a single door event into the database Uses INSERT IGNORE to skip duplicate events based
   * on the unique constraint
   *
   * @param event Door event to insert
   * @return Number of rows inserted (0 if duplicate, 1 if successful)
   */
  suspend fun insertEvent(event: DoorEvent): Int {
    return suspendTransaction {
      DoorEventsTable.insertIgnore {
            it[controllerId] = event.controllerId
            it[slotNumber] = event.slotNumber
            it[dbindex] = event.dbindex
            it[dbindextype] = event.dbindextype
            it[accessRequest] = event.accessRequest
            it[granted] = if (event.granted) 1 else 0
            it[status] = event.status
            it[options] = event.options
            it[cardNumber] = event.cardNumber
            it[cardCN] = event.cardCN
            it[userId] = event.userId
            it[date] = event.date
            it[rawData] = event.rawData
            it[note] = event.note
          }
          .insertedCount
    }
  }

  /**
   * Returns the last [limit] badge swipes for the given username, newest first.
   *
   * @param username MakerManager username (must be alphanumeric/dash/underscore)
   * @param limit Maximum number of swipes to return (default 5)
   */
  suspend fun getRecentSwipesForUser(
      username: String,
      limit: Int = 5,
  ): List<org.dallasmakerspace.models.BadgeSwipeInfo> {
    require(username.matches(Regex("^[a-zA-Z0-9_-]+$"))) { "Invalid username" }
    val sql =
        """
        SELECT
            DATE_FORMAT(CONVERT_TZ(e.date, 'UTC', 'America/Chicago'), '%a, %b %e, %Y at %l:%i %p') AS swipe_time,
            s.name AS door_name,
            e.granted AS granted
        FROM `AccessControl`.events e
        LEFT JOIN `AccessControl`.slots s ON e.controllerId = s.controllerId AND e.slotNumber = s.slotNumber
        LEFT JOIN `dms-makermanager`.users u ON e.userId = u.id
        WHERE u.username = '$username'
        ORDER BY e.date DESC
        LIMIT $limit
        """
            .trimIndent()
    return suspendTransaction {
      val result = mutableListOf<org.dallasmakerspace.models.BadgeSwipeInfo>()
      exec(sql) { rs ->
        while (rs.next()) {
          result.add(
              org.dallasmakerspace.models.BadgeSwipeInfo(
                  swipeTime = rs.getString("swipe_time") ?: "",
                  doorName = rs.getString("door_name") ?: "Unknown",
                  granted = rs.getInt("granted") == 1,
              ))
        }
      }
      result
    }
  }

  /**
   * Insert multiple door events in a batch operation Uses INSERT IGNORE to skip duplicate events
   * based on the unique constraint
   *
   * @param events List of door events to insert
   * @return Number of rows inserted
   */
  suspend fun insertEvents(events: List<DoorEvent>): Int {
    if (events.isEmpty()) return 0

    return suspendTransaction {
      var insertedCount = 0
      events.forEach { event ->
        val result =
            DoorEventsTable.insertIgnore {
              it[controllerId] = event.controllerId
              it[slotNumber] = event.slotNumber
              it[dbindex] = event.dbindex
              it[dbindextype] = event.dbindextype
              it[accessRequest] = event.accessRequest
              it[granted] = if (event.granted) 1 else 0
              it[status] = event.status
              it[options] = event.options
              it[cardNumber] = event.cardNumber
              it[cardCN] = event.cardCN
              it[userId] = event.userId
              it[date] = event.date
              it[rawData] = event.rawData
              it[note] = event.note
            }
        insertedCount += result.insertedCount
      }
      insertedCount
    }
  }
}
