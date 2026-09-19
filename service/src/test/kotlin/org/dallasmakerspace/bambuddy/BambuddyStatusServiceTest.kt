package org.dallasmakerspace.bambuddy

import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.LoggerFactory
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class BambuddyStatusServiceTest {

  private lateinit var bambuddyApiClient: IBambuddyApiClient
  private lateinit var service: BambuddyStatusService

  @Before
  fun setUp() {
    bambuddyApiClient = mock()
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(BambuddyStatusService::class.java)).thenReturn(mock<Logger>())
    service = BambuddyStatusService(bambuddyApiClient, loggerFactory)
  }

  private fun printer(id: Int) = BambuPrinter(id = id, name = "Printer $id")

  private suspend fun stubStatus(
      id: Int,
      state: String,
      connected: Boolean = true,
      currentPrint: String? = null,
  ) {
    whenever(bambuddyApiClient.getPrinterStatus(id))
        .thenReturn(
            BambuPrinterStatus(
                id = id, name = "Printer $id", connected = connected, state = state, currentPrint = currentPrint))
  }

  @Test
  fun `available excludes printing, offline, finished, and needs-attention states`() = runBlocking {
    whenever(bambuddyApiClient.getPrinters())
        .thenReturn(listOf(1, 2, 3, 4, 5, 6).map(::printer))
    stubStatus(1, "IDLE")
    stubStatus(2, "RUNNING")
    stubStatus(3, "PAUSE")
    stubStatus(4, "IDLE", connected = false) // unreachable, despite a stale "IDLE" state
    stubStatus(5, "FAILED")
    stubStatus(6, "FINISH")
    whenever(bambuddyApiClient.getQueue()).thenReturn(emptyList())

    val summary = service.getSummary()

    assertEquals(1, summary.available)
    assertEquals(2, summary.printing)
    assertEquals(1, summary.needsAttention)
    assertEquals(1, summary.finished)
    assertEquals(1, summary.offline)
    assertEquals(6, summary.total)
  }

  @Test
  fun `FINISH counts as its own finished bucket, not needs-attention`() = runBlocking {
    // A Bambu printer's firmware only reports whether a new print has started since the last one
    // finished - not whether a person has physically cleared the bed - so FINISH must not inflate
    // the "needs attention" count the way an actual failure does.
    whenever(bambuddyApiClient.getPrinters()).thenReturn(listOf(printer(1)))
    stubStatus(1, "FINISH")
    whenever(bambuddyApiClient.getQueue()).thenReturn(emptyList())

    val summary = service.getSummary()

    assertEquals(0, summary.needsAttention)
    assertEquals(1, summary.finished)
  }

  @Test
  fun `a printer whose status call fails entirely counts as offline, not available`() = runBlocking {
    whenever(bambuddyApiClient.getPrinters()).thenReturn(listOf(printer(1)))
    whenever(bambuddyApiClient.getPrinterStatus(1)).thenReturn(null)
    whenever(bambuddyApiClient.getQueue()).thenReturn(emptyList())

    val summary = service.getSummary()

    assertEquals(0, summary.available)
    assertEquals(1, summary.offline)
  }

  @Test
  fun `an unrecognized firmware state fails toward offline rather than available`() = runBlocking {
    whenever(bambuddyApiClient.getPrinters()).thenReturn(listOf(printer(1)))
    stubStatus(1, "SOME_NEW_FIRMWARE_STATE")
    whenever(bambuddyApiClient.getQueue()).thenReturn(emptyList())

    val summary = service.getSummary()

    assertEquals(0, summary.available)
    assertEquals(1, summary.offline)
  }

  @Test
  fun `getPrinterDetails reads the current job from status and the next job from the queue`() =
      runBlocking {
        whenever(bambuddyApiClient.getPrinters()).thenReturn(listOf(printer(1)))
        stubStatus(1, "RUNNING", currentPrint = "Current.3mf")
        whenever(bambuddyApiClient.getQueue())
            .thenReturn(
                listOf(
                    BambuQueueItem(
                        id = 1,
                        printerId = 1,
                        status = "printing",
                        archiveName = "Current.3mf",
                        position = 1,
                    ),
                    BambuQueueItem(
                        id = 2,
                        printerId = 1,
                        status = "pending",
                        archiveName = "Next.3mf",
                        position = 2,
                    ),
                ))

        val details = service.getPrinterDetails()

        assertEquals(1, details.size)
        assertEquals("Current.3mf", details[0].currentJobName)
        assertEquals("Next.3mf", details[0].nextJobName)
      }

  @Test
  fun `getPrinterDetails surfaces who queued the currently-printing job`() = runBlocking {
    whenever(bambuddyApiClient.getPrinters()).thenReturn(listOf(printer(1)))
    stubStatus(1, "RUNNING", currentPrint = "Current.3mf")
    whenever(bambuddyApiClient.getQueue())
        .thenReturn(
            listOf(
                BambuQueueItem(
                    id = 1,
                    printerId = 1,
                    status = "printing",
                    archiveName = "Current.3mf",
                    position = 1,
                    createdByUsername = "jdoe",
                )))

    val details = service.getPrinterDetails()

    assertEquals("jdoe", details[0].printedByUsername)
  }

  @Test
  fun `getPrinterDetails surfaces who queued the job when it just FAILED`() = runBlocking {
    // A failed print's queue item moves straight to "failed" - it never lingers at "printing" -
    // so printedByUsername must also recognize "failed" as the active job, not just "printing".
    whenever(bambuddyApiClient.getPrinters()).thenReturn(listOf(printer(1)))
    stubStatus(1, "FAILED")
    whenever(bambuddyApiClient.getQueue())
        .thenReturn(
            listOf(
                BambuQueueItem(
                    id = 1,
                    printerId = 1,
                    status = "failed",
                    archiveName = "Bracket v3",
                    createdByUsername = "asmith",
                )))

    val details = service.getPrinterDetails()

    assertEquals("asmith", details[0].printedByUsername)
  }

  @Test
  fun `getPrinterDetails surfaces who ran the most recently completed job when FINISHed`() =
      runBlocking {
        whenever(bambuddyApiClient.getPrinters()).thenReturn(listOf(printer(1)))
        stubStatus(1, "FINISH")
        whenever(bambuddyApiClient.getQueue())
            .thenReturn(
                listOf(
                    BambuQueueItem(
                        id = 1,
                        printerId = 1,
                        status = "completed",
                        archiveName = "Older.3mf",
                        createdByUsername = "jdoe",
                        completedAt = "2026-09-16T12:00:00Z",
                    ),
                    BambuQueueItem(
                        id = 2,
                        printerId = 1,
                        status = "completed",
                        archiveName = "Newer.3mf",
                        createdByUsername = "asmith",
                        completedAt = "2026-09-17T08:00:00Z",
                    ),
                ))

        val details = service.getPrinterDetails()

        assertEquals("asmith", details[0].previousPrintedByUsername)
      }

  @Test
  fun `the next job is the lowest-position pending item, not just the first one returned`() =
      runBlocking {
        whenever(bambuddyApiClient.getPrinters()).thenReturn(listOf(printer(1)))
        stubStatus(1, "IDLE")
        whenever(bambuddyApiClient.getQueue())
            .thenReturn(
                listOf(
                    BambuQueueItem(id = 2, printerId = 1, status = "pending", archiveName = "Second.3mf", position = 2),
                    BambuQueueItem(id = 1, printerId = 1, status = "pending", archiveName = "First.3mf", position = 1),
                ))

        val details = service.getPrinterDetails()

        assertEquals("First.3mf", details[0].nextJobName)
      }
}
