package org.dallasmakerspace.db.master

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.Time
import org.dallasmakerspace.voterregistration.UserGracePeriod
import org.dallasmakerspace.voterregistration.VoterRegistrationManager

@Singleton
class WhmcsDataService
@Inject
constructor(
    private val time: Time,
    private val whmcsDataRepository: WhmcsDataRepository,
    private val voterRegistrationManager: VoterRegistrationManager
) : IWhmcsDataService {
  /** Get account info for a list of WHMCS user IDs. */
  override suspend fun getAccountInfoMap(whmcsIdList: List<Int>): Map<Int, AccountStatus> {
    val startDate: LocalDate =
        time.getToday().minusDays(VoterRegistrationManager.MEMBER_IN_GOOD_STANDING_DAYS)
    return getAccountInfoMap(whmcsIdList, startDate)
  }

  /**
   * Get account info for a list of WHMCS user IDs with a custom start date.
   *
   * @param whmcsIdList List of WHMCS user IDs
   * @param startDate Start date for the check (how far back to scan)
   */
  override suspend fun getAccountInfoMap(
      whmcsIdList: List<Int>,
      startDate: LocalDate
  ): Map<Int, AccountStatus> {
    val accountProductsInfoMap =
        whmcsDataRepository.getAccountProductInfoMap(whmcsIdList, startDate)
    val result = mutableMapOf<Int, AccountStatus>()
    whmcsIdList.forEach { whmcsId ->
      val products = accountProductsInfoMap[whmcsId] ?: emptyList()
      result[whmcsId] =
          checkActiveInRange(products, startDate, voterRegistrationManager.getUserGracePeriods())
    }
    return result
  }

  /**
   * Check if the user has at least one active product from the startDate to now. Product is
   * considered active between regdate and termination_date, if it is currently active then
   * domainStatus is DomainStatus.Active and termination_date is null. We cater the edge case where
   * the user switched from one active product to another on the same day.
   *
   * @param whmcsId WHMCS user ID
   * @param products List of products for the user
   * @param startDate Start date for the check
   * @return True if the user has at least active product between start date and now, false
   *   otherwise
   */
  private fun checkActiveInRange(
      products: List<WhmcsProductInfo>,
      startDate: LocalDate,
      userGracePeriods: List<UserGracePeriod>
  ): AccountStatus {
    val today = time.getToday()
    val userProducts = products.sortedBy { it.regDate }
    var lastInactiveDate: LocalDate? = null
    var hasAnyInactiveDate = false
    var currentStatusStartDate: LocalDate? = null
    var previousDayWasActive: Boolean? = null

    // Track timeline entries (active periods and gaps)
    val timeline = mutableListOf<TimelineEntry>()
    var currentPeriodStart: LocalDate? = null
    var currentPeriodType: TimelineEntryType? = null

    // For each date between start date and now, check if the user has an active product
    var currentDate = startDate
    while (!currentDate.isAfter(today)) {
      val dateIsCovered =
          userProducts.any { product ->
            // Product covers a date if:
            // 1. Registration date is on or before the date we're checking
            // 2. And either:
            //    - Product is currently active (Active + null termination)
            //    - Or termination date is after the date we're checking
            val isCurrentlyActive =
                product.domainStatus == WhmcsDomainStatus.Active && product.terminationDate == null
            val isTerminatedAfterDate =
                product.terminationDate?.isAfter(currentDate) == true ||
                    product.terminationDate?.isEqual(currentDate) == true

            !product.regDate.isAfter(currentDate) && (isCurrentlyActive || isTerminatedAfterDate)
          }

      val dateInGracePeriod =
          userGracePeriods.any { gracePeriod ->
            !currentDate.isBefore(gracePeriod.startDate) &&
                !currentDate.isAfter(gracePeriod.endDate)
          }

      val isActiveToday = dateIsCovered || dateInGracePeriod

      if (!isActiveToday) {
        lastInactiveDate = currentDate
        hasAnyInactiveDate = true
      }

      // Track status transitions and build timeline
      if (previousDayWasActive != null && previousDayWasActive != isActiveToday) {
        // Status changed - close current period and start new one
        currentStatusStartDate = currentDate

        if (currentPeriodStart != null && currentPeriodType != null) {
          // Close the current period
          val periodEnd = currentDate.minusDays(1)
          val durationDays =
              java.time.temporal.ChronoUnit.DAYS.between(currentPeriodStart, periodEnd).toInt() + 1
          timeline.add(
              TimelineEntry(
                  type = currentPeriodType,
                  startDate = currentPeriodStart,
                  endDate = periodEnd,
                  durationDays = durationDays))
        }

        // Start new period
        currentPeriodStart = currentDate
        currentPeriodType = if (isActiveToday) TimelineEntryType.ACTIVE else TimelineEntryType.GAP
      } else if (previousDayWasActive == null) {
        // First day - initialize tracking
        currentPeriodStart = currentDate
        currentPeriodType = if (isActiveToday) TimelineEntryType.ACTIVE else TimelineEntryType.GAP
      }

      previousDayWasActive = isActiveToday
      currentDate = currentDate.plusDays(1)
    }

    // Close any open period at the end of the scan
    if (currentPeriodStart != null && currentPeriodType != null) {
      val durationDays =
          java.time.temporal.ChronoUnit.DAYS.between(currentPeriodStart, today).toInt() + 1
      timeline.add(
          TimelineEntry(
              type = currentPeriodType,
              startDate = currentPeriodStart,
              endDate = null, // null means "ongoing" (extends to today)
              durationDays = durationDays))
    }

    // Calculate days in current status (inclusive, so add 1)
    val daysInCurrentStatus =
        currentStatusStartDate?.let { startDate ->
          java.time.temporal.ChronoUnit.DAYS.between(startDate, today).toInt() + 1
        }

    return AccountStatus(
        wasActiveInRange = !hasAnyInactiveDate,
        lastInactiveDate = lastInactiveDate,
        regDate = userProducts.firstOrNull()?.regDate,
        daysInCurrentStatus = daysInCurrentStatus,
        timeline = timeline)
  }

  override suspend fun getAccountRegdate(whmcsId: Int): LocalDate? {
    return whmcsDataRepository.getAccountRegdate(whmcsId)
  }
}

// Data structure to hold whether the account was active in the given range and the last inactive
// date
data class AccountStatus(
    val wasActiveInRange: Boolean,
    val lastInactiveDate: LocalDate?,
    val regDate: LocalDate?,
    /**
     * Number of days in the current WHMCS status (active or inactive). Returns null if no status
     * transition occurred within the 90-day scan window.
     */
    val daysInCurrentStatus: Int?,
    /** Unified chronological timeline of active periods and gaps */
    val timeline: List<TimelineEntry>
)

/** Represents an entry in the product coverage timeline (either active period or gap) */
data class TimelineEntry(
    val type: TimelineEntryType,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val durationDays: Int
)

enum class TimelineEntryType {
  ACTIVE,
  GAP
}
