package org.dallasmakerspace.dataviz.reports.interlock

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.dallasmakerspace.core.api.DataVizReport
import org.dallasmakerspace.core.logging.ElasticsearchClientManager
import org.dallasmakerspace.models.Cabinets
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.models.Interlocks

private const val SECONDS_PER_HOUR = 3600
private const val SECONDS_PER_MINUTE = 60

class TopCabinetsReport
@Inject
constructor(
    private val events: InterlockEventsService,
) : DataVizReport() {

  override fun getName(): String = "interlock-top-cabinets"

  @Suppress("TooGenericExceptionCaught", "LongMethod", "MagicNumber", "ReturnCount")
  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val committee =
        params["committee"]?.firstOrNull()
            ?: return DataVizResponse(
                data = emptyList(),
                dataFields = emptyList(),
                metadata = mapOf("error" to "Missing required parameter: committee"),
            )

    val days = params["days"]?.firstOrNull()?.toIntOrNull() ?: Interlocks.DEFAULT_REPORT_DAYS

    val c =
        Committees.findBySlug(committee)
            ?: return DataVizResponse(
                data = emptyList(),
                dataFields = emptyList(),
                metadata = mapOf("error" to "Unknown committee: $committee"),
            )

    val cabinets = Cabinets.byCommittee()[c] ?: emptyList()
    if (cabinets.isEmpty()) {
      return DataVizResponse(
          data = emptyList(),
          dataFields = emptyList(),
          metadata = mapOf("Committee" to c.name, "Cabinets" to "0"),
      )
    }

    val tagToCabinet = cabinets.associateBy { it.interlockTag }
    val raw = events.fetchEvents(cabinets.map { it.interlockTag }, days)
    val sessions = events.reconstructSessions(raw).filter { it.authorized }

    data class Stat(val tag: String, val members: Int, val sessions: Int, val totalSeconds: Long)

    val sessionsByCabinet = sessions.groupBy { it.host }

    val stats =
        cabinets.map { cabinet ->
          val list = sessionsByCabinet[cabinet.interlockTag].orEmpty()
          val totalSeconds =
              list.sumOf { s ->
                val start =
                    try {
                      Instant.parse(s.startTime).epochSecond
                    } catch (e: Exception) {
                      ElasticsearchClientManager.log.debug(
                          "TopCabinetsReport: failed to parse startTime: ${e.message}")
                      0L
                    }
                val end =
                    try {
                      s.shutdownTime?.let { Instant.parse(it).epochSecond } ?: start
                    } catch (e: Exception) {
                      ElasticsearchClientManager.log.debug(
                          "TopCabinetsReport: failed to parse shutdownTime: ${e.message}")
                      start
                    }
                (end - start).coerceAtLeast(0)
              }
          Stat(
              tag = cabinet.interlockTag,
              members = list.map { it.badge }.toSet().size,
              sessions = list.size,
              totalSeconds = totalSeconds,
          )
        }

    val sorted =
        stats.sortedWith(
            compareByDescending<Stat> { it.totalSeconds }.thenByDescending { it.members })

    val dataItems =
        sorted.map { stat ->
          val cabinet = tagToCabinet[stat.tag]
          val cabinetLink = buildJsonObject {
            put("text", JsonPrimitive(cabinet?.name ?: stat.tag))
            put("url", JsonPrimitive("/interlock-cabinets?cabinet=${stat.tag}"))
          }
          val hours = stat.totalSeconds / SECONDS_PER_HOUR
          val mins = (stat.totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
          val totalHours = "${hours}h ${mins}m"

          DataItem(
              values =
                  mapOf(
                      "Cabinet" to cabinetLink,
                      "Members" to JsonPrimitive(stat.members),
                      "Total Hours" to JsonPrimitive(totalHours),
                      "Sessions" to JsonPrimitive(stat.sessions),
                  ))
        }

    val dataFields =
        listOf(
            DataField(name = "Cabinet", type = DataType.LINK, label = "Cabinet"),
            DataField(name = "Members", type = DataType.NUMBER, label = "Members"),
            DataField(name = "Total Hours", type = DataType.STRING, label = "Total Hours"),
            DataField(name = "Sessions", type = DataType.NUMBER, label = "Sessions"),
        )

    val metadata = buildMap {
      put("Committee", c.name)
      put("Days", "$days")
      put("Cabinets", "${cabinets.size}")
      put("Total sessions", "${sessions.size}")
      put(
          "Generated at",
          DateTimeFormatter.ISO_DATE_TIME.format(
              Instant.now().atZone(ZoneId.of("America/Chicago"))))
      events
          .buildKibanaDiscoverUrl(
              events.kibanaQueryAuthorizedSwipes(cabinets.map { it.interlockTag }), days)
          ?.let { put("Kibana", it) }
    }

    return DataVizResponse(data = dataItems, dataFields = dataFields, metadata = metadata)
  }
}
