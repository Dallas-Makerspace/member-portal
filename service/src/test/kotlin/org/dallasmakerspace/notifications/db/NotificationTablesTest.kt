package org.dallasmakerspace.notifications.db

import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.BeforeClass
import org.junit.Test

/** Validates the Exposed table definitions against H2 in MariaDB mode (mirrors prod DDL). */
class NotificationTablesTest {

  companion object {
    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      Database.connect(
          "jdbc:h2:mem:notifications;DB_CLOSE_DELAY=-1;MODE=MariaDB;DATABASE_TO_LOWER=TRUE",
          driver = "org.h2.Driver")
      transaction {
        SchemaUtils.create(
            NotificationsTable,
            NotificationPreferencesTable,
            NotificationSuppressionsTable,
            ProfileSubscriptionsTable)
      }
    }
  }

  @Test
  fun `notifications insert and read with auto-increment id`() {
    transaction {
      val id =
          NotificationsTable.insert {
                it[profileId] = 42
                it[topic] = "SYSTEM"
                it[channel] = "EMAIL"
                it[aggregation] = "IMMEDIATE"
                it[templateName] = "system-test"
                it[payload] = """{"message":"hi"}"""
                it[subject] = "Test"
                it[body] = "Body"
                it[status] = "QUEUED"
                it[attempts] = 0
                it[createdAt] = LocalDateTime.of(2026, 8, 2, 12, 0)
                it[updatedAt] = LocalDateTime.of(2026, 8, 2, 12, 0)
              }
              .get(NotificationsTable.idColumn)
      assertTrue(id.value > 0)

      val row = NotificationsTable.select { NotificationsTable.idColumn eq id.value }.first()
      assertEquals(42, row[NotificationsTable.profileId])
      assertEquals("QUEUED", row[NotificationsTable.status])
      assertEquals(0, row[NotificationsTable.attempts])
      assertNull(row[NotificationsTable.dedupeKey])
      assertNull(row[NotificationsTable.sentAt])
    }
  }

  @Test
  fun `dedupe key unique index rejects duplicates`() {
    transaction {
      NotificationsTable.insert {
        it[profileId] = 43
        it[topic] = "SYSTEM"
        it[channel] = "EMAIL"
        it[aggregation] = "IMMEDIATE"
        it[templateName] = "system-test"
        it[subject] = "Test"
        it[body] = "Body"
        it[status] = "QUEUED"
        it[attempts] = 0
        it[dedupeKey] = "dup-key"
        it[createdAt] = LocalDateTime.of(2026, 8, 2, 12, 0)
        it[updatedAt] = LocalDateTime.of(2026, 8, 2, 12, 0)
      }
      assertFailsWith<Exception> {
        NotificationsTable.insert {
          it[profileId] = 43
          it[topic] = "SYSTEM"
          it[channel] = "EMAIL"
          it[aggregation] = "IMMEDIATE"
          it[templateName] = "system-test"
          it[subject] = "Test 2"
          it[body] = "Body 2"
          it[status] = "QUEUED"
          it[attempts] = 0
          it[dedupeKey] = "dup-key"
          it[createdAt] = LocalDateTime.of(2026, 8, 2, 12, 0)
          it[updatedAt] = LocalDateTime.of(2026, 8, 2, 12, 0)
        }
      }
    }
  }

  @Test
  fun `preferences table enforces composite primary key`() {
    transaction {
      NotificationPreferencesTable.insert {
        it[profileId] = 44
        it[topic] = "EVENT_ANNOUNCEMENT"
        it[channel] = "EMAIL"
        it[enabled] = true
        it[updatedAt] = LocalDateTime.of(2026, 8, 2, 12, 0)
      }
      assertFailsWith<Exception> {
        NotificationPreferencesTable.insert {
          it[profileId] = 44
          it[topic] = "EVENT_ANNOUNCEMENT"
          it[channel] = "EMAIL"
          it[enabled] = false
          it[updatedAt] = LocalDateTime.of(2026, 8, 2, 12, 0)
        }
      }
    }
  }

  @Test
  fun `preferences aggregation column round trips null and explicit values`() {
    transaction {
      NotificationPreferencesTable.insert {
        it[profileId] = 45
        it[topic] = "ACCOUNT"
        it[channel] = "EMAIL"
        it[enabled] = false
        it[aggregation] = "DAILY"
        it[updatedAt] = LocalDateTime.of(2026, 8, 2, 12, 0)
      }
      val row =
          NotificationPreferencesTable.select { NotificationPreferencesTable.profileId eq 45 }
              .single()
      assertEquals(false, row[NotificationPreferencesTable.enabled])
      assertEquals("DAILY", row[NotificationPreferencesTable.aggregation])

      NotificationPreferencesTable.update({ NotificationPreferencesTable.profileId eq 45 }) {
        it[aggregation] = null
        it[updatedAt] = LocalDateTime.of(2026, 8, 2, 13, 0)
      }
      val cleared =
          NotificationPreferencesTable.select { NotificationPreferencesTable.profileId eq 45 }
              .single()
      assertNull(cleared[NotificationPreferencesTable.aggregation])
    }
  }

  @Test
  fun `suppressions insert with auto-increment id`() {
    transaction {
      val id =
          NotificationSuppressionsTable.insert {
                it[profileId] = 46
                it[reason] = "spam-complaint"
                it[createdAt] = LocalDateTime.of(2026, 8, 2, 12, 0)
              }
              .get(NotificationSuppressionsTable.id)
      assertNotNull(id)
      assertEquals(1, NotificationSuppressionsTable.selectAll().count())
    }
  }

  @Test
  fun `profile subscriptions enforces composite primary key`() {
    transaction {
      ProfileSubscriptionsTable.insert {
        it[profileId] = 47
        it[followedProfileId] = 48
        it[createdAt] = LocalDateTime.of(2026, 8, 2, 12, 0)
      }
      assertFailsWith<Exception> {
        ProfileSubscriptionsTable.insert {
          it[profileId] = 47
          it[followedProfileId] = 48
          it[createdAt] = LocalDateTime.of(2026, 8, 2, 13, 0)
        }
      }
    }
  }

  @Test
  fun `profile subscriptions allows the same follower to follow multiple profiles`() {
    transaction {
      ProfileSubscriptionsTable.insert {
        it[profileId] = 49
        it[followedProfileId] = 50
        it[createdAt] = LocalDateTime.of(2026, 8, 2, 12, 0)
      }
      ProfileSubscriptionsTable.insert {
        it[profileId] = 49
        it[followedProfileId] = 51
        it[createdAt] = LocalDateTime.of(2026, 8, 2, 12, 0)
      }
      val followed =
          ProfileSubscriptionsTable.select { ProfileSubscriptionsTable.profileId eq 49 }
              .map { it[ProfileSubscriptionsTable.followedProfileId] }
      assertEquals(setOf(50, 51), followed.toSet())
    }
  }
}
