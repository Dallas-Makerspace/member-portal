package org.dallasmakerspace.bambuddy

interface IBambuddyApiClient {
  suspend fun getPrinters(): List<BambuPrinter>

  /** Null if the printer's status can't be fetched at all (treat as offline/unknown). */
  suspend fun getPrinterStatus(printerId: Int): BambuPrinterStatus?

  suspend fun getQueue(): List<BambuQueueItem>

  /** Raw JPEG snapshot bytes plus the content type Bambuddy reported, or null if unavailable. */
  suspend fun getCameraSnapshot(printerId: Int): Pair<ByteArray, String>?

  /** Bambuddy's own static product illustration for a printer model (e.g. "X1C", "A1 Mini") -
   * falls back to Bambuddy's generic default icon for a model it doesn't have art for. Raw bytes
   * plus content type, or null if even the fallback can't be fetched. */
  suspend fun getPrinterModelIcon(model: String): Pair<ByteArray, String>?
}
