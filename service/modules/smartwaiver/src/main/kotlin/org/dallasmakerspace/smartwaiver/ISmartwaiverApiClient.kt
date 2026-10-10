package org.dallasmakerspace.smartwaiver

import java.time.LocalDate

interface ISmartwaiverApiClient {
  /**
   * Get a list of waivers signed within a date range.
   *
   * @param fromDate The start date (inclusive)
   * @param toDate The end date (inclusive)
   * @return List of waiver signing data
   */
  suspend fun getWaivers(fromDate: LocalDate, toDate: LocalDate): List<WaiverSigningData>

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
}
