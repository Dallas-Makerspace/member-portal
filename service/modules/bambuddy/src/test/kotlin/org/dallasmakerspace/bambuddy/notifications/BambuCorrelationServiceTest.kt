package org.dallasmakerspace.bambuddy.notifications

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.bambuddy.BambuPrinter
import org.dallasmakerspace.bambuddy.BambuQueueItem
import org.dallasmakerspace.bambuddy.IBambuddyApiClient
import org.dallasmakerspace.bambuddy.notifications.db.EphemeralNotificationsRepository
import org.dallasmakerspace.bambuddy.notifications.webhook.BambuddyWebhookPayload
import org.dallasmakerspace.core.LoggerFactory
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class BambuCorrelationServiceTest {

  private lateinit var apiClient: IBambuddyApiClient
  private lateinit var watchRepository: EphemeralNotificationsRepository
  private lateinit var service: BambuCorrelationService

  @Before
  fun setUp() {
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(mock<Logger>())
    apiClient = mock()
    watchRepository = mock()
    service = BambuCorrelationService(apiClient, watchRepository, loggerFactory)
  }

  private fun watch(
      id: Long,
      scope: WatchScope = WatchScope.OWN_JOB,
      queueId: Int? = 10,
      filename: String? = "Job.3mf",
  ) =
      EphemeralNotification(
          id = id,
          memberUsername = "member$id",
          scope = scope,
          printerId = 1,
          printerName = "Printer 1",
          queueId = queueId,
          filename = filename,
          jobName = filename,
          triggerEvents = listOf("print_complete", "print_failed", "print_stopped"),
          status = WatchStatus.ARMED,
          rearmCount = 0,
          createdAt = LocalDateTime.now(ZoneId.of("UTC")),
          expiresAt = LocalDateTime.now(ZoneId.of("UTC")).plusHours(2),
          terminalAt = null,
          firedNotificationId = null,
          terminalReason = null)

  private fun payload(event: String = "print_complete", filename: String? = "Job.3mf") =
      BambuddyWebhookPayload(event = event, printer = "Printer 1", filename = filename)

  private fun stubLookup(queue: List<BambuQueueItem>, watches: List<EphemeralNotification>) =
      runBlocking {
        whenever(apiClient.getPrinters()).thenReturn(listOf(BambuPrinter(id = 1, name = "Printer 1")))
        whenever(apiClient.getQueue()).thenReturn(queue)
        whenever(watchRepository.findArmedByPrinter(1)).thenReturn(watches)
        whenever(watchRepository.findArmedByPrinterName("Printer 1")).thenReturn(watches)
      }

  private fun row(id: Int, status: String, archiveName: String? = "Job.3mf") =
      BambuQueueItem(id = id, printerId = 1, status = status, archiveName = archiveName)

  @Test
  fun `exact queue-id match with a terminal row fires the watch`() = runBlocking {
    val w = watch(1)
    stubLookup(listOf(row(10, "completed")), listOf(w))

    val result = service.correlatePrintEvent(payload())

    assertEquals(CorrelationMode.FIRED, result.mode)
    assertEquals(listOf(w), result.watches)
  }

  @Test
  fun `a same-filename reprint on a different queue row does not fire the watch`() = runBlocking {
    // The lingering-FINISH trap: queue history is full of completed rows with this filename.
    // Only the watch's own queue row counts - it is still printing, so nothing fires.
    val w = watch(1, queueId = 10)
    stubLookup(
        listOf(row(9, "completed"), row(10, "printing"), row(11, "completed")),
        listOf(w))

    val result = service.correlatePrintEvent(payload())

    assertTrue(result.watches.isEmpty())
    assertEquals(CorrelationMode.STATE_MISMATCH, result.mode)
  }

  @Test
  fun `filename-only fallback fires when exactly one watch's row has vanished`() = runBlocking {
    val w = watch(1, queueId = 10)
    stubLookup(emptyList(), listOf(w)) // queue row pruned entirely

    val result = service.correlatePrintEvent(payload())

    assertEquals(CorrelationMode.FILENAME_ONLY, result.mode)
    assertEquals(listOf(w), result.watches)
  }

  @Test
  fun `filename-only fallback refuses when two vanished watches share the filename`() =
      runBlocking {
        stubLookup(emptyList(), listOf(watch(1, queueId = 10), watch(2, queueId = 11)))

        val result = service.correlatePrintEvent(payload())

        assertEquals(CorrelationMode.NO_WATCH, result.mode)
        assertTrue(result.watches.isEmpty())
      }

  @Test
  fun `unreachable Bambuddy falls back to firing from stored watch fields`() = runBlocking {
    val w = watch(1)
    runBlocking {
      whenever(apiClient.getPrinters()).thenReturn(listOf(BambuPrinter(id = 1, name = "Printer 1")))
      whenever(apiClient.getQueue()).thenThrow(RuntimeException("timeout"))
      whenever(watchRepository.findArmedByPrinter(1)).thenReturn(listOf(w))
    }

    val result = service.correlatePrintEvent(payload())

    assertEquals(CorrelationMode.UNVERIFIED, result.mode)
    assertEquals(listOf(w), result.watches)
  }

  @Test
  fun `a row still printing is a state mismatch, not a fire - the TTL backstop resolves it`() =
      runBlocking {
        val w = watch(1)
        stubLookup(listOf(row(10, "printing")), listOf(w))

        val result = service.correlatePrintEvent(payload(event = "print_failed"))

        assertEquals(CorrelationMode.STATE_MISMATCH, result.mode)
        assertTrue(result.watches.isEmpty())
      }

  @Test
  fun `missing filename - own-job watch does not fire even with a terminal row`() = runBlocking {
    // Design §7.4: without the payload filename we cannot prove the ended job is the watched
    // one, so own-job watches stay ARMED for the expiry backstop.
    val w = watch(1)
    stubLookup(listOf(row(10, "completed")), listOf(w))

    val result = service.correlatePrintEvent(payload(filename = null))

    assertTrue(result.watches.isEmpty())
  }

  @Test
  fun `missing filename - an available watch still fires when its blocking row is terminal`() =
      runBlocking {
        val w = watch(1, scope = WatchScope.PRINTER_AVAILABLE)
        stubLookup(listOf(row(10, "completed")), listOf(w))

        val result = service.correlatePrintEvent(payload(filename = null))

        assertEquals(CorrelationMode.FIRED, result.mode)
        assertEquals(listOf(w), result.watches)
      }

  @Test
  fun `an event outside the watch's trigger set does not fire it`() = runBlocking {
    val w = watch(1)
    stubLookup(listOf(row(10, "completed")), listOf(w))

    val result = service.correlatePrintEvent(payload(event = "print_start"))

    assertTrue(result.watches.isEmpty())
  }

  @Test
  fun `unknown printer name resolves to no match`() = runBlocking {
    runBlocking {
      whenever(apiClient.getPrinters()).thenReturn(listOf(BambuPrinter(id = 2, name = "Other")))
      whenever(apiClient.getQueue()).thenReturn(emptyList())
    }

    val result = service.correlatePrintEvent(payload())

    assertEquals(CorrelationMode.NO_PRINTER, result.mode)
  }

  @Test
  fun `printer resolution is exact first, then unique case-insensitive`() {
    val printers = listOf(1 to "Printer 1", 2 to "printer 2")
    assertEquals(1, service.resolvePrinterId(printers, "Printer 1"))
    assertEquals(2, service.resolvePrinterId(printers, "PRINTER 2"))
    assertEquals(null, service.resolvePrinterId(printers, "Missing"))
    // Ambiguous case-insensitive matches resolve to nothing rather than guessing.
    assertEquals(null, service.resolvePrinterId(listOf(1 to "Same", 2 to "SAME"), "same"))
  }

  @Test
  fun `print_stopped fires with a failed queue row`() = runBlocking {
    val w = watch(1)
    stubLookup(listOf(row(10, "failed")), listOf(w))

    val result = service.correlatePrintEvent(payload(event = "print_stopped"))

    assertEquals(CorrelationMode.FIRED, result.mode)
    assertEquals(listOf(w), result.watches)
  }

  @Test
  fun `print_stopped fires with a completed queue row`() = runBlocking {
    val w = watch(1)
    stubLookup(listOf(row(10, "completed")), listOf(w))

    val result = service.correlatePrintEvent(payload(event = "print_stopped"))

    assertEquals(CorrelationMode.FIRED, result.mode)
    assertEquals(listOf(w), result.watches)
  }

  @Test
  fun `print_stopped fires with a cancelled queue row`() = runBlocking {
    val w = watch(1)
    stubLookup(listOf(row(10, "cancelled")), listOf(w))

    val result = service.correlatePrintEvent(payload(event = "print_stopped"))

    assertEquals(CorrelationMode.FIRED, result.mode)
    assertEquals(listOf(w), result.watches)
  }

  @Test
  fun `an own-job watch on a skipped row never fires from a print event`() = runBlocking {
    stubLookup(listOf(row(10, "skipped")), listOf(watch(1)))

    val result = service.correlatePrintEvent(payload(event = "print_stopped"))

    assertTrue(result.watches.isEmpty())
  }

  @Test
  fun `an available watch fires when its blocking row ended in any way`() = runBlocking {
    for (status in listOf("completed", "failed", "cancelled", "skipped")) {
      val w = watch(1, scope = WatchScope.PRINTER_AVAILABLE)
      stubLookup(listOf(row(10, status)), listOf(w))
      val result = service.correlatePrintEvent(payload(event = "print_complete"))
      assertEquals(CorrelationMode.FIRED, result.mode, status)
      assertEquals(listOf(w), result.watches, status)
    }
  }

  @Test
  fun `webhook names with spaces match queue names with underscores and extensions`() =
      runBlocking {
        // BamBuddy sends subtask_name with "_" turned into " "; the queue keeps the original.
        val w = watch(1, filename = "Cable_Clip_v2")
        stubLookup(listOf(row(10, "completed", archiveName = "Cable_Clip_v2")), listOf(w))
        val result = service.correlatePrintEvent(payload(filename = "Cable Clip v2"))
        assertEquals(CorrelationMode.FIRED, result.mode)

        // Vanished row: the stored (queue-side) name still matches the webhook form.
        stubLookup(emptyList(), listOf(w))
        assertEquals(
            CorrelationMode.FILENAME_ONLY,
            service.correlatePrintEvent(payload(filename = "cable clip V2.gcode.3mf")).mode)
      }

  @Test
  fun `unverified fallback matches across the underscore and extension forms`() = runBlocking {
    val w = watch(1, filename = "Cable_Clip_v2.gcode.3mf")
    runBlocking {
      whenever(apiClient.getPrinters()).thenReturn(listOf(BambuPrinter(id = 1, name = "Printer 1")))
      whenever(apiClient.getQueue()).thenThrow(RuntimeException("timeout"))
      whenever(watchRepository.findArmedByPrinter(1)).thenReturn(listOf(w))
    }

    val result = service.correlatePrintEvent(payload(filename = "Cable Clip v2"))

    assertEquals(CorrelationMode.UNVERIFIED, result.mode)
  }

  @Test
  fun `a state mismatch on one watch does not suppress another watch's filename-only fire`() =
      runBlocking {
        // Watch 1's row is still printing (state mismatch); watch 2's row vanished and it is
        // the single filename match — the filename-only fallback must still fire it.
        val mismatched = watch(1, queueId = 10)
        val vanished = watch(2, queueId = 11)
        stubLookup(listOf(row(10, "printing")), listOf(mismatched, vanished))

        val result = service.correlatePrintEvent(payload())

        assertEquals(CorrelationMode.FILENAME_ONLY, result.mode)
        assertEquals(listOf(vanished), result.watches)
      }

  @Test
  fun `unverified fallback fires none when two watches share printer and filename`() = runBlocking {
    // Same-filename reprints are indistinguishable without the queue anchor: fire none and
    // let the TTL backstop resolve it.
    runBlocking {
      whenever(apiClient.getPrinters()).thenReturn(listOf(BambuPrinter(id = 1, name = "Printer 1")))
      whenever(apiClient.getQueue()).thenThrow(RuntimeException("timeout"))
      whenever(watchRepository.findArmedByPrinter(1))
          .thenReturn(listOf(watch(1, queueId = 10), watch(2, queueId = 11)))
    }

    val result = service.correlatePrintEvent(payload())

    assertEquals(CorrelationMode.NO_WATCH, result.mode)
    assertTrue(result.watches.isEmpty())
  }

  @Test
  fun `unverified fallback never fires when the payload filename is null`() = runBlocking {
    runBlocking {
      whenever(apiClient.getPrinters()).thenReturn(listOf(BambuPrinter(id = 1, name = "Printer 1")))
      whenever(apiClient.getQueue()).thenThrow(RuntimeException("timeout"))
      whenever(watchRepository.findArmedByPrinter(1)).thenReturn(listOf(watch(1)))
    }

    val result = service.correlatePrintEvent(payload(filename = null))

    assertEquals(CorrelationMode.UNVERIFIED_NO_FILENAME, result.mode)
    assertTrue(result.watches.isEmpty())
  }

  @Test
  fun `terminal consistency follows the event, with stopped accepting either terminal status`() {
    assertTrue(service.isTerminalConsistent("completed", "print_complete"))
    assertTrue(!service.isTerminalConsistent("failed", "print_complete"))
    assertTrue(service.isTerminalConsistent("failed", "print_failed"))
    assertTrue(service.isTerminalConsistent("failed", "print_stopped"))
    assertTrue(service.isTerminalConsistent("completed", "print_stopped"))
    assertTrue(service.isTerminalConsistent("cancelled", "print_stopped"))
    assertTrue(!service.isTerminalConsistent("cancelled", "print_complete"))
    assertTrue(!service.isTerminalConsistent("cancelled", "print_failed"))
    assertTrue(!service.isTerminalConsistent("skipped", "print_stopped"))
    assertTrue(!service.isTerminalConsistent("printing", "print_stopped"))
  }
}
