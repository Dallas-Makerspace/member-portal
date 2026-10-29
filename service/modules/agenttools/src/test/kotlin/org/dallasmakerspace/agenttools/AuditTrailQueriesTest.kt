package org.dallasmakerspace.agenttools

import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.DBMemberPortalConnection
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/** Runs the audit reads against an H2 copy of the tables they touch. */
class AuditTrailQueriesTest {
  companion object {
    private lateinit var db: Database

    @BeforeClass
    @JvmStatic
    fun createTables() {
      db =
          Database.connect(
              "jdbc:h2:mem:agenttools-audit;DB_CLOSE_DELAY=-1;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;IGNORECASE=TRUE",
              driver = "org.h2.Driver")
      DBMemberPortalConnection.db = db
      transaction(db) {
        exec(
            "CREATE TABLE IF NOT EXISTS profile (id INT AUTO_INCREMENT PRIMARY KEY, " +
                "username VARCHAR(100) NOT NULL UNIQUE, is_enabled BOOLEAN DEFAULT FALSE NOT NULL, " +
                "discourse_username VARCHAR(100) NULL, discourse_avatar_url VARCHAR(2083) NULL, " +
                "discord_userid VARCHAR(100) NULL, discord_username VARCHAR(100) NULL, " +
                "discord_avatar_url VARCHAR(2083) NULL, discord_webhook_id VARCHAR(100) NULL, " +
                "profile_photo_key VARCHAR(512) NULL, profile_photo_updated_at TIMESTAMP NULL, " +
                "attributes TEXT NULL)")
        exec(
            "CREATE TABLE IF NOT EXISTS `groups` (id INT AUTO_INCREMENT PRIMARY KEY, " +
                "name VARCHAR(255) NOT NULL, dn VARCHAR(512) NOT NULL, " +
                "created TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, object_guid VARCHAR(36) NULL, " +
                "status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL, archived_at TIMESTAMP NULL, " +
                "archived_by VARCHAR(100) NULL, archive_reason VARCHAR(255) NULL, archive_run_id BIGINT NULL)")
        exec(
            "CREATE TABLE IF NOT EXISTS group_history (id INT AUTO_INCREMENT PRIMARY KEY, " +
                "actor_id INT NOT NULL, member_id INT NOT NULL, group_id INT NOT NULL, " +
                "action_type INT NOT NULL, event_timestamp TIMESTAMP NOT NULL, " +
                "created TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, source VARCHAR(20) DEFAULT 'AD_WEBHOOK' NOT NULL)")
        exec(
            "CREATE TABLE IF NOT EXISTS activity_log (id INT AUTO_INCREMENT PRIMARY KEY, " +
                "source INT NOT NULL, actor_profile_row_id INT NULL, subject_profile_row_id INT NOT NULL, " +
                "event INT NOT NULL, attributes TEXT NULL, created TIMESTAMP NULL)")
      }
    }
  }

  private val queries = AuditTrailQueries()
  private val since = LocalDateTime.parse("2026-09-01T00:00:00")

  @Before
  fun reset() {
    DBMemberPortalConnection.db = db
    transaction(db) {
      listOf("activity_log", "group_history", "`groups`", "profile").forEach { exec("DELETE FROM $it") }
      exec("INSERT INTO profile (id, username) VALUES (1, 'asmith'), (2, 'bwhite'), (3, 'cgray')")
      exec("INSERT INTO `groups` (id, name, dn, status) VALUES (1, 'Laser Cutters', 'dn1', 'ACTIVE'), (2, 'Woodshop', 'dn2', 'ACTIVE'), (3, 'Laser Cutters', 'dn3', 'ARCHIVED')")
    }
  }

  private fun history(actor: Int, member: Int, group: Int, action: Int, at: String) =
      transaction(db) {
        exec("INSERT INTO group_history (actor_id, member_id, group_id, action_type, event_timestamp) VALUES ($actor, $member, $group, $action, '$at')")
      }

  private fun activityRow(subject: Int, actor: String, event: Int, created: String?) =
      transaction(db) {
        val at = created?.let { "'$it'" } ?: "NULL"
        exec("INSERT INTO activity_log (source, actor_profile_row_id, subject_profile_row_id, event, attributes, created) VALUES (1, $actor, $subject, $event, '{\"k\":\"v\"}', $at)")
      }

  @Test
  fun `member group changes carry the group name, newest first, inside the window and cap`() = runBlocking<Unit> {
    history(2, 1, 1, 0, "2026-10-01 10:00:00")
    history(2, 1, 2, 1, "2026-10-05 10:00:00")
    history(2, 1, 2, 0, "2026-08-01 10:00:00")
    history(2, 3, 2, 0, "2026-10-06 10:00:00")

    val rows = queries.groupChanges("asmith", null, since, 10)

    assertEquals(listOf("Woodshop", "Laser Cutters"), rows.map { it.group })
    assertEquals(listOf(false, true), rows.map { it.added })
    assertEquals("bwhite", rows[0].actor)
    assertEquals(LocalDateTime.parse("2026-10-05T10:00:00"), rows[0].atUtc)
    assertEquals(1, queries.groupChanges("asmith", null, since, 1).size)
  }

  @Test
  fun `a group's history skips archived groups and can narrow to one member`() = runBlocking<Unit> {
    history(2, 1, 1, 0, "2026-10-01 10:00:00")
    history(2, 3, 1, 0, "2026-10-02 10:00:00")
    history(2, 1, 3, 0, "2026-10-03 10:00:00")
    history(2, 1, 2, 0, "2026-10-04 10:00:00")

    assertEquals(listOf("cgray", "asmith"), queries.groupChanges(null, "Laser Cutters", since, 10).map { it.member })
    assertEquals(listOf("asmith"), queries.groupChanges("asmith", "Laser Cutters", since, 10).map { it.member })
  }

  @Test
  fun `activity is newest first, windowed, and keeps entries without an actor`() = runBlocking<Unit> {
    activityRow(1, "2", 13, "2026-10-01 10:00:00")
    activityRow(1, "NULL", 14, "2026-10-02 10:00:00")
    activityRow(1, "2", 13, "2026-08-01 10:00:00")
    activityRow(3, "2", 13, "2026-10-03 10:00:00")

    val rows = queries.activity("asmith", since, 10)

    assertEquals(listOf(14, 13), rows.map { it.event })
    assertNull(rows[0].actor)
    assertEquals("bwhite", rows[1].actor)
    assertEquals("""{"k":"v"}""", rows[1].attributes)
    assertEquals(1, queries.activity("asmith", since, 1).size)
  }

  @Test
  fun `hasProfile never inserts a profile`() = runBlocking<Unit> {
    assertTrue(queries.hasProfile("asmith"))
    assertFalse(queries.hasProfile("ghost"))
    assertFalse(queries.hasProfile("ghost"))
    assertEquals(3, transaction(db) { exec("SELECT COUNT(*) FROM profile") { it.next(); it.getInt(1) } })
  }
}
