package org.dallasmakerspace.bambuddy.notifications.db

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.bambuddy.IBambuddyApiClient
import org.dallasmakerspace.bambuddy.notifications.EphemeralNotification
import org.dallasmakerspace.bambuddy.notifications.EphemeralNotificationService
import org.dallasmakerspace.bambuddy.notifications.WatchScope
import org.dallasmakerspace.bambuddy.notifications.WatchStatus
import org.dallasmakerspace.core.DBMemberPortalConnection
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.db.ProfileTable
import org.dallasmakerspace.notifications.CreateNotificationRequest
import org.dallasmakerspace.notifications.Notification
import org.dallasmakerspace.notifications.NotificationService
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

/**
 * P0 regression (panel review, Exposed 0.41.1): the watch claim and
 * [NotificationService.create] commit in SEPARATE transactions — the pinned Exposed
 * `newSuspendedTransaction` never joins an ambient transaction — so a create that throws
 * after the claim point must not strand the watch as a FIRED/EXPIRED tombstone with
 * `fired_notification_id = NULL` and no notification. Real H2 repositories throughout;
 * NotificationService is mocked so the failure point is controllable.
 *
 * Guarantees proven here:
 * 1. create throws after the claim → the claim is compensated back to ARMED, and a retry
 *    creates the notice exactly once (same per-watch dedupe key).
 * 2. A tombstone stranded by a crash between claim and create (simulated by claiming
 *    directly) is recovered by [EphemeralNotificationService.reconcileTerminalWatches],
 *    exactly once.
 */
class EphemeralNotificationStrandingTest {

