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
              "archived_by VARCHAR(100) NULL, " +
              "archive_reason VARCHAR(255) NULL, " +
              "archive_run_id BIGINT NULL, " +
              "active_name VARCHAR(255) GENERATED ALWAYS AS " +
              "(CASE WHEN status = 'ARCHIVED' THEN NULL ELSE name END), " +
              "active_dn VARCHAR(512) GENERATED ALWAYS AS " +
              "(CASE WHEN status = 'ARCHIVED' THEN NULL ELSE dn END), " +
              "CONSTRAINT groups_active_name_UNQ UNIQUE (active_name), " +
              "CONSTRAINT groups_active_dn_UNQ UNIQUE (active_dn), " +
              "CONSTRAINT groups_object_guid_UNQ UNIQUE (object_guid))")
    }
    database
  }

  fun reset() {
    DBMemberPortalConnection.db = db
    transaction(db) { exec("DELETE FROM `groups`") }
  }

  fun insert(name: String, dn: String, guid: String? = null, status: String = "ACTIVE"): Int =
      transaction(db) {
        val guidSql = guid?.let { "'$it'" } ?: "NULL"
        exec(
            "INSERT INTO `groups` (name, dn, object_guid, status) " +
                "VALUES ('$name', '$dn', $guidSql, '$status')")
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

  fun status(id: Int): String? =
      transaction(db) {
        exec("SELECT status FROM `groups` WHERE id = $id") {
              val found = mutableListOf<String>()
              if (it.next()) found.add(it.getString(1))
              found
            }
            ?.firstOrNull()
      }

  /** Returns (archived_by, archive_reason, archive_run_id) for the row. */
  fun archiveMeta(id: Int): Triple<String?, String?, Long?>? =
      transaction(db) {
        exec("SELECT archived_by, archive_reason, archive_run_id FROM `groups` WHERE id = $id") {
              val found = mutableListOf<Triple<String?, String?, Long?>>()
              if (it.next()) {
                val runId = it.getObject(3)?.let { n -> (n as Number).toLong() }
                found.add(Triple(it.getString(1), it.getString(2), runId))
              }
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
