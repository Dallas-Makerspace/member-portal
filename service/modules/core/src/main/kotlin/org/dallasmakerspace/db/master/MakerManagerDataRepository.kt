package org.dallasmakerspace.db.master

import java.sql.ResultSet
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import org.dallasmakerspace.core.DBMasterConnection
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

// Data class to represent a user in MakerManager
data class MakerManagerUser(
    val username: String,
    val relatedUsername: String?,
    val makerManagerUserId: Int,
    val whmcsUserId: Int,
    val whmcsRealUserId: Int,
    val adActive: Boolean
)

// New data class for the getAllUsers query
data class MakerManagerUserInfo(
    val makerManagerId: Int,
    val firstName: String?,
    val lastName: String?,
    val username: String,
    val email: String?,
    val whmcsUserId: Int,
    val adActive: Boolean,
    val phone: String?,
    val badgeNumber: String?,
    val isPrimaryAccount: Boolean,
)

/** An active MakerManager user and the email on file; [toString] leaves the email out. */
data class MakerManagerEmailMatch(
    val makerManagerId: Int,
    val username: String,
    val email: String,
    val created: LocalDateTime?,
) {
  override fun toString() = "MakerManagerEmailMatch(id=$makerManagerId, username=$username)"
}

@Singleton
class MakerManagerDataRepository @Inject constructor() {
  /** Active users whose trimmed, lowercased email equals [normalizedEmail]. */
  suspend fun findActiveUsersByEmail(normalizedEmail: String): List<MakerManagerEmailMatch> =
      suspendTransaction {
        val matches = mutableListOf<MakerManagerEmailMatch>()
        this.exec(
            "SELECT id, username, email, created FROM `dms-makermanager`.users " +
                "WHERE ad_active = 1 AND LOWER(TRIM(email)) = ?",
            args = listOf(VarCharColumnType() to normalizedEmail)) { resultSet: ResultSet ->
              while (resultSet.next()) matches.add(resultSet.toEmailMatch())
              matches
            }
        matches
      }

  /** Every active user with an email, oldest account first. */
  suspend fun getActiveUsersWithEmail(): List<MakerManagerEmailMatch> = suspendTransaction {
    val users = mutableListOf<MakerManagerEmailMatch>()
    this.exec(
        """
        SELECT id, username, email, created FROM `dms-makermanager`.users
        WHERE ad_active = 1 AND email IS NOT NULL AND TRIM(email) <> ''
        ORDER BY created IS NULL, created ASC, id ASC
      """
            .trimIndent()) { resultSet: ResultSet ->
          while (resultSet.next()) users.add(resultSet.toEmailMatch())
          users
        }
    users
  }

  private fun ResultSet.toEmailMatch() =
      MakerManagerEmailMatch(
          makerManagerId = getInt("id"),
          username = getString("username"),
          email = getString("email"),
          created = getTimestamp("created")?.toLocalDateTime())

