package org.dallasmakerspace.bambuddy

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.LoggerFactory

@Singleton
class BambuddyApiClientMock @Inject constructor(loggerFactory: LoggerFactory) :
    IBambuddyApiClient {
  private val log = loggerFactory.create(javaClass)

  private val statusByPrinterId =
      mapOf(
          1 to
              BambuPrinterStatus(
                  id = 1,
                  name = "Printer 1",
                  state = "RUNNING",
                  currentPrint = "Bracket v3",
                  progress = 42.0,
                  remainingTimeMinutes = 45,
                  layerNum = 118,
                  totalLayers = 280,
              ),
          2 to BambuPrinterStatus(id = 2, name = "Printer 2", state = "IDLE"),
          3 to
              BambuPrinterStatus(
                  id = 3,
                  name = "Printer 3",
                  state = "RUNNING",
                  currentPrint = "Enclosure lid",
                  progress = 87.0,
                  remainingTimeMinutes = 8,
                  layerNum = 640,
                  totalLayers = 700,
              ),
          // Demonstrates the "unreachable" state - Bambuddy itself reports connected=false when
          // it can't reach the printer over the LAN, distinct from a printer that's simply IDLE.
          4 to BambuPrinterStatus(id = 4, name = "Printer 4", connected = false, state = "IDLE"),
          // Finished but not yet cleared - still occupies the bed, so it must not count as
          // "available" even though nothing is actively printing.
          5 to
              BambuPrinterStatus(
                  id = 5,
                  name = "Printer 5",
                  state = "FINISH",
                  currentPrint = "Test cube",
                  progress = 100.0,
                  layerNum = 200,
                  totalLayers = 200,
              ),
          // Needs human attention - also must not count as "available".
          6 to BambuPrinterStatus(id = 6, name = "Printer 6", state = "FAILED"),
      )

  override suspend fun getPrinters(): List<BambuPrinter> {
    log.info("Mock: getPrinters")
    return listOf(
        BambuPrinter(id = 1, name = "Printer 1", model = "X1C"),
        BambuPrinter(id = 2, name = "Printer 2", model = "P1S"),
        BambuPrinter(id = 3, name = "Printer 3", model = "X1C"),
        BambuPrinter(id = 4, name = "Printer 4", model = "A1"),
        BambuPrinter(id = 5, name = "Printer 5", model = "P1S"),
        BambuPrinter(id = 6, name = "Printer 6", model = "X1C"),
    )
  }

  override suspend fun getPrinterStatus(printerId: Int): BambuPrinterStatus? {
    log.info("Mock: getPrinterStatus printerId=$printerId")
    return statusByPrinterId[printerId]
  }

  override suspend fun getQueue(): List<BambuQueueItem> {
    log.info("Mock: getQueue")
    return listOf(
        BambuQueueItem(
            id = 1,
            printerId = 1,
            status = "printing",
            archiveName = "Bracket v3",
            printTimeSeconds = 4_500,
            position = 1,
            createdByUsername = "jdoe",
        ),
        BambuQueueItem(
            id = 2,
            printerId = 1,
            status = "pending",
            archiveName = "Bracket v3 (2nd copy)",
            printTimeSeconds = 4_500,
            position = 2,
            createdByUsername = "jdoe",
        ),
        BambuQueueItem(
            id = 3,
            printerId = 3,
            status = "printing",
            archiveName = "Enclosure lid",
            printTimeSeconds = 3_600,
            position = 1,
            createdByUsername = "asmith",
        ),
        // Printer 5 is in FINISH state (see statusByPrinterId above) - demonstrates
        // previousPrintedByUsername surfacing who to credit/blame for the part still on the bed.
        BambuQueueItem(
            id = 4,
            printerId = 5,
            status = "completed",
            archiveName = "Test cube",
            printTimeSeconds = 1_800,
            createdByUsername = "asmith",
            completedAt = "2026-09-17T12:00:00Z",
        ),
        // Printer 6 is in FAILED state (see statusByPrinterId above) - demonstrates
        // printedByUsername surfacing who to notify about the failure.
        BambuQueueItem(
            id = 5,
            printerId = 6,
            status = "failed",
            archiveName = "Enclosure lid v2",
            printTimeSeconds = 3_600,
            createdByUsername = "jdoe",
        ),
    )
  }

  override suspend fun getCameraSnapshot(printerId: Int): Pair<ByteArray, String>? {
    log.info("Mock: getCameraSnapshot printerId=$printerId")
    // No real JPEG bytes in mock mode - the UI falls back to a placeholder image on a null/empty
    // response, same as any camera being unreachable would show.
    return null
  }

  override suspend fun getPrinterModelIcon(model: String): Pair<ByteArray, String>? {
    log.info("Mock: getPrinterModelIcon model=$model")
    return null
  }
}
