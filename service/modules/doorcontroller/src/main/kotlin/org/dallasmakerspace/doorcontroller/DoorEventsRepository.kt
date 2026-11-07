package org.dallasmakerspace.doorcontroller

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.db.master.suspendTransaction
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.select

/** Repository for managing door badge swipe events in the AccessControl.events table */
@Singleton
class DoorEventsRepository @Inject constructor() {
  private companion object {
    const val DEFAULT_SWIPE_LIMIT = 200
    const val BADGE_TAIL = 4
    val USERNAME = Regex("^[a-zA-Z0-9_.-]+$")
  }

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
   * Only scraped rows count: the imported ones (dbindextype 0) are numbered by the old system, far
   * above what the controllers now count, and would hide every new event.
   */
  internal fun maxDbindexSql(controllerIds: List<Int>): String =
      "SELECT controllerId, slotNumber, MAX(dbindex) AS maxDbindex " +
          "FROM `AccessControl`.`events` " +
          "WHERE controllerId IN (${controllerIds.joinToString(",")}) AND dbindextype = 1 " +
          "GROUP BY controllerId, slotNumber"

  /**
   * Returns the max dbindex seen per (controllerId, slotNumber) for the given controller IDs. Used
   * to filter out already-persisted events before inserting.
   *
   * @param controllerIds List of controller database IDs to query
   * @return Map of (controllerId, slotNumber) -> max dbindex
   */
  suspend fun getMaxDbindexPerSlot(controllerIds: List<Int>): Map<Pair<Int, Int>, Int> {
    if (controllerIds.isEmpty()) return emptyMap()

    val sql = maxDbindexSql(controllerIds)

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
   * Swipes at the named [doors] (`AccessControl.slots` names) from [from] to [to], oldest first, at
   * most [limit]. [usernames] keeps only those members' swipes.
   */
  @Suppress("LongParameterList")
  suspend fun searchSwipes(
      doors: Collection<String>,
      from: Instant,
      to: Instant,
      usernames: Set<String>? = null,
      deniedOnly: Boolean = false,
      limit: Int = DEFAULT_SWIPE_LIMIT,
  ): List<DoorSwipe> {
    if (doors.isEmpty() || (usernames != null && usernames.isEmpty())) return emptyList()
    usernames?.forEach { require(it.matches(USERNAME)) { "Invalid username" } }
    val start = LocalDateTime.ofInstant(from, ZoneOffset.UTC)
    val end = LocalDateTime.ofInstant(to, ZoneOffset.UTC)

    return suspendTransaction {
      val userIds = usernames?.let { userIdsOf(it) }
      if (userIds != null && userIds.isEmpty()) return@suspendTransaction emptyList()
      val rows =
          atDoors()
              .select {
                var match =
                    (DoorSlotsTable.name inList doors) and (DoorEventsTable.date greaterEq start) and (DoorEventsTable.date lessEq end)
                if (deniedOnly) match = match and (DoorEventsTable.granted eq 0)
                if (userIds != null) match = match and (DoorEventsTable.userId inList userIds)
                match
              }
              .orderBy(DoorEventsTable.date to SortOrder.ASC)
              .limit(limit)
              .map {
                RawSwipe(
                    it[DoorEventsTable.date],
                    it[DoorSlotsTable.name],
                    it[DoorEventsTable.granted] == 1,
                    it[DoorEventsTable.userId],
                    it[DoorEventsTable.cardNumber])
              }
      val names = usernamesOf(rows.mapNotNull { it.userId }.toSet())
      rows.map {
        DoorSwipe(
            it.date.toInstant(ZoneOffset.UTC),
            it.door,
            it.granted,
            it.userId?.let(names::get),
            it.card.takeLast(BADGE_TAIL))
      }
    }
  }

  /** The newest event of any door on the controllers of [doors], or null if there is none. */
  suspend fun latestEventTime(doors: Collection<String>): Instant? {
    if (doors.isEmpty()) return null
    return suspendTransaction {
      val controllers =
          DoorSlotsTable.select { DoorSlotsTable.name inList doors }.map { it[DoorSlotsTable.controllerId] }
      if (controllers.isEmpty()) return@suspendTransaction null
      DoorEventsTable.slice(DoorEventsTable.date.max())
          .select { DoorEventsTable.controllerId inList controllers }
          .firstOrNull()
          ?.get(DoorEventsTable.date.max())
          ?.toInstant(ZoneOffset.UTC)
    }
  }

  private fun atDoors() =
      DoorEventsTable.join(DoorSlotsTable, JoinType.INNER) {
        (DoorEventsTable.controllerId eq DoorSlotsTable.controllerId) and
            (DoorEventsTable.slotNumber eq DoorSlotsTable.slotNumber)
      }

  private class RawSwipe(
      val date: LocalDateTime,
      val door: String,
      val granted: Boolean,
      val userId: Int?,
      val card: String,
  )

  private fun Transaction.userIdsOf(usernames: Set<String>): List<Int> {
    val list = usernames.joinToString(",") { "'${it.replace("'", "''")}'" }
    return exec("SELECT id FROM `dms-makermanager`.users WHERE username IN ($list)") { rs ->
      buildList { while (rs.next()) add(rs.getInt("id")) }
    } ?: emptyList()
  }

  private fun Transaction.usernamesOf(userIds: Set<Int>): Map<Int, String> {
    if (userIds.isEmpty()) return emptyMap()
    val list = userIds.joinToString(",")
    return exec("SELECT id, username FROM `dms-makermanager`.users WHERE id IN ($list)") { rs ->
      buildMap { while (rs.next()) put(rs.getInt("id"), rs.getString("username")) }
    } ?: emptyMap()
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
