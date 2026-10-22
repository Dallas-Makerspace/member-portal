package org.dallasmakerspace.bambuddy.notifications

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.bambuddy.BambuPrinter
import org.dallasmakerspace.bambuddy.BambuPrinterStatus
import org.dallasmakerspace.bambuddy.BambuQueueItem
import org.dallasmakerspace.bambuddy.IBambuddyApiClient
import org.dallasmakerspace.core.DBMemberPortalConnection
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.CreateNotificationRequest
import org.dallasmakerspace.notifications.Notification
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.topics.BambuPrintActionRequiredTopic
import org.dallasmakerspace.notifications.topics.BambuPrintFinishedTopic
import org.dallasmakerspace.notifications.topics.BambuPrinterAvailableTopic
import org.dallasmakerspace.notifications.topics.BambuWatchTopic
import org.jetbrains.exposed.sql.Database
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class EphemeralNotificationServiceTest {

  companion object {
    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      // No SQL runs against this database (the repository is a fake, NotificationService a
      // mock); a connected Database handle is kept so any transaction helper a code path
      // still uses has somewhere to bind.
      DBMemberPortalConnection.db =
          Database.connect(
              "jdbc:h2:mem:bambu-watch-service;DB_CLOSE_DELAY=-1;MODE=MariaDB;DATABASE_TO_LOWER=TRUE",
              driver = "org.h2.Driver")
    }
  }

  private val utc = ZoneId.of("UTC")
  private lateinit var repository: FakeEphemeralNotificationsRepository
  private lateinit var apiClient: IBambuddyApiClient
  private lateinit var notificationService: NotificationService
  private lateinit var service: EphemeralNotificationService
  private val createdRequests = mutableListOf<CreateNotificationRequest>()

  @Before
  fun setUp() {
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(mock<Logger>())
    repository = FakeEphemeralNotificationsRepository(loggerFactory)
    apiClient = mock()
    notificationService = mock()
    createdRequests.clear()
    runBlocking {
      whenever(notificationService.create(any())).thenAnswer {
        val request = it.getArgument<CreateNotificationRequest>(0)
        createdRequests += request
        val notification = mock<Notification>()
        whenever(notification.id).thenReturn(1000L + createdRequests.size)
        notification
      }
    }
    service = EphemeralNotificationService(repository, apiClient, notificationService, loggerFactory)
  }

  // -------------------------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------------------------

  private fun stubApi(
      printers: List<BambuPrinter> = listOf(BambuPrinter(id = 1, name = "Printer 1")),
      queue: List<BambuQueueItem> = emptyList(),
      status: BambuPrinterStatus? = null,
  ) = runBlocking {
    whenever(apiClient.getPrinters()).thenReturn(printers)
    whenever(apiClient.getQueue()).thenReturn(queue)
    whenever(apiClient.getPrinterStatus(any())).thenReturn(status)
  }

  private fun printingStatus(remaining: Int? = 100, currentPrint: String? = "Job.3mf") =
      BambuPrinterStatus(
          id = 1,
          name = "Printer 1",
          connected = true,
          state = "RUNNING",
          currentPrint = currentPrint,
          remainingTimeMinutes = remaining)

  private fun queueItem(
      id: Int = 10,
      status: String = "printing",
      owner: String? = "jdoe",
      archiveName: String? = "Job.3mf",
      printTimeSeconds: Int? = 3600,
  ) =
      BambuQueueItem(
          id = id,
          printerId = 1,
          status = status,
          archiveName = archiveName,
          printTimeSeconds = printTimeSeconds,
          createdByUsername = owner)

  private fun storedWatch(
      member: String = "jdoe",
      scope: WatchScope = WatchScope.OWN_JOB,
      queueId: Int? = 10,
      filename: String? = "Job.3mf",
      rearmCount: Int = 0,
  ): EphemeralNotification = runBlocking {
    repository.insert(
        EphemeralNotification(
            id = 0,
            memberUsername = member,
            scope = scope,
            printerId = 1,
            printerName = "Printer 1",
            queueId = queueId,
            filename = filename,
            jobName = filename,
            triggerEvents = listOf("print_complete", "print_failed", "print_stopped"),
            status = WatchStatus.ARMED,
            rearmCount = rearmCount,
            createdAt = LocalDateTime.now(utc).minusHours(1),
            expiresAt = LocalDateTime.now(utc).minusMinutes(1),
            terminalAt = null,
            firedNotificationId = null,
            terminalReason = null))
  }

  private fun ttlMinutesOf(dtoCreatedAt: String, dtoExpiresAt: String): Long =
      java.time.Duration.between(LocalDateTime.parse(dtoCreatedAt), LocalDateTime.parse(dtoExpiresAt))
          .toMinutes()

  // -------------------------------------------------------------------------------------------
  // Arm: own job
  // -------------------------------------------------------------------------------------------

  @Test
  fun `arm own job uses live remaining time for the TTL`() = runBlocking {
    stubApi(queue = listOf(queueItem()), status = printingStatus(remaining = 100))

    val dto = service.arm("jdoe", CreateBambuWatchRequest(scope = "OWN_JOB", printerId = 1, queueId = 10))

    assertEquals("OWN_JOB", dto.scope)
    assertEquals(10, dto.queueId)
    assertEquals(160, ttlMinutesOf(dto.createdAt, dto.expiresAt)) // 100 + 60 grace floor
    assertEquals(1, repository.watches.size)
    assertEquals("Job.3mf", repository.watches.single().filename)
  }

  @Test
  fun `arm own job falls back to the slicer estimate when remaining is zero or missing`() =
      runBlocking {
        stubApi(
            queue = listOf(queueItem(printTimeSeconds = 7200)),
            status = printingStatus(remaining = 0))

        val dto = service.arm("jdoe", CreateBambuWatchRequest("OWN_JOB", 1, 10))

        assertEquals(180, ttlMinutesOf(dto.createdAt, dto.expiresAt)) // estimate 120 + 60 grace
      }

  @Test
  fun `arm own pending job uses the estimate, never the running job's remaining`() = runBlocking {
    stubApi(
        queue = listOf(queueItem(status = "pending", printTimeSeconds = 1800)),
        status = printingStatus(remaining = 999))

    val dto = service.arm("jdoe", CreateBambuWatchRequest("OWN_JOB", 1, 10))

    assertEquals(90, ttlMinutesOf(dto.createdAt, dto.expiresAt)) // estimate 30 + 60 grace
  }

  @Test
  fun `arm rejects when no time estimate exists`() = runBlocking {
    stubApi(
        queue = listOf(queueItem(printTimeSeconds = null)),
        status = printingStatus(remaining = null))

    val e =
        assertFailsWith<BambuWatchException> {
          service.arm("jdoe", CreateBambuWatchRequest("OWN_JOB", 1, 10))
        }
    assertEquals(422, e.statusCode)
    assertTrue(repository.watches.isEmpty())
  }

  @Test
  fun `arm own job rejects another member's print with 403`() = runBlocking {
    stubApi(queue = listOf(queueItem(owner = "asmith")), status = printingStatus())

    val e =
        assertFailsWith<BambuWatchException> {
          service.arm("jdoe", CreateBambuWatchRequest("OWN_JOB", 1, 10))
        }
    assertEquals(403, e.statusCode)
  }

  @Test
  fun `arm own job rejects a finished print with 409 and a vanished one with 404`() = runBlocking {
    stubApi(queue = listOf(queueItem(status = "completed")), status = printingStatus())

    val finished =
        assertFailsWith<BambuWatchException> {
          service.arm("jdoe", CreateBambuWatchRequest("OWN_JOB", 1, 10))
        }
    assertEquals(409, finished.statusCode)

    stubApi(queue = emptyList(), status = printingStatus())
    val vanished =
        assertFailsWith<BambuWatchException> {
          service.arm("jdoe", CreateBambuWatchRequest("OWN_JOB", 1, 10))
        }
    assertEquals(404, vanished.statusCode)
  }

  @Test
  fun `arm rejects an unknown printer with 404 and a missing queue id with 400`() = runBlocking {
    stubApi(printers = emptyList(), queue = emptyList(), status = null)
    val noPrinter =
        assertFailsWith<BambuWatchException> {
          service.arm("jdoe", CreateBambuWatchRequest("OWN_JOB", 99, 10))
        }
    assertEquals(404, noPrinter.statusCode)

    stubApi(queue = listOf(queueItem()), status = printingStatus())
    val noQueueId =
        assertFailsWith<BambuWatchException> {
          service.arm("jdoe", CreateBambuWatchRequest("OWN_JOB", 1, null))
        }
    assertEquals(400, noQueueId.statusCode)
  }

  @Test
  fun `re-clicking arm returns the existing watch without duplicating or resetting its TTL`() =
      runBlocking {
        stubApi(queue = listOf(queueItem()), status = printingStatus(remaining = 100))

        val first = service.arm("jdoe", CreateBambuWatchRequest("OWN_JOB", 1, 10))
        val second = service.arm("jdoe", CreateBambuWatchRequest("OWN_JOB", 1, 10))

        assertEquals(first.id, second.id)
        assertEquals(first.expiresAt, second.expiresAt)
        assertEquals(1, repository.watches.size)
      }

  // -------------------------------------------------------------------------------------------
  // Arm: printer available
  // -------------------------------------------------------------------------------------------

  @Test
  fun `arm printer-available watches the blocking job`() = runBlocking {
    stubApi(
        queue = listOf(queueItem(owner = "asmith")),
        status = printingStatus(remaining = 60))

    val dto = service.arm("jdoe", CreateBambuWatchRequest("PRINTER_AVAILABLE", 1))

    assertEquals("PRINTER_AVAILABLE", dto.scope)
    assertEquals(10, dto.queueId)
    assertEquals(120, ttlMinutesOf(dto.createdAt, dto.expiresAt)) // 60 + 60 grace
  }

  @Test
  fun `arm printer-available rejects idle, finished and offline printers with 409`() = runBlocking {
    for ((state, connected) in
        listOf("IDLE" to true, "FINISH" to true, "FAILED" to true, "IDLE" to false)) {
      stubApi(
          queue = emptyList(),
          status =
              BambuPrinterStatus(id = 1, name = "Printer 1", connected = connected, state = state))
      val e =
          assertFailsWith<BambuWatchException> {
            service.arm("jdoe", CreateBambuWatchRequest("PRINTER_AVAILABLE", 1))
          }
      assertEquals(409, e.statusCode, "state=$state connected=$connected")
    }
    assertTrue(repository.watches.isEmpty())
  }

  @Test
  fun `arm printer-available succeeds on a paused printer`() = runBlocking {
    stubApi(
        queue = listOf(queueItem(owner = "asmith")),
        status =
            BambuPrinterStatus(
                id = 1,
                name = "Printer 1",
                connected = true,
                state = "PAUSE",
                currentPrint = "Job.3mf",
                remainingTimeMinutes = 45))

    val dto = service.arm("jdoe", CreateBambuWatchRequest("PRINTER_AVAILABLE", 1))

    assertEquals("PRINTER_AVAILABLE", dto.scope)
    assertEquals(10, dto.queueId)
  }

  @Test
  fun `re-clicking a printer-available arm returns the existing watch without duplicating`() =
      runBlocking {
        stubApi(queue = listOf(queueItem(owner = "asmith")), status = printingStatus(remaining = 60))

        val first = service.arm("jdoe", CreateBambuWatchRequest("PRINTER_AVAILABLE", 1))
        val second = service.arm("jdoe", CreateBambuWatchRequest("PRINTER_AVAILABLE", 1))

        assertEquals(first.id, second.id)
        assertEquals(first.expiresAt, second.expiresAt)
        assertEquals(1, repository.watches.size)
      }

  @Test
  fun `arm surfaces a Bambuddy API failure as a friendly 502 without upstream detail`() =
      runBlocking {
        whenever(apiClient.getPrinters())
            .thenThrow(
                org.dallasmakerspace.bambuddy.BambuddyApiException(
                    "Failed to fetch Bambuddy printers: 500 - upstream stack trace detail"))

        val e =
            assertFailsWith<BambuWatchException> {
              service.arm("jdoe", CreateBambuWatchRequest("OWN_JOB", 1, 10))
            }
        assertEquals(502, e.statusCode)
        assertTrue(e.message!!.contains("try again", ignoreCase = true))
        assertTrue(!e.message!!.contains("upstream"))
      }

  @Test
  fun `arm printer-available on a printing printer with no queue row says try again, not offline`() =
      runBlocking {
        stubApi(queue = emptyList(), status = printingStatus())

        val e =
            assertFailsWith<BambuWatchException> {
              service.arm("jdoe", CreateBambuWatchRequest("PRINTER_AVAILABLE", 1))
            }
        assertEquals(409, e.statusCode)
        assertTrue(e.message!!.contains("can't tell which print"))
      }

  // -------------------------------------------------------------------------------------------
  // Cancel / list
  // -------------------------------------------------------------------------------------------

  @Test
  fun `cancel is owner-scoped and idempotent`() = runBlocking {
    val watch = storedWatch()

    assertNull(service.cancel("asmith", watch.id)) // not the owner: indistinguishable from missing
    val cancelled = service.cancel("jdoe", watch.id)
    assertEquals("CANCELLED", cancelled?.status)
    assertEquals("CANCELLED", service.cancel("jdoe", watch.id)?.status) // terminal: still 200 DTO
    assertTrue(service.listArmed("jdoe").isEmpty())
  }

  // -------------------------------------------------------------------------------------------
  // Fire
  // -------------------------------------------------------------------------------------------

  @Test
  fun `fire creates the finished notice once, with consent override and a per-watch dedupe key`() =
      runBlocking {
        val watch = storedWatch()

        assertTrue(service.fireWatch(watch, "print_complete", null, LocalDateTime.now(utc)))
        assertFalse(service.fireWatch(watch, "print_complete", null, LocalDateTime.now(utc)))

        assertEquals(1, createdRequests.size)
        val request = createdRequests.single()
        assertEquals(BambuPrintFinishedTopic, request.topic)
        assertEquals("bambu-print-finished", request.templateName)
        assertEquals("bambu:1:10:print_complete:${watch.id}", request.dedupeKey)
        assertTrue(request.consentOverride)
        val stored = assertNotNull(repository.getById(watch.id))
        assertEquals(WatchStatus.FIRED, stored.status)
        assertNotNull(stored.firedNotificationId)
          Unit
  }

  @Test
  fun `two members watching the same job both fire - dedupe keys differ by watch`() = runBlocking {
    val mine = storedWatch(member = "jdoe")
    val theirs = storedWatch(member = "asmith")

    assertTrue(service.fireWatch(mine, "print_complete", null, LocalDateTime.now(utc)))
    assertTrue(service.fireWatch(theirs, "print_complete", null, LocalDateTime.now(utc)))

    assertEquals(2, createdRequests.size)
    assertEquals(
        setOf("jdoe", "asmith"), createdRequests.map { it.memberUsername }.toSet())
    assertEquals(2, createdRequests.map { it.dedupeKey }.distinct().size)
  }

  @Test
  fun `fire with a webhook payload carries reason, duration and filament into the notice payloads`() =
      runBlocking {
        // Reason flows into the action-required payload; duration/filament into the finished
        // payload (those are the fields each template's data class declares).
        val failedWatch = storedWatch()
        val failedPayload =
            org.dallasmakerspace.bambuddy.notifications.webhook.BambuddyWebhookPayload(
                event = "print_failed",
                printer = "Printer 1",
                filename = "Job.3mf",
                reason = "Filament runout",
                duration = "1h 5m",
                filamentGrams = 12.5,
                progress = 87.0)
        assertTrue(service.fireWatch(failedWatch, "print_failed", failedPayload, LocalDateTime.now(utc)))
        assertTrue(createdRequests[0].payload!!.contains("Filament runout"), createdRequests[0].payload)

        val finishedWatch = storedWatch()
        val finishedPayload =
            failedPayload.copy(event = "print_complete", reason = null)
        assertTrue(
            service.fireWatch(finishedWatch, "print_complete", finishedPayload, LocalDateTime.now(utc)))
        val finishedRequestPayload = createdRequests[1].payload!!
        assertTrue(finishedRequestPayload.contains("1h 5m"), finishedRequestPayload)
        assertTrue(finishedRequestPayload.contains("12.5 g"), finishedRequestPayload)
      }

  @Test
  fun `failed and stopped events use the action-required topic`() = runBlocking {
    val failed = storedWatch()
    val stopped = storedWatch()

    assertTrue(service.fireWatch(failed, "print_failed", null, LocalDateTime.now(utc)))
    assertTrue(service.fireWatch(stopped, "print_stopped", null, LocalDateTime.now(utc)))

    assertEquals(2, createdRequests.size)
    assertTrue(createdRequests.all { it.topic == BambuPrintActionRequiredTopic })
    assertTrue(createdRequests.all { it.templateName == "bambu-print-action-required" })
    assertTrue(createdRequests[0].payload!!.contains("\"failed\""))
    assertTrue(createdRequests[1].payload!!.contains("was stopped"))
  }

  @Test
  fun `an available watch fires the available notice whatever the ending event`() = runBlocking {
    val watch = storedWatch(scope = WatchScope.PRINTER_AVAILABLE, member = "asmith")

    assertTrue(service.fireWatch(watch, "print_failed", null, LocalDateTime.now(utc)))

    val request = createdRequests.single()
    assertEquals(BambuPrinterAvailableTopic, request.topic)
    assertEquals("bambu-printer-available", request.templateName)
  }

  // -------------------------------------------------------------------------------------------
  // Expiry decision table
  // -------------------------------------------------------------------------------------------

  @Test
  fun `expiry fires the real notice when the job ended while its webhook was lost`() = runBlocking {
    val watch = storedWatch()
    stubApi(queue = listOf(queueItem(status = "completed")), status = printingStatus())

    assertEquals(ExpiryOutcome.FIRED, service.processExpiredWatch(watch))

    assertEquals(WatchStatus.FIRED, repository.getById(watch.id)?.status)
    assertEquals(BambuPrintFinishedTopic, createdRequests.single().topic)
    assertEquals("bambu:1:10:print_complete:${watch.id}", createdRequests.single().dedupeKey)
  }

  @Test
  fun `expiry fires the action-required notice when the job failed meanwhile`() = runBlocking {
    val watch = storedWatch()
    stubApi(queue = listOf(queueItem(status = "failed")), status = printingStatus())

    assertEquals(ExpiryOutcome.FIRED, service.processExpiredWatch(watch))
    assertEquals(BambuPrintActionRequiredTopic, createdRequests.single().topic)
  }

  @Test
  fun `expiry while still running sends the overdue notice and re-arms exactly once`() =
      runBlocking {
        val watch = storedWatch()
        stubApi(queue = listOf(queueItem(status = "printing")), status = printingStatus(remaining = 30))

        assertEquals(ExpiryOutcome.OVERDUE_REARMED, service.processExpiredWatch(watch))

        val stored = assertNotNull(repository.getById(watch.id))
        assertEquals(WatchStatus.ARMED, stored.status)
        assertEquals(1, stored.rearmCount)
        val ttl = java.time.Duration.between(LocalDateTime.now(utc), stored.expiresAt).toMinutes()
        assertTrue(ttl in 80..100, "fresh TTL from remaining 30m should be ~90m, was $ttl")
        val request = createdRequests.single()
        assertEquals(BambuWatchTopic, request.topic)
        assertEquals("bambu-watch-overdue", request.templateName)
        assertEquals("bambu:watch:${watch.id}:overdue", request.dedupeKey)
        assertTrue(request.consentOverride)
      }

  @Test
  fun `a re-armed watch that expires again expires for real - no second re-arm`() = runBlocking {
    val watch = storedWatch(rearmCount = 1)
    stubApi(queue = listOf(queueItem(status = "printing")), status = printingStatus(remaining = 30))

    assertEquals(ExpiryOutcome.EXPIRED, service.processExpiredWatch(watch))

    val stored = assertNotNull(repository.getById(watch.id))
    assertEquals(WatchStatus.EXPIRED, stored.status)
    assertEquals(1, stored.rearmCount)
    val request = createdRequests.single()
    assertEquals("bambu-watch-expired", request.templateName)
    assertEquals("bambu:watch:${watch.id}:expired", request.dedupeKey)
  }

  @Test
  fun `expiry with Bambuddy unreachable sends the check-yourself notice and expires, no re-arm`() =
      runBlocking {
        val watch = storedWatch()
        runBlocking {
          whenever(apiClient.getQueue()).thenThrow(RuntimeException("connection refused"))
          whenever(apiClient.getPrinterStatus(any()))
              .thenThrow(RuntimeException("connection refused"))
        }

        assertEquals(ExpiryOutcome.EXPIRED, service.processExpiredWatch(watch))

        assertEquals(WatchStatus.EXPIRED, repository.getById(watch.id)?.status)
        assertEquals("bambu-watch-expired", createdRequests.single().templateName)
      }

  @Test
  fun `expiry with the watched job gone expires with a notice`() = runBlocking {
    val watch = storedWatch()
    stubApi(
        queue = emptyList(),
        status = BambuPrinterStatus(id = 1, name = "Printer 1", connected = true, state = "IDLE"))

    assertEquals(ExpiryOutcome.EXPIRED, service.processExpiredWatch(watch))
    assertEquals(WatchStatus.EXPIRED, repository.getById(watch.id)?.status)
    assertEquals("bambu-watch-expired", createdRequests.single().templateName)
  }

  @Test
  fun `available watch expiry fires when the blocking job is gone and the printer is free`() =
      runBlocking {
        val watch = storedWatch(scope = WatchScope.PRINTER_AVAILABLE, member = "asmith")
        stubApi(
            queue = emptyList(),
            status =
                BambuPrinterStatus(id = 1, name = "Printer 1", connected = true, state = "FINISH"))

        assertEquals(ExpiryOutcome.FIRED, service.processExpiredWatch(watch))
        assertEquals(BambuPrinterAvailableTopic, createdRequests.single().topic)
      }

  @Test
  fun `available watch re-arms onto the new blocking job when another print is running`() =
      runBlocking {
        val watch = storedWatch(scope = WatchScope.PRINTER_AVAILABLE, member = "asmith")
        stubApi(
            queue =
                listOf(
                    queueItem(id = 22, status = "printing", owner = "jdoe", archiveName = "Next.3mf")),
            status = printingStatus(remaining = 45, currentPrint = "Next.3mf"))

        assertEquals(ExpiryOutcome.OVERDUE_REARMED, service.processExpiredWatch(watch))

        val stored = assertNotNull(repository.getById(watch.id))
        assertEquals(22, stored.queueId)
        assertEquals("Next.3mf", stored.filename)
        assertEquals(1, stored.rearmCount)
      }

  @Test
  fun `pending own-job re-arm takes its TTL from the estimate, not the other job's remaining`() =
      runBlocking {
        val watch = storedWatch(queueId = 20)
        stubApi(
            queue =
                listOf(
                    queueItem(id = 99, status = "printing", owner = "asmith", archiveName = "Other.3mf"),
                    queueItem(id = 20, status = "pending", printTimeSeconds = 3600)),
            status = printingStatus(remaining = 500, currentPrint = "Other.3mf"))

        assertEquals(ExpiryOutcome.OVERDUE_REARMED, service.processExpiredWatch(watch))

        val stored = assertNotNull(repository.getById(watch.id))
        val ttl = java.time.Duration.between(LocalDateTime.now(utc), stored.expiresAt).toMinutes()
        // Estimate 60m + 60m grace = ~120m; the other job's remaining would give ~680m.
        assertTrue(ttl in 100..140, "expected ~120m from the estimate, was $ttl")
      }

  @Test
  fun `expiry discards its outcome when the watch was already consumed mid-check`() = runBlocking {
    val watch = storedWatch()
    // The webhook fired the watch after the sweeper picked it up: the stored row is FIRED,
    // the copy the sweeper holds still says ARMED.
    repository.watches[0] = watch.copy(status = WatchStatus.FIRED)
    stubApi(queue = listOf(queueItem(status = "completed")), status = printingStatus())

    assertEquals(ExpiryOutcome.CLAIM_LOST, service.processExpiredWatch(watch))
    assertTrue(createdRequests.isEmpty())
  }

  @Test
  fun `expiry with no fresh estimate sends the terminal overdue notice then expires, no re-arm`() =
      runBlocking {
        // QA F2 / design §10.2 no-fresh-estimate branch: the job is still printing, but neither
        // the live remaining time nor the slicer estimate can produce a new TTL.
        val watch = storedWatch()
        stubApi(
            queue = listOf(queueItem(status = "printing", printTimeSeconds = null)),
            status = printingStatus(remaining = null))

        assertEquals(ExpiryOutcome.EXPIRED, service.processExpiredWatch(watch))

        val stored = assertNotNull(repository.getById(watch.id))
        assertEquals(WatchStatus.EXPIRED, stored.status)
        assertEquals(0, stored.rearmCount) // no re-arm happened
        assertEquals(2, createdRequests.size)
        assertEquals("bambu-watch-overdue", createdRequests[0].templateName)
        // The first notice is the terminal variant: it must not claim we're still watching.
        assertTrue(createdRequests[0].payload!!.contains("\"stillWatching\":false"))
        assertEquals("bambu-watch-expired", createdRequests[1].templateName)
        assertEquals("bambu:watch:${watch.id}:expired", createdRequests[1].dedupeKey)
      }

  @Test
  fun `expiry drives its decision from the status when only the queue read fails`() = runBlocking {
    // P1: the "unreachable" branch requires BOTH reads to fail. Here the queue read fails
    // but the status read succeeds and shows the watched job still printing.
    val watch = storedWatch()
    runBlocking {
      whenever(apiClient.getQueue()).thenThrow(RuntimeException("queue endpoint down"))
      whenever(apiClient.getPrinterStatus(any())).thenReturn(printingStatus(remaining = 30))
    }

    assertEquals(ExpiryOutcome.OVERDUE_REARMED, service.processExpiredWatch(watch))
    assertEquals(WatchStatus.ARMED, repository.getById(watch.id)?.status)
    assertEquals(1, repository.getById(watch.id)?.rearmCount)
  }
}
