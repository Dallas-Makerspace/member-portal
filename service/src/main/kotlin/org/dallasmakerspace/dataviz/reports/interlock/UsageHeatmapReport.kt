package org.dallasmakerspace.dataviz.reports.interlock

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.serialization.json.JsonPrimitive
import org.dallasmakerspace.dataviz.DataVizReport
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.models.Interlocks

private const val HOURS_PER_DAY = 24
private const val DAYS_PER_WEEK = 7
private val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

class UsageHeatmapReport
@Inject
constructor(
    private val events: InterlockEventsService,
) : DataVizReport() {

  override fun getName(): String = "interlock-usage-heatmap"

  @Suppress("TooGenericExceptionCaught", "MagicNumber")
  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val toolParam = params["tool"]?.firstOrNull()
    val cabinetParam = params["cabinet"]?.firstOrNull()
    val tool =
        toolParam
            ?: cabinetParam
            ?: return DataVizResponse(
                data = emptyList(),
                dataFields = emptyList(),
                metadata = mapOf("error" to "Missing required parameter: tool"),
            )
    val scopeLabel = if (cabinetParam != null) "Cabinet" else "Tool"

    val days = params["days"]?.firstOrNull()?.toIntOrNull() ?: Interlocks.DEFAULT_REPORT_DAYS
    val aggregateBy = params["aggregateBy"]?.firstOrNull() ?: "hour"

    val raw = events.fetchEvents(listOf(tool), days)
    val sessions = events.reconstructSessions(raw).filter { it.authorized }

    val chicago = ZoneId.of("America/Chicago")
    val (dataItems, dataFields) =
        when (aggregateBy) {
          "dayOfWeek" -> buildDayOfWeek(sessions, chicago)
          else -> buildHourOfDay(sessions, chicago)
        }

    val metadata = buildMap {
      put(scopeLabel, tool)
      put("Days", "$days")
      put("Aggregate", aggregateBy)
      put("Total sessions", "${sessions.size}")
      put("Timezone", "America/Chicago")
      put(
          "Generated at",
          DateTimeFormatter.ISO_DATE_TIME.format(
              Instant.now().atZone(ZoneId.of("America/Chicago"))))
      events.buildKibanaDiscoverUrl(events.kibanaQueryAuthorizedSwipes(listOf(tool)), days)?.let {
        put("Kibana", it)
      }
    }

    return DataVizResponse(data = dataItems, dataFields = dataFields, metadata = metadata)
  }

  @Suppress("TooGenericExceptionCaught")
  private fun buildHourOfDay(
      sessions: List<SessionInfo>,
      chicago: ZoneId,
  ): Pair<List<DataItem>, List<DataField>> {
    val buckets = IntArray(HOURS_PER_DAY)
    for (s in sessions) {
      try {
        val hour = Instant.parse(s.startTime).atZone(chicago).hour
        buckets[hour] += 1
      } catch (_: Exception) {
        // skip unparseable timestamps
      }
    }
    val items =
        (0 until HOURS_PER_DAY).map { h ->
          DataItem(
              values =
                  mapOf(
                      "Hour" to JsonPrimitive(String.format(java.util.Locale.US, "%02d:00", h)),
                      "Sessions" to JsonPrimitive(buckets[h]),
                  ))
        }
    val fields =
        listOf(
            DataField(name = "Hour", type = DataType.STRING, label = "Hour"),
            DataField(name = "Sessions", type = DataType.NUMBER, label = "Sessions"),
        )
    return items to fields
  }

  @Suppress("TooGenericExceptionCaught")
  private fun buildDayOfWeek(
      sessions: List<SessionInfo>,
      chicago: ZoneId,
  ): Pair<List<DataItem>, List<DataField>> {
    val buckets = IntArray(DAYS_PER_WEEK)
    for (s in sessions) {
      try {
        val idx = Instant.parse(s.startTime).atZone(chicago).dayOfWeek.value - 1
        buckets[idx] += 1
      } catch (_: Exception) {
        // skip unparseable timestamps
      }
    }
    val items =
        (0 until DAYS_PER_WEEK).map { d ->
          DataItem(
              values =
                  mapOf(
                      "Day" to JsonPrimitive(DAY_LABELS[d]),
                      "Sessions" to JsonPrimitive(buckets[d]),
                  ))
        }
    val fields =
        listOf(
            DataField(name = "Day", type = DataType.STRING, label = "Day"),
            DataField(name = "Sessions", type = DataType.NUMBER, label = "Sessions"),
        )
    return items to fields
  }
}