  companion object {
    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      val db =
          Database.connect(
              "jdbc:h2:mem:bambu-stranding;DB_CLOSE_DELAY=-1;MODE=MariaDB;DATABASE_TO_LOWER=TRUE",
              driver = "org.h2.Driver")
      DBMemberPortalConnection.db = db
      transaction(db) {
        SchemaUtils.create(ProfileTable, EphemeralNotificationsTable)
        ProfileTable.insert { it[username] = "jdoe" }
      }
    }
  }

  private val utc = ZoneId.of("UTC")
  private val now: LocalDateTime = LocalDateTime.now(utc).withNano(0)
  private lateinit var watches: EphemeralNotificationsRepository
  private lateinit var notificationService: NotificationService
  private lateinit var apiClient: IBambuddyApiClient
  private lateinit var service: EphemeralNotificationService
  private val createdRequests = mutableListOf<CreateNotificationRequest>()
  private var createShouldFail = false

  @Before
  fun setUp() {
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(mock<Logger>())
    watches = EphemeralNotificationsRepository(loggerFactory)
    createdRequests.clear()
    createShouldFail = false
    notificationService = mock()
    runBlocking {
      whenever(notificationService.create(any())).thenAnswer {
        if (createShouldFail) throw RuntimeException("notification store unavailable")
        val request = it.getArgument<CreateNotificationRequest>(0)
        createdRequests += request
        val notification = mock<Notification>()
        whenever(notification.id).thenReturn(5000L + createdRequests.size)
        notification
      }
    }
    apiClient = mock()
    service = EphemeralNotificationService(watches, apiClient, notificationService, loggerFactory)
    transaction(DBMemberPortalConnection.db) { EphemeralNotificationsTable.deleteAll() }
  }

  private fun armedWatch(): EphemeralNotification = runBlocking {
    watches.insert(
        EphemeralNotification(
            id = 0,
            memberUsername = "jdoe",
            scope = WatchScope.OWN_JOB,
            printerId = 1,
            printerName = "Printer 1",
            queueId = 10,
            filename = "Job.3mf",
            jobName = "Job.3mf",
            triggerEvents = listOf("print_complete", "print_failed", "print_stopped"),
            status = WatchStatus.ARMED,
            rearmCount = 0,
            createdAt = now.minusHours(1),
            expiresAt = now.plusHours(1),
            terminalAt = null,
            firedNotificationId = null,
            terminalReason = null))
  }

  @Test
  fun `a create failure after the claim leaves the watch ARMED and a retry fires exactly once`() =
      runBlocking {
        val watch = armedWatch()
        createShouldFail = true

        assertFailsWith<RuntimeException> {
          service.fireWatch(watch, "print_complete", null, now)
        }

        // Not stranded: the committed claim was compensated back to ARMED, nothing linked.
        val afterFailure = assertNotNull(watches.getById(watch.id))
        assertEquals(WatchStatus.ARMED, afterFailure.status)
        assertNull(afterFailure.firedNotificationId)
        assertNull(afterFailure.terminalAt)
        assertTrue(createdRequests.isEmpty())
        assertTrue(watches.findTerminalWithoutNotification(10).isEmpty())

        // Retry with a healthy create: fires, and the notice exists exactly once.
        createShouldFail = false
        assertTrue(service.fireWatch(watch, "print_complete", null, now))
        val afterRetry = assertNotNull(watches.getById(watch.id))
        assertEquals(WatchStatus.FIRED, afterRetry.status)
        assertNotNull(afterRetry.firedNotificationId)
        assertEquals(1, createdRequests.size)
        assertEquals("bambu:1:10:print_complete:${watch.id}", createdRequests.single().dedupeKey)
        Unit
      }

  @Test
  fun `a create failure on the expiry fire path also leaves the watch ARMED`() = runBlocking {
    val watch = armedWatch()
    createShouldFail = true
    whenever(apiClient.getQueue())
        .thenReturn(
            listOf(
                org.dallasmakerspace.bambuddy.BambuQueueItem(
                    id = 10, printerId = 1, status = "completed", archiveName = "Job.3mf")))
    whenever(apiClient.getPrinterStatus(any())).thenReturn(null)

    assertFailsWith<RuntimeException> { service.processExpiredWatch(watch) }

    val afterFailure = assertNotNull(watches.getById(watch.id))
    assertEquals(WatchStatus.ARMED, afterFailure.status)
    assertNull(afterFailure.firedNotificationId)
    Unit
  }

  @Test
  fun `the reconciliation pass recovers a crash-stranded FIRED tombstone exactly once`() =
      runBlocking {
        val watch = armedWatch()
        // Simulate the crash window in the old code: claim committed, process died before
        // the notification create — a FIRED tombstone with no notification linked.
        assertTrue(watches.claimForFiring(watch.id, now, "Fired by print_complete"))
        val stranded = assertNotNull(watches.getById(watch.id))
        assertEquals(WatchStatus.FIRED, stranded.status)
        assertNull(stranded.firedNotificationId)
        assertEquals(listOf(watch.id), watches.findTerminalWithoutNotification(10).map { it.id })

        assertEquals(1, service.reconcileTerminalWatches())

        val recovered = assertNotNull(watches.getById(watch.id))
        assertEquals(WatchStatus.FIRED, recovered.status)
        assertNotNull(recovered.firedNotificationId)
        assertEquals(1, createdRequests.size)
        // Same per-watch dedupe key the original fire would have used: a real duplicate
        // create against NotificationService would return the existing row, not a second send.
        assertEquals("bambu:1:10:print_complete:${watch.id}", createdRequests.single().dedupeKey)

        // Second pass: nothing left stranded, nothing more created.
        assertEquals(0, service.reconcileTerminalWatches())
        assertEquals(1, createdRequests.size)
        Unit
      }

  @Test
  fun `the reconciliation pass recovers a crash-stranded EXPIRED tombstone`() = runBlocking {
    val watch = armedWatch()
    assertTrue(watches.claimForExpiry(watch.id, now, "BamBuddy unreachable at expiry check"))

    assertEquals(1, service.reconcileTerminalWatches())

    val recovered = assertNotNull(watches.getById(watch.id))
    assertEquals(WatchStatus.EXPIRED, recovered.status)
    assertNotNull(recovered.firedNotificationId)
    assertEquals("bambu-watch-expired", createdRequests.single().templateName)
    assertEquals("bambu:watch:${watch.id}:expired", createdRequests.single().dedupeKey)
    Unit
  }
}
