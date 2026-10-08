package org.dallasmakerspace.dataviz.reports.moodle

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.FeatureFlagService
import org.dallasmakerspace.models.Committee
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.moodle.EnrolmentRecord
import org.dallasmakerspace.moodle.LearnCourse
import org.dallasmakerspace.moodle.MoodleCatalogService
import org.dallasmakerspace.moodle.MoodleStatsService

private val CHICAGO = ZoneId.of("America/Chicago")

/** Inputs shared by the Moodle reports: flag check, optional committee scope and the data. */
class MoodleReportSupport
@Inject
constructor(
    private val featureFlagService: FeatureFlagService,
    private val catalogService: MoodleCatalogService,
    private val statsService: MoodleStatsService,
) {
  data class Inputs(
      val committee: Committee?,
      val records: List<EnrolmentRecord>,
      val courses: List<LearnCourse>,
      val today: LocalDate,
  )

  /** Returns the report inputs, or an error response to send back instead. */
  suspend fun load(params: Map<String, List<String>>): Pair<Inputs?, DataVizResponse?> {
    val actor = params["actorUsername"]?.firstOrNull()
    if (actor == null ||
        !featureFlagService.isEnabledForUsername(ConfigRegistry.MOODLE_ENABLED, actor)) {
      return null to error("Online course reports are not enabled")
    }
    val slug = params["committee"]?.firstOrNull()?.takeIf { it.isNotBlank() }
    val committee = slug?.let { Committees.findBySlug(it) }
    if (slug != null && committee == null) return null to error("Unknown committee: $slug")
    if (!statsService.isConfigured) return null to error("Moodle stats report is not configured")
    val records = statsService.getRecords() ?: return null to error("Moodle is unavailable")
    return Inputs(committee, records, catalogService.getAllCourses(), LocalDate.now(CHICAGO)) to
        null
  }

  fun metadata(inputs: Inputs, extra: Map<String, String> = emptyMap()): Map<String, String> =
      buildMap {
        inputs.committee?.let { put("Committee", it.name) }
        putAll(extra)
        put("Source", "DMS Learn (Moodle)")
        put("Generated at", DateTimeFormatter.ISO_DATE_TIME.format(ZonedDateTime.now(CHICAGO)))
      }

  private fun error(message: String) =
      DataVizResponse(data = emptyList(), dataFields = emptyList(), metadata = mapOf("error" to message))
}
