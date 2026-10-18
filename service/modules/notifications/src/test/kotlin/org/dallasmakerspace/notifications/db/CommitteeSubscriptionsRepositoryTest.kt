package org.dallasmakerspace.notifications.db

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.DBMemberPortalConnection
import org.dallasmakerspace.core.LoggerFactory
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
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

/** Committee follow storage against H2 in MariaDB mode. */
class CommitteeSubscriptionsRepositoryTest {

  companion object {
    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      val db =
          Database.connect(
              "jdbc:h2:mem:committee-subs;DB_CLOSE_DELAY=-1;MODE=MariaDB;DATABASE_TO_LOWER=TRUE",
              driver = "org.h2.Driver")
      DBMemberPortalConnection.db = db
      transaction(db) {
        SchemaUtils.create(ProfileTable, CommitteeSubscriptionsTable)
        listOf("jdoe", "asmith", "bsmith").forEach { name ->
          ProfileTable.insert { it[username] = name }
        }
      }
    }
  }

  private lateinit var repository: CommitteeSubscriptionsRepository

  @Before
  fun setUp() {
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(mock<Logger>())
    repository = CommitteeSubscriptionsRepository(loggerFactory)
    transaction(DBMemberPortalConnection.db) { CommitteeSubscriptionsTable.deleteAll() }
  }

  @Test
  fun `subscribe then unsubscribe toggles isSubscribed`() = runBlocking {
    assertFalse(repository.isSubscribed("jdoe", 7))

    repository.subscribe("jdoe", 7)
    assertTrue(repository.isSubscribed("jdoe", 7))
    assertFalse(repository.isSubscribed("jdoe", 8))
    assertFalse(repository.isSubscribed("asmith", 7))

    repository.unsubscribe("jdoe", 7)
    assertFalse(repository.isSubscribed("jdoe", 7))
  }

  @Test
  fun `subscribing twice and unsubscribing when not subscribed are no-ops`() = runBlocking {
    repository.subscribe("jdoe", 7)
    repository.subscribe("jdoe", 7)
    repository.unsubscribe("asmith", 7)

    val rows = transaction(DBMemberPortalConnection.db) { CommitteeSubscriptionsTable.selectAll().count() }
    assertEquals(1, rows)
  }

  @Test
  fun `unknown members cannot subscribe, and are never subscribed`() = runBlocking {
    assertFailsWith<IllegalArgumentException> { repository.subscribe("nobody", 7) }
    assertFalse(repository.isSubscribed("nobody", 7))
    repository.unsubscribe("nobody", 7)
  }

  @Test
  fun `getSubscribers returns each follower of any listed committee once, sorted`() = runBlocking {
    repository.subscribe("bsmith", 7)
    repository.subscribe("asmith", 7)
    repository.subscribe("asmith", 9)
    repository.subscribe("jdoe", 12)

    assertEquals(listOf("asmith", "bsmith"), repository.getSubscribers(listOf(7)))
    assertEquals(listOf("asmith", "bsmith"), repository.getSubscribers(listOf(7, 9)))
    assertEquals(listOf("asmith", "bsmith", "jdoe"), repository.getSubscribers(listOf(7, 9, 12)))
    assertTrue(repository.getSubscribers(emptyList()).isEmpty())
    assertTrue(repository.getSubscribers(listOf(99)).isEmpty())
  }
}
