package org.dallasmakerspace.bambuddy

import io.ktor.utils.io.ByteReadChannel

interface IBambuddyApiClient {
  suspend fun getPrinters(): List<BambuPrinter>

  /** Null if the printer's status can't be fetched at all (treat as offline/unknown). */
  suspend fun getPrinterStatus(printerId: Int): BambuPrinterStatus?

  suspend fun getQueue(): List<BambuQueueItem>

  /** Only the printing and pending queue items - cheap, unlike [getQueue] which includes all history. */
  suspend fun getActiveQueue(): List<BambuQueueItem>

  /** The printer's most recently finished queue item with [status], or null if it has none. */
  suspend fun getLatestFinished(printerId: Int, status: String): BambuQueueItem?

  /** Raw JPEG snapshot bytes plus the content type Bambuddy reported, or null if unavailable. */
  suspend fun getCameraSnapshot(printerId: Int): Pair<ByteArray, String>?

  /** Opens the printer's live MJPEG stream and hands its content type and body to [onOpen], which
   * runs until the stream ends or the caller stops reading. False if the stream isn't available. */
  suspend fun streamCamera(
      printerId: Int,
      onOpen: suspend (contentType: String, body: ByteReadChannel) -> Unit,
  ): Boolean

  /** Bambuddy's own static product illustration for a printer model (e.g. "X1C", "A1 Mini") -
   * falls back to Bambuddy's generic default icon for a model it doesn't have art for. Raw bytes
   * plus content type, or null if even the fallback can't be fetched. */
  suspend fun getPrinterModelIcon(model: String): Pair<ByteArray, String>?
}
