package org.dallasmakerspace.smartwaiver

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import org.dallasmakerspace.core.api.DataVizReport
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.models.toJsonElement

private const val REPORT_DAYS = 90L
private const val THURSDAY = 4
private const val SATURDAY = 6
private const val MILLIS_IN_SECOND = 1000.0
private const val CACHE_DURATION_HOURS = 24L

@Singleton
class SignedWaivers
@Inject
constructor(
    private val smartwaiverClient: ISmartwaiverApiClient,
    private val localReads: LocalWaiverReads,
) : DataVizReport() {

  @Volatile private var cachedResponse: DataVizResponse? = null
  @Volatile private var cacheTimestamp: Long = 0

  override fun getName(): String = "signed-waivers"

  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val startTime = System.currentTimeMillis()
    val now = System.currentTimeMillis()
    val cacheAgeMillis = now - cacheTimestamp
    val cacheMaxAgeMillis = CACHE_DURATION_HOURS * 60 * 60 * 1000

    // The local mirror is a quick query, so it is never cached (and a cached API answer is not
    // served once reads move to the mirror).
    val local = localReads.useLocal(params["source"]?.firstOrNull())

    // Check for cache bypass parameter
    val bypassCache = params["cache"]?.firstOrNull() == "0" || local

    // Check if we have a valid cached response (and not bypassing cache)
    if (!bypassCache && cachedResponse != null && cacheAgeMillis < cacheMaxAgeMillis) {
      val cacheAgeMinutes = cacheAgeMillis / (60 * 1000)
      val updatedMetadata =
          (cachedResponse!!.metadata ?: emptyMap()) +
              mapOf(
                  "Cache status" to "Cached (age: ${cacheAgeMinutes} minutes)",
                  "Cache expires in" to
                      "${(cacheMaxAgeMillis - cacheAgeMillis) / (60 * 1000)} minutes")
      return cachedResponse!!.copy(metadata = updatedMetadata)
    }

    // Calculate date range: past 90 days. The local mirror counts Chicago days; the API path
    // keeps the server's date.
    val toDate = if (local) LocalDate.now(ZoneId.of("America/Chicago")) else LocalDate.now()
    val fromDate = toDate.minusDays(REPORT_DAYS)

    val waivers =
        if (local) localReads.signingData(fromDate, toDate)
        else smartwaiverClient.getWaivers(fromDate, toDate)

    // Find the start of the first week (Sunday)
    val firstWeekStart = fromDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

    // Find the end of the last week (Saturday)
    val lastWeekEnd = toDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))

    // Create data items for each week
    val dataItems = mutableListOf<DataItem>()
    var weekStart = firstWeekStart

    while (!weekStart.isAfter(lastWeekEnd)) {
      val weekEnd = weekStart.plusDays(6) // Sunday to Saturday

      // Filter waivers for this week
      val weekWaivers =
          waivers.filter {
            val waiverDate = it.date.toLocalDate()
            !waiverDate.isBefore(weekStart) && !waiverDate.isAfter(weekEnd)
          }

      // Count total waivers for the week
      val totalCount = weekWaivers.size

      // Count Thursday waivers (day 4)
      val thursdayCount = weekWaivers.count { it.dayOfWeek == THURSDAY }

      // Count Saturday waivers (day 6)
      val saturdayCount = weekWaivers.count { it.dayOfWeek == SATURDAY }

      // Format week label (e.g., "Aug 3 - 10" or "Dec 29 - Jan 4")
      val weekLabel =
          if (weekStart.month == weekEnd.month) {
            // Same month: "Aug 3 - 10"
            val monthFormatter = DateTimeFormatter.ofPattern("MMM")
            "${weekStart.format(monthFormatter)} ${weekStart.dayOfMonth} - ${weekEnd.dayOfMonth}"
          } else {
            // Different months: "Dec 29 - Jan 4"
            val monthDayFormatter = DateTimeFormatter.ofPattern("MMM d")
            "${weekStart.format(monthDayFormatter)} - ${weekEnd.format(monthDayFormatter)}"
          }

      dataItems.add(
          DataItem(
              values =
                  mapOf(
                      "Week" to JsonPrimitive(weekLabel),
                      "Total Waivers Signed" to totalCount.toJsonElement(),
                      "Thursday Waivers" to thursdayCount.toJsonElement(),
                      "Saturday Waivers" to saturdayCount.toJsonElement())))

      weekStart = weekStart.plusWeeks(1)
    }

    val endTime = System.currentTimeMillis()
    val timeTaken = (endTime - startTime) / MILLIS_IN_SECOND

    val dataFields =
        listOf(
            DataField(name = "Week", type = DataType.STRING, label = "Week"),
            DataField(
                name = "Total Waivers Signed",
                type = DataType.NUMBER,
                label = "Total Waivers Signed"),
            DataField(
                name = "Thursday Waivers", type = DataType.NUMBER, label = "Thursday Waivers"),
            DataField(
                name = "Saturday Waivers", type = DataType.NUMBER, label = "Saturday Waivers"))

    val cacheStatus =
        if (local) {
          "Not cached (read from the local mirror)"
        } else if (bypassCache) {
          "Freshly generated (cache bypassed)"
        } else {
          "Freshly generated (cached for 24 hours)"
        }

    val metadata =
        mapOf(
            "Generated at" to
                DateTimeFormatter.ISO_DATE_TIME.format(
                    Instant.now().atZone(java.time.ZoneId.of("America/Chicago"))),
            "Time taken" to "${"%.3f".format(timeTaken)} sec",
            "Date range" to "$fromDate to $toDate",
            "Total waivers" to "${waivers.size}",
            "Source" to (if (local) "Local mirror" else "SmartWaiver API"),
            "Cache status" to cacheStatus)

    val response = DataVizResponse(data = dataItems, dataFields = dataFields, metadata = metadata)

    // Cache the response for 24 hours (unless cache was bypassed)
    if (!bypassCache) {
      cachedResponse = response
      cacheTimestamp = System.currentTimeMillis()
    }

    return response
  }
}
