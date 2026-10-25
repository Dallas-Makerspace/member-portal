package org.dallasmakerspace.notifications.db

import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.DBMemberPortalConnection
import org.dallasmakerspace.members.db.ProfileTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/** Chair message storage and send limits against H2 in MariaDB mode. */
class CommitteeMessagesRepositoryTest {

  companion object {
    private val limits = SendLimits(perDay = 2, perWeek = 3)
    private val now = LocalDateTime.of(2026, 3, 10, 12, 0)

    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      val db =
          Database.connect(
              "jdbc:h2:mem:committee-messages;DB_CLOSE_DELAY=-1;MODE=MariaDB;DATABASE_TO_LOWER=TRUE",
              driver = "org.h2.Driver")
      DBMemberPortalConnection.db = db
      transaction(db) {
        SchemaUtils.create(ProfileTable, CommitteeMessagesTable)
        ProfileTable.insert { it[username] = "jdoe" }
      }
    }
  }

  private val repository = CommitteeMessagesRepository()

  @Before
  fun setUp() {
    transaction(DBMemberPortalConnection.db) { CommitteeMessagesTable.deleteAll() }
  }

  private fun reserve(committeeId: Int, at: LocalDateTime) = runBlocking {
    repository.reserve(committeeId, "jdoe", "Meeting moved to Thursday", limits, at)
  }

  @Test
  fun `reserve records a pending message`() {
    val reservation = reserve(9, now)

    val id = assertIs<Reservation.Reserved>(reservation).id
    val row =
        transaction(DBMemberPortalConnection.db) {
          CommitteeMessagesTable.selectAll().single()
        }
    assertEquals(id, row[CommitteeMessagesTable.id].value)
    assertEquals(9, row[CommitteeMessagesTable.committeeId])
    assertEquals("Meeting moved to Thursday", row[CommitteeMessagesTable.message])
    assertEquals(CommitteeMessageStatus.PENDING, row[CommitteeMessagesTable.status])
  }

  @Test
  fun `the third message in a day is refused until the first is a day old`() {
    reserve(9, now.minusHours(10))
    reserve(9, now.minusHours(2))

    val refused = reserve(9, now)

    assertEquals(
        now.minusHours(10).plusDays(1), assertIs<Reservation.RateLimited>(refused).nextAllowedAt)
  }

  @Test
  fun `the fourth message in a week is refused even on a quiet day`() {
    reserve(9, now.minusDays(6))
    reserve(9, now.minusDays(5))
    reserve(9, now.minusDays(3))

    val refused = reserve(9, now)

    assertEquals(
        now.minusDays(6).plusDays(7), assertIs<Reservation.RateLimited>(refused).nextAllowedAt)
  }

  @Test
  fun `limits are per committee`() {
    reserve(9, now.minusHours(3))
    reserve(9, now.minusHours(2))

    assertIs<Reservation.Reserved>(reserve(11, now))
  }

  @Test
  fun `a failed message does not count`() {
    val first = assertIs<Reservation.Reserved>(reserve(9, now.minusHours(3)))
    reserve(9, now.minusHours(2))
    runBlocking { repository.markFailed(first.id) }

    assertIs<Reservation.Reserved>(reserve(9, now))
  }

  @Test
  fun `markSent stores the thread and short link`() {
    val id = assertIs<Reservation.Reserved>(reserve(9, now)).id

    runBlocking {
      repository.attachThread(id, 42, "https://talk.example.test/t/x/42", threadCreated = true)
      repository.markSent(id, "https://short.example.test/abc", recipientCount = 5)
    }

    val row = transaction(DBMemberPortalConnection.db) { CommitteeMessagesTable.selectAll().single() }
    assertEquals(42, row[CommitteeMessagesTable.talkTopicId])
    assertEquals(true, row[CommitteeMessagesTable.threadCreated])
    assertEquals("https://short.example.test/abc", row[CommitteeMessagesTable.shortUrl])
    assertEquals(5, row[CommitteeMessagesTable.recipientCount])
    assertEquals(CommitteeMessageStatus.SENT, row[CommitteeMessagesTable.status])
  }

  @Test
  fun `nextAllowedAt is null while under both limits`() {
    assertNull(CommitteeMessagesRepository.nextAllowedAt(emptyList(), now, limits))
    assertNull(
        CommitteeMessagesRepository.nextAllowedAt(listOf(now.minusHours(1)), now, limits))
  }
}
