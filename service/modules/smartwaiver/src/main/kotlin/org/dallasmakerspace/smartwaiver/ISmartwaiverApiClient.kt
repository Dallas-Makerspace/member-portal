package org.dallasmakerspace.smartwaiver

import java.time.LocalDate
import java.time.LocalDateTime

/** Name of the dedicated, `@Named` object store the SmartWaiver mirror writes kiosk photos to. */
const val SMARTWAIVER_STORE = "smartwaiver"

interface ISmartwaiverApiClient {
  /**
   * Get a list of waivers signed within a date range.
   *
   * @param fromDate The start date (inclusive)
   * @param toDate The end date (inclusive)
   * @return List of waiver signing data
   */
  suspend fun getWaivers(fromDate: LocalDate, toDate: LocalDate): List<WaiverSigningData>

  /**
   * Every waiver summary signed from [fromDate] through [toDate] (both inclusive). A window that
   * holds more results than one search can page through is split in half, so nothing is dropped.
   */
  suspend fun getWaiverDetails(fromDate: LocalDate, toDate: LocalDate): List<SmartwaiverSummary>

  /**
   * Waivers signed by any participant with this first/last name, most recent first. Live API, no
   * date range.
   *
   * Deliberately name-based rather than email-based: Smartwaiver's `email` search parameter was
   * empirically found to miss real, existing waivers (confirmed via direct waiverId lookup) that
   * `firstName`/`lastName` search finds correctly — an apparent gap in Smartwaiver's own email
   * search index, not something specific to this app's data. Callers should filter the returned
   * summaries by `email` themselves to guard against same-name false positives.
   */
  suspend fun getWaiversByName(firstName: String, lastName: String): List<SmartwaiverSummary>

  suspend fun getWaiver(waiverId: String): SmartwaiverFullWaiver

  /** Kiosk-captured photos for a waiver signing session. Empty list if none exist. */
  suspend fun getWaiverPhotos(waiverId: String): List<SmartwaiverPhotoEntry>

  /**
   * Up to 100 check-ins from [from] (inclusive) to [to], oldest first. When
   * [SmartwaiverCheckinPage.moreCheckins] is true, ask again starting at the last check-in's date.
   */
  suspend fun getCheckins(from: LocalDateTime, to: LocalDateTime): SmartwaiverCheckinPage
}
