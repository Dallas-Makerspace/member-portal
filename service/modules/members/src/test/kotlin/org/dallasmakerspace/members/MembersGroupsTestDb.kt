package org.dallasmakerspace.members

import org.dallasmakerspace.core.DBMemberPortalConnection
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

/** H2 stand-in for the groups table, mirroring create.sql (case-insensitive like general_ci). */
object MembersGroupsTestDb {
  val db: Database by lazy {
    val database =
        Database.connect(
            "jdbc:h2:mem:members-groups;DB_CLOSE_DELAY=-1;MODE=MariaDB;" +
                "DATABASE_TO_LOWER=TRUE;IGNORECASE=TRUE",
            driver = "org.h2.Driver")
    transaction(database) {
      exec(
          "CREATE TABLE IF NOT EXISTS `groups` (" +
              "id INT AUTO_INCREMENT PRIMARY KEY, " +
              "name VARCHAR(255) NOT NULL, " +
              "dn VARCHAR(512) NOT NULL, " +
              "created TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, " +
              "object_guid VARCHAR(36) NULL, " +
              "status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL, " +
              "archived_at TIMESTAMP NULL, " +
              "CONSTRAINT groups_name_UNQ UNIQUE (name), " +
              "CONSTRAINT groups_dn_UNQ UNIQUE (dn), " +
              "CONSTRAINT groups_object_guid_UNQ UNIQUE (object_guid))")
    }
    database
  }

  fun reset() {
    DBMemberPortalConnection.db = db
    transaction(db) { exec("DELETE FROM `groups`") }
  }

  fun insert(name: String, dn: String, guid: String? = null): Int =
      transaction(db) {
        val guidSql = guid?.let { "'$it'" } ?: "NULL"
        exec("INSERT INTO `groups` (name, dn, object_guid) VALUES ('$name', '$dn', $guidSql)")
        exec("SELECT MAX(id) FROM `groups`") {
          it.next()
          it.getInt(1)
        }!!
      }

  /** Returns (name, dn, object_guid) for the row, or null. */
  fun row(id: Int): Triple<String, String, String?>? =
      transaction(db) {
        exec("SELECT name, dn, object_guid FROM `groups` WHERE id = $id") {
              val found = mutableListOf<Triple<String, String, String?>>()
              if (it.next()) found.add(Triple(it.getString(1), it.getString(2), it.getString(3)))
              found
            }
            ?.firstOrNull()
      }

  fun count(): Int =
      transaction(db) {
        exec("SELECT COUNT(*) FROM `groups`") {
          it.next()
          it.getInt(1)
        }!!
      }
}
