package org.dallasmakerspace.dataviz.reports.interlock

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.dallasmakerspace.dataviz.DataVizReport
import org.dallasmakerspace.models.Cabinets
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.models.Interlocks

class CabinetInactivityReport
@Inject
constructor(
    private val events: InterlockEventsService,
) : DataVizReport() {

  override fun getName(): String = "interlock-cabinet-inactivity"

  @Suppress("MagicNumber", "LongMethod", "ReturnCount")
  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val committee =
        params["committee"]?.firstOrNull()
            ?: return DataVizResponse(
                data = emptyList(),
                dataFields = emptyList(),
                metadata = mapOf("error" to "Missing required parameter: committee"),
            )

    val silentDays = params["silentDays"]?.firstOrNull()?.toIntOrNull() ?: Interlocks.DEFAULT_INACTIVITY_DAYS

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
          metadata =
              mapOf(
                  "Committee" to c.name,
                  "Cabinets" to "0",
              ),
      )
    }

    val lastSeen = events.fetchLastEventPerTool(cabinets.map { it.interlockTag })
    val now = Instant.now()

    data class Row(
        val cabinet: org.dallasmakerspace.models.Cabinet,
        val lastInstant: Instant?,
        val daysSilent: Long?,
    )

    val rows =
        cabinets.map { cabinet ->
          val inst = lastSeen[cabinet.interlockTag]
          val days =
              inst?.let { ChronoUnit.DAYS.between(it, now).coerceAtLeast(0) }
          Row(cabinet, inst, days)
        }

    val sorted =
        rows.sortedWith(
            compareByDescending<Row> { it.daysSilent == null }
                .thenByDescending { it.daysSilent ?: -1L })

    val dataItems =
        sorted.map { row ->
          val cabinetLink = buildJsonObject {
            put("text", JsonPrimitive(row.cabinet.name))
            put("url", JsonPrimitive("/interlock-cabinets?cabinet=${row.cabinet.interlockTag}"))
          }

          val lastEventJson: JsonElement =
              row.lastInstant?.let { JsonPrimitive(it.toString()) } ?: JsonPrimitive("")

          val daysSilentJson: JsonElement = JsonPrimitive(row.daysSilent ?: -1L)

          val statusJson = buildJsonObject {
            when {
              row.daysSilent == null -> {
                put("variant", JsonPrimitive("secondary"))
                put("text", JsonPrimitive("NEVER"))
              }
              row.daysSilent > silentDays -> {
                put("variant", JsonPrimitive("warning"))
                put("text", JsonPrimitive("STALE (${row.daysSilent}d)"))
              }
              else -> {
                put("variant", JsonPrimitive("success"))
                put("text", JsonPrimitive("OK"))
              }
            }
          }

          DataItem(
              values =
                  mapOf(
                      "Cabinet" to cabinetLink,
                      "Last used" to lastEventJson,
                      "Days Silent" to daysSilentJson,
                      "Status" to statusJson,
                  ))
        }

    val dataFields =
        listOf(
            DataField(name = "Cabinet", type = DataType.LINK, label = "Cabinet"),
            DataField(name = "Last used", type = DataType.RELATIVE_DATE, label = "Last used"),
            DataField(name = "Days Silent", type = DataType.NUMBER, label = "Days Silent"),
            DataField(name = "Status", type = DataType.BADGE, label = "Status"),
        )

    val metadata = buildMap {
      put("Committee", c.name)
      put("Cabinets", "${cabinets.size}")
      put("Silent threshold (days)", "$silentDays")
      put(
          "Generated at",
          DateTimeFormatter.ISO_DATE_TIME.format(
              Instant.now().atZone(ZoneId.of("America/Chicago"))))
      events
          .buildKibanaDiscoverUrl(
              events.kibanaQueryForTags(cabinets.map { it.interlockTag }),
              Interlocks.DEFAULT_REPORT_DAYS,
          )
          ?.let { put("Kibana", it) }
    }

    return DataVizResponse(data = dataItems, dataFields = dataFields, metadata = metadata)
  }
}
