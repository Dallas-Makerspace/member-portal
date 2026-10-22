package org.dallasmakerspace.bambuddy.notifications.db

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.bambuddy.notifications.EphemeralNotification
import org.dallasmakerspace.bambuddy.notifications.PendingOfflineAlert
import org.dallasmakerspace.bambuddy.notifications.PendingOfflineStatus
import org.dallasmakerspace.bambuddy.notifications.WatchScope
import org.dallasmakerspace.bambuddy.notifications.WatchStatus
import org.dallasmakerspace.core.DBMemberPortalConnection
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.db.ProfileTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

/** Exercises the watch / offline-alert / subscription repositories against H2 in MariaDB mode. */
class BambuRepositoriesTest {

  companion object {
    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      val db =
          Database.connect(
              "jdbc:h2:mem:bambu-repositories;DB_CLOSE_DELAY=-1;MODE=MariaDB;DATABASE_TO_LOWER=TRUE",
              driver = "org.h2.Driver")
      DBMemberPortalConnection.db = db
      transaction(db) {
        SchemaUtils.create(
            ProfileTable,
            EphemeralNotificationsTable,
            PendingOfflineAlertsTable,
            BambuCommitteeSubscriptionsTable)
        listOf("jdoe", "asmith").forEach { name -> ProfileTable.insert { it[username] = name } }
      }
    }
  }

  private val utc = ZoneId.of("UTC")
  private val now: LocalDateTime = LocalDateTime.now(utc).withNano(0)
  private lateinit var watches: EphemeralNotificationsRepository
  private lateinit var offlineAlerts: PendingOfflineAlertsRepository
  private lateinit var subscriptions: BambuCommitteeSubscriptionsRepository

  @Before
  fun setUp() {
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(mock<Logger>())
    watches = EphemeralNotificationsRepository(loggerFactory)
    offlineAlerts = PendingOfflineAlertsRepository(loggerFactory)
    subscriptions = BambuCommitteeSubscriptionsRepository(loggerFactory)
    transaction(DBMemberPortalConnection.db) {
      EphemeralNotificationsTable.deleteAll()
      PendingOfflineAlertsTable.deleteAll()
      BambuCommitteeSubscriptionsTable.deleteAll()
    }
  }

  private fun watch(
      member: String = "jdoe",
      queueId: Int? = 10,
      expiresAt: LocalDateTime = now.plusHours(2),
  ) =
      EphemeralNotification(
          id = 0,
          memberUsername = member,
          scope = WatchScope.OWN_JOB,
          printerId = 1,
          printerName = "Printer 1",
          queueId = queueId,
          filename = "Job.3mf",
          jobName = "Job.3mf",
          triggerEvents = listOf("print_complete", "print_failed", "print_stopped"),
          status = WatchStatus.ARMED,
          rearmCount = 0,
          createdAt = now,
          expiresAt = expiresAt,
          terminalAt = null,
          firedNotificationId = null,
          terminalReason = null)

  @Test
  fun `insert and getById round-trip a watch`() = runBlocking {
    val inserted = watches.insert(watch())

    val loaded = assertNotNull(watches.getById(inserted.id))
    assertEquals("jdoe", loaded.memberUsername)
    assertEquals(WatchScope.OWN_JOB, loaded.scope)
    assertEquals(1, loaded.printerId)
    assertEquals(10, loaded.queueId)
    assertEquals("Job.3mf", loaded.filename)
    assertEquals(listOf("print_complete", "print_failed", "print_stopped"), loaded.triggerEvents)
    assertEquals(WatchStatus.ARMED, loaded.status)
  }

  @Test
  fun `listArmed returns only the member's ARMED watches`() = runBlocking {
    val mine = watches.insert(watch())
    watches.insert(watch(member = "asmith"))
    val fired = watches.insert(watch(queueId = 11))
    watches.claimForFiring(fired.id, now, "done")

    assertEquals(listOf(mine.id), watches.listArmed("jdoe").map { it.id })
    assertEquals(1, watches.findArmedByPrinter(1).count { it.memberUsername == "jdoe" })
  }

  @Test
  fun `the firing claim succeeds exactly once - a double fire cannot consume a watch twice`() =
      runBlocking {
        val inserted = watches.insert(watch())

        assertTrue(watches.claimForFiring(inserted.id, now, "Fired by print_complete"))
        assertFalse(watches.claimForFiring(inserted.id, now, "Fired by print_complete"))
        assertFalse(watches.claimForExpiry(inserted.id, now, "too late"))
        assertFalse(watches.claimForCancel(inserted.id, "jdoe", now))

        val stored = assertNotNull(watches.getById(inserted.id))
        assertEquals(WatchStatus.FIRED, stored.status)
        assertEquals(now, stored.terminalAt)
      }

  @Test
  fun `the re-arm claim succeeds exactly once`() = runBlocking {
    val inserted = watches.insert(watch())
    val newExpiry = now.plusHours(3)

    assertTrue(watches.claimForRearm(inserted.id, newExpiry))
    assertFalse(watches.claimForRearm(inserted.id, newExpiry.plusHours(1)))

    val stored = assertNotNull(watches.getById(inserted.id))
    assertEquals(1, stored.rearmCount)
    assertEquals(newExpiry, stored.expiresAt)
    assertEquals(WatchStatus.ARMED, stored.status)
  }

  @Test
  fun `the cancel claim is scoped to the owner`() = runBlocking {
    val inserted = watches.insert(watch())

    assertFalse(watches.claimForCancel(inserted.id, "asmith", now))
    assertTrue(watches.claimForCancel(inserted.id, "jdoe", now))
    assertEquals(WatchStatus.CANCELLED, watches.getById(inserted.id)?.status)
  }

  @Test
  fun `findDueArmed returns only armed watches past their deadline`() = runBlocking {
    val due = watches.insert(watch(expiresAt = now.minusMinutes(1)))
    watches.insert(watch(queueId = 11, expiresAt = now.plusHours(1)))
    val dueButFired = watches.insert(watch(queueId = 12, expiresAt = now.minusMinutes(5)))
    watches.claimForFiring(dueButFired.id, now, "done")

    assertEquals(listOf(due.id), watches.findDueArmed(now, 50).map { it.id })
  }

  @Test
  fun `purge removes only terminal tombstones older than the cutoff`() = runBlocking {
    val oldFired = watches.insert(watch())
    watches.claimForFiring(oldFired.id, now.minusDays(8), "done")
    val recentFired = watches.insert(watch(queueId = 11))
    watches.claimForFiring(recentFired.id, now.minusDays(1), "done")
    val armed = watches.insert(watch(queueId = 12, expiresAt = now.minusDays(30)))

    val purged = watches.purgeTombstones(now.minusDays(7))

    assertEquals(1, purged)
    assertNull(watches.getById(oldFired.id))
    assertNotNull(watches.getById(recentFired.id))
    assertNotNull(watches.getById(armed.id)) // ARMED rows are never purged
      Unit
  }

  @Test
  fun `pending offline alerts claim resolution exactly once and purge when old`() = runBlocking {
    val alert =
        offlineAlerts.insert(
            PendingOfflineAlert(
                id = 0,
                printerId = 3,
                printerName = "Printer 3",
                offlineSince = now.minusMinutes(6),
                releaseAt = now.minusMinutes(1),
                status = PendingOfflineStatus.PENDING,
                resolvedAt = null,
                notificationsCreated = null))
    offlineAlerts.insert(
        alert.copy(id = 0, printerId = 4, printerName = "Printer 4", releaseAt = now.plusMinutes(5)))

    assertEquals(alert.id, offlineAlerts.findPendingByPrinter(3)?.id)
    assertEquals(listOf(alert.id), offlineAlerts.findDuePending(now, 50).map { it.id })

    assertTrue(offlineAlerts.claimSent(alert.id, now, 2))
    assertFalse(offlineAlerts.claimSent(alert.id, now, 2))
    assertFalse(offlineAlerts.claimSuppressed(alert.id, now))
    assertNull(offlineAlerts.findPendingByPrinter(3))

    // Resolved just now: not yet purgeable. A second episode resolved long ago is.
    val old =
        offlineAlerts.insert(alert.copy(id = 0, printerId = 5, printerName = "Printer 5"))
    offlineAlerts.claimSuppressed(old.id, now.minusDays(8))
    assertEquals(1, offlineAlerts.purgeResolved(now.minusDays(7)))
    assertEquals(0, offlineAlerts.purgeResolved(now.minusDays(7)))
  }

  @Test
  fun `insertArmedIfAbsent returns the existing armed watch instead of inserting a duplicate`() =
      runBlocking {
        val (first, createdFirst) = watches.insertArmedIfAbsent(watch())
        val (second, createdSecond) = watches.insertArmedIfAbsent(watch())

        assertTrue(createdFirst)
        assertFalse(createdSecond)
        assertEquals(first.id, second.id)
        assertEquals(1, watches.listArmed("jdoe").size)

        // A different queue item is a different watch and does insert.
        val (third, createdThird) = watches.insertArmedIfAbsent(watch(queueId = 11))
        assertTrue(createdThird)
        assertTrue(third.id != first.id)
        Unit
      }

  @Test
  fun `insertIfNoPending blocks a duplicate episode but allows a second after resolution`() =
      runBlocking {
        fun episode(printerId: Int) =
            PendingOfflineAlert(
                id = 0,
                printerId = printerId,
                printerName = "Printer $printerId",
                offlineSince = now,
                releaseAt = now.plusMinutes(5),
                status = PendingOfflineStatus.PENDING,
                resolvedAt = null,
                notificationsCreated = null)

        val first = offlineAlerts.insertIfNoPending(episode(3))
        assertNotNull(first)
        // Same printer, episode still pending: no second row.
        assertNull(offlineAlerts.insertIfNoPending(episode(3)))
        // A different printer is a different episode.
        assertNotNull(offlineAlerts.insertIfNoPending(episode(4)))

        // Resolve episode 1 through the PROCESSING claim, as the sweeper does.
        assertTrue(offlineAlerts.claimProcessing(first.id))
        assertFalse(offlineAlerts.claimProcessing(first.id)) // already claimed
        assertTrue(offlineAlerts.claimSent(first.id, now, 2))

        // F12: a new printer_offline after the first episode resolved inserts episode 2.
        val second = offlineAlerts.insertIfNoPending(episode(3))
        assertNotNull(second)
        assertTrue(second.id != first.id)
        assertEquals(second.id, offlineAlerts.findPendingByPrinter(3)?.id)
        Unit
      }

  @Test
  fun `a failed release returns the PROCESSING claim to PENDING`() = runBlocking {
    val alert =
        offlineAlerts.insert(
            PendingOfflineAlert(
                id = 0,
                printerId = 6,
                printerName = "Printer 6",
                offlineSince = now.minusMinutes(6),
                releaseAt = now.minusMinutes(1),
                status = PendingOfflineStatus.PENDING,
                resolvedAt = null,
                notificationsCreated = null))

    assertTrue(offlineAlerts.claimProcessing(alert.id))
    assertTrue(offlineAlerts.releaseProcessingClaim(alert.id))
    assertEquals(alert.id, offlineAlerts.findPendingByPrinter(6)?.id)
    // And it can be claimed again for the retry.
    assertTrue(offlineAlerts.claimProcessing(alert.id))
    assertTrue(offlineAlerts.claimSuppressed(alert.id, now))
    Unit
  }

  @Test
  fun `committee subscriptions are idempotent and list subscribers`() = runBlocking {
    subscriptions.subscribe("jdoe")
    subscriptions.subscribe("jdoe") // duplicate subscribe is a no-op, not an error
    subscriptions.subscribe("asmith")

    assertTrue(subscriptions.isSubscribed("jdoe"))
    assertEquals(listOf("asmith", "jdoe"), subscriptions.getSubscribers())

    subscriptions.unsubscribe("jdoe")
    subscriptions.unsubscribe("jdoe") // and unsubscribing twice is fine too
    assertFalse(subscriptions.isSubscribed("jdoe"))
    assertEquals(listOf("asmith"), subscriptions.getSubscribers())
  }
}