  /**
   * Get related accounts for a list of usernames. This includes the account(s) being queried and
   * any related accounts like the primary account or addon accounts.
   *
   * @param usernames List of usernames to get related accounts for
   * @return Map of usernames to related accounts
   */
  suspend fun getRelatedAccountsMap(usernames: List<String>): Map<String, List<MakerManagerUser>> {
    val results = suspendTransaction {
      // Convert list of usernames to SQL-safe format
      val usernamesList = usernames.joinToString(",") { "'${it.replace("'", "''")}'" }

      val query =
          """
                    SELECT 
                        u1.username as main_username,
                        u2.username as related_username,
                        u2.user_id,
                        u2.whmcs_user_id,
                        u2.whmcs_real_user_id,
                        u2.ad_active
                    FROM `dms-makermanager`.users u1
                    INNER JOIN `dms-makermanager`.users u2 ON u1.whmcs_user_id = u2.whmcs_user_id
                    WHERE u1.username IN ($usernamesList)
                """
              .trimIndent()

      val makerManagerUsers = mutableListOf<MakerManagerUser>()

      this.exec(query) { resultSet: ResultSet ->
        while (resultSet.next()) {
          makerManagerUsers.add(
              MakerManagerUser(
                  username = resultSet.getString("main_username"),
                  relatedUsername = resultSet.getString("related_username"),
                  makerManagerUserId = resultSet.getInt("user_id"),
                  whmcsUserId = resultSet.getInt("whmcs_user_id"),
                  whmcsRealUserId = resultSet.getInt("whmcs_real_user_id"),
                  adActive = resultSet.getInt("ad_active") == 1))
        }
        makerManagerUsers
      } ?: emptyList()

      makerManagerUsers
    }

    // Convert the results to a map, maintaining the same return type
    return results.groupBy { it.username }
  }

  /**
   * Get user IDs for multiple badge numbers at a specific date/time Checks badge_histories for
   * historical assignment, falls back to current badges
   *
   * @param badgeNumbersWithDates Map of badge numbers to their swipe dates
   * @return Map of badge numbers to their user IDs (null if not found)
   */
  suspend fun getUserIdsByBadgeNumbers(
      badgeNumbersWithDates: Map<String, String>
  ): Map<String, Int?> {
    if (badgeNumbersWithDates.isEmpty()) return emptyMap()

    return suspendTransaction {
      val results = mutableMapOf<String, Int?>()

      badgeNumbersWithDates.forEach { (badgeNumber, swipeDate) ->
        // Escape single quotes in parameters
        val safeBadge = badgeNumber.replace("'", "''")
        val safeDate = swipeDate.replace("'", "''")

        // Query 1: Check badge_histories for historical assignment
        val historyQuery =
            """
          SELECT last.user_id
          FROM (
              SELECT b.user_id, bh.changed_to
              FROM `dms-makermanager`.badge_histories as bh
              LEFT JOIN `dms-makermanager`.badges as b on b.id = bh.badge_id
              WHERE TRIM(LEADING '0' FROM bh.badge_number) = TRIM(LEADING '0' FROM '$safeBadge')
              AND bh.created <= '$safeDate'
              ORDER BY bh.created DESC
              LIMIT 1
          ) as last
          WHERE last.changed_to = 'active'
        """
                .trimIndent()

        val historyResults =
            this.exec(historyQuery) { resultSet: ResultSet ->
              buildList {
                if (resultSet.next()) {
                  add(resultSet.getInt("user_id"))
                }
              }
            } ?: emptyList()

        var userId: Int? = historyResults.firstOrNull()

        // Query 2: Fallback to current badges table if no history found
        if (userId == null) {
          val fallbackQuery =
              """
            SELECT b.user_id
            FROM `dms-makermanager`.badges as b
            WHERE TRIM(LEADING '0' FROM b.number) = TRIM(LEADING '0' FROM '$safeBadge')
            LIMIT 1
          """
                  .trimIndent()

          val fallbackResults =
              this.exec(fallbackQuery) { resultSet: ResultSet ->
                buildList {
                  if (resultSet.next()) {
                    add(resultSet.getInt("user_id"))
                  }
                }
              } ?: emptyList()

          userId = fallbackResults.firstOrNull()
        }

        results[badgeNumber] = userId
      }

      results
    }
  }

  /**
   * Get all users from MakerManager database
   *
   * @return List of MakerManagerUserInfo containing all users
   */
  suspend fun getAllUsers(): List<MakerManagerUserInfo> {
    return suspendTransaction {
      // Join at most one badge per user (users can have multiple badge rows, e.g. an old
      // active badge plus a newer unassigned one) — prefer active status, then highest number
      val query =
          """
        SELECT u.id as makermanager_id, u.first_name, u.last_name, u.username, u.email, u.whmcs_user_id, u.ad_active,
        u.whmcs_real_user_id, REGEXP_REPLACE(u.phone, '[^0-9]+', '') as phone, b.number as badge_number
        FROM `dms-makermanager`.users u
        LEFT JOIN `dms-makermanager`.badges b ON b.id = (
          SELECT b2.id FROM `dms-makermanager`.badges b2
          WHERE b2.user_id = u.id
          ORDER BY (b2.status = 'active') DESC, b2.`number` DESC
          LIMIT 1
        )
        ORDER BY u.ad_active DESC, b.`number` DESC
      """
              .trimIndent()

      val users = mutableListOf<MakerManagerUserInfo>()

      this.exec(query) { resultSet: ResultSet ->
        while (resultSet.next()) {
          users.add(
              MakerManagerUserInfo(
                  makerManagerId = resultSet.getInt("makermanager_id"),
                  firstName = resultSet.getString("first_name"),
                  lastName = resultSet.getString("last_name"),
                  username = resultSet.getString("username"),
                  email = resultSet.getString("email"),
                  whmcsUserId = resultSet.getInt("whmcs_user_id"),
                  adActive = resultSet.getInt("ad_active") == 1,
                  phone = resultSet.getString("phone"),
                  badgeNumber = resultSet.getString("badge_number"),
                  isPrimaryAccount = resultSet.getObject("whmcs_real_user_id") == null))
        }
        users
      } ?: emptyList()

      users
    }
  }
}

// Keep the transaction helper function
suspend fun <T> suspendTransaction(block: Transaction.() -> T): T =
    newSuspendedTransaction(Dispatchers.IO, statement = block, db = DBMasterConnection.db)
