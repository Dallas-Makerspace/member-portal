package org.dallasmakerspace.notifications.db

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.DBMemberPortalConnection
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.db.ProfileTable
import org.dallasmakerspace.notifications.Aggregation
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.Notification
import org.dallasmakerspace.notifications.NotificationStatus
import org.dallasmakerspace.notifications.UnknownTopic
import org.dallasmakerspace.notifications.templates.SystemTestTemplate
import org.dallasmakerspace.notifications.templates.TemplateRegistry
import org.dallasmakerspace.notifications.topics.SystemTopic
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

/** Exercises the outbox state-transition queries against H2 in MariaDB mode. */
class NotificationsRepositoryTest {

  companion object {
    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      val db =
          Database.connect(
              "jdbc:h2:mem:notifications-repo;DB_CLOSE_DELAY=-1;MODE=MariaDB;DATABASE_TO_LOWER=TRUE",
              driver = "org.h2.Driver")
      DBMemberPortalConnection.db = db
      transaction(db) {
        SchemaUtils.create(ProfileTable, NotificationsTable, NotificationSuppressionsTable)
        listOf("jdoe", "asmith").forEach { name -> ProfileTable.insert { it[username] = name } }
      }
    }
  }

  private val utc = ZoneId.of("UTC")
  private val now: LocalDateTime = LocalDateTime.now(utc).withNano(0)
  private lateinit var repository: NotificationsRepository

  @Before
  fun setUp() {
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(mock<Logger>())
    repository = NotificationsRepository(loggerFactory, TemplateRegistry(setOf(SystemTestTemplate())))
    transaction(DBMemberPortalConnection.db) {
      NotificationsTable.deleteAll()
      NotificationSuppressionsTable.deleteAll()
    }
  }

  @Suppress("LongParameterList")
  private fun insert(
      member: String = "jdoe",
      status: NotificationStatus = NotificationStatus.QUEUED,
      aggregation: Aggregation = Aggregation.IMMEDIATE,
      dueAt: LocalDateTime? = null,
      providerId: String? = null,
      channel: Channel = Channel.EMAIL,
  ): Long = runBlocking {
    repository
        .create(
            Notification(
                id = 0,
                memberUsername = member,
                topic = SystemTopic,
                channel = channel,
                aggregation = aggregation,
                templateName = "system-test",
                payload = null,
                subject = "s",
                body = "b",
                status = status,
                attempts = 0,
                nextAttemptAt = dueAt,
                dedupeKey = null,
                providerId = providerId,
                lastError = null,
                sentAt = null,
                readAt = null,
                createdAt = now))
        .id
  }

  private fun statusOf(id: Long) = runBlocking {
    repository.list(100).single { it.id == id }.status
  }

  @Test
  fun `claim should succeed exactly once`() = runBlocking {
    val id = insert()

    assertTrue(repository.claim(id, listOf(NotificationStatus.QUEUED)))
    assertFalse(repository.claim(id, listOf(NotificationStatus.QUEUED)))
    assertEquals(NotificationStatus.SENDING, statusOf(id))
  }

  @Test
  fun `due digest queries should respect the delivery window and return whole members`() =
      runBlocking {
        val due1 = insert(status = NotificationStatus.IN_DIGEST, dueAt = now.minusMinutes(1))
        val due2 = insert(status = NotificationStatus.IN_DIGEST, dueAt = now.minusHours(1))
        insert(status = NotificationStatus.IN_DIGEST, dueAt = now.plusHours(3))
        insert(member = "asmith", status = NotificationStatus.IN_DIGEST, dueAt = now.plusDays(1))

        assertEquals(listOf("jdoe"), repository.getMembersWithDueDigests(10, now))
        assertEquals(
            listOf(due1, due2), repository.getDueDigestRows("jdoe", now).map { it.id })
      }

  @Test
  fun `immediate queries should honor the username restriction`() = runBlocking {
    insert(member = "asmith")
    insert(member = "jdoe")

    assertEquals(listOf("jdoe"), repository.getMembersWithDueImmediate(10, now, setOf("jdoe")))
    assertEquals(emptyList(), repository.getMembersWithDueImmediate(10, now, setOf("nobody")))
  }

  @Test
  fun `a member is due once any queued row's hold ends, and the burst includes held rows`() =
      runBlocking {
        val held = insert(dueAt = now.plusMinutes(4))
        assertEquals(emptyList(), repository.getMembersWithDueImmediate(10, now))

        val due = insert(dueAt = now.minusSeconds(1))
        val retryLater = insert(status = NotificationStatus.FAILED, dueAt = now.plusHours(1))

        assertEquals(listOf("jdoe"), repository.getMembersWithDueImmediate(10, now))
        assertEquals(listOf(held, due), repository.getImmediateBurst("jdoe", now).map { it.id })
        assertTrue(retryLater !in repository.getImmediateBurst("jdoe", now).map { it.id })
      }

  @Test
  fun `countImmediateEmailsSince counts a combined email once`() = runBlocking {
    listOf("txn-a", "txn-a", "txn-a", "txn-b").forEach { txn ->
      val id = insert()
      repository.markSent(id, txn, now)
    }

    assertEquals(2, repository.countImmediateEmailsSince("jdoe", now.minusHours(1)))
    assertEquals(0, repository.countImmediateEmailsSince("jdoe", now.plusHours(1)))
  }

  @Test
  fun `countMessagesSentSince counts every member's messages on one channel, digests once`() =
      runBlocking {
        listOf("txn-a" to "jdoe", "txn-a" to "jdoe", "txn-b" to "asmith").forEach { (txn, member) ->
          repository.markSent(insert(member = member, aggregation = Aggregation.DAILY), txn, now)
        }
        repository.markSent(insert(channel = Channel.SMS), "SM-1", now)

        assertEquals(2, repository.countMessagesSentSince(Channel.EMAIL, now.minusHours(1)))
        assertEquals(1, repository.countMessagesSentSince(Channel.SMS, now.minusHours(1)))
        assertEquals(0, repository.countMessagesSentSince(Channel.EMAIL, now.plusHours(1)))
      }

  @Test
  fun `countImmediateEmailsSince ignores texts`() = runBlocking {
    repository.markSent(insert(channel = Channel.SMS), "SM-1", now)

    assertEquals(0, repository.countImmediateEmailsSince("jdoe", now.minusHours(1)))
  }

  @Test
  fun `markDigestRetry should keep the row in digest and count the attempt`() = runBlocking {
    val id = insert(status = NotificationStatus.IN_DIGEST)
    repository.claim(id, listOf(NotificationStatus.IN_DIGEST))

    repository.markDigestRetry(id, "rate limited", now.plusMinutes(5))

    val row = repository.list(10).single()
    assertEquals(NotificationStatus.IN_DIGEST, row.status)
    assertEquals(1, row.attempts)
    assertEquals(now.plusMinutes(5), row.nextAttemptAt)
    assertTrue(repository.getMembersWithDueImmediate(10, now.plusDays(1)).isEmpty())
  }

  @Test
  fun `requeueStaleSending should return digest rows to the digest and others to retry`() =
      runBlocking {
        val immediate = insert()
        val digest = insert(status = NotificationStatus.IN_DIGEST, aggregation = Aggregation.DAILY)
        val fresh = insert()
        listOf(immediate, digest, fresh).forEach {
          repository.claim(it, listOf(NotificationStatus.QUEUED, NotificationStatus.IN_DIGEST))
        }
        transaction(DBMemberPortalConnection.db) {
          listOf(immediate, digest).forEach { id ->
            NotificationsTable.update({ NotificationsTable.idColumn eq id }) {
              it[updatedAt] = now.minusHours(1)
            }
          }
        }

        assertEquals(2, repository.requeueStaleSending(now.minusMinutes(15)))
        assertEquals(NotificationStatus.FAILED, statusOf(immediate))
        assertEquals(NotificationStatus.IN_DIGEST, statusOf(digest))
        assertEquals(NotificationStatus.SENDING, statusOf(fresh))
      }

  @Test
  fun `getAllByProviderId should return every row of a digest transmission`() = runBlocking {
    insert(status = NotificationStatus.SENT, providerId = "txn")
    insert(status = NotificationStatus.SENT, providerId = "txn")
    insert(status = NotificationStatus.SENT, providerId = "other")

    assertEquals(2, repository.getAllByProviderId("txn").size)
  }

  @Test
  fun `insertSuppression should not duplicate a reason`() = runBlocking {
    repository.insertSuppression("jdoe", "jdoe@example.com", "BOUNCE")
    repository.insertSuppression("jdoe", "jdoe@example.com", "BOUNCE")

    val count = transaction(DBMemberPortalConnection.db) { NotificationSuppressionsTable.selectAll().count() }
    assertEquals(1L, count)
  }

  @Test
  fun `rows with an unknown topic should still load`() = runBlocking {
    val id = insert()
    transaction(DBMemberPortalConnection.db) {
      NotificationsTable.update({ NotificationsTable.idColumn eq id }) {
        it[topic] = "REMOVED_FEATURE"
      }
    }

    assertEquals(UnknownTopic("REMOVED_FEATURE"), repository.list(10).single().topic)
  }

  @Test
  fun `unread counts only delivered rows and markAllRead clears just this member`() = runBlocking {
    insert(status = NotificationStatus.SENT)
    insert(status = NotificationStatus.SENT)
    insert(status = NotificationStatus.IN_DIGEST)
    insert(member = "asmith", status = NotificationStatus.SENT)

    assertEquals(2, repository.countUnread("jdoe"))
    assertEquals(2, repository.markAllRead("jdoe", now))
    assertEquals(0, repository.countUnread("jdoe"))
    assertEquals(1, repository.countUnread("asmith"))
    assertEquals(0, repository.countUnread("nobody"))
  }
}
