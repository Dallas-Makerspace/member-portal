package org.dallasmakerspace.dataviz.reports.interlock

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.dallasmakerspace.core.logging.ElasticsearchClientManager
import org.dallasmakerspace.dataviz.DataVizReport
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.models.Interlocks
import org.dallasmakerspace.models.Tools

private val DAY_LABEL_FORMAT = DateTimeFormatter.ofPattern("MM/dd")

class ToolActivityReport
@Inject
constructor(
    private val events: InterlockEventsService,
) : DataVizReport() {

  override fun getName(): String = "interlock-tool-activity"

  @Suppress("TooGenericExceptionCaught", "LongMethod", "ReturnCount")
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

    val tools = Tools.byCommittee()[c] ?: emptyList()
    if (tools.isEmpty()) {
      return DataVizResponse(
          data = emptyList(),
          dataFields = emptyList(),
          metadata = mapOf("Committee" to c.name, "Tools" to "0"),
      )
    }

    val chicago = ZoneId.of("America/Chicago")
    val today = LocalDate.now(chicago)
    val dayList = (0 until days).map { today.minusDays((days - 1 - it).toLong()) }

    val raw = events.fetchEvents(tools.map { it.interlockTag }, days)
    val sessions = events.reconstructSessions(raw).filter { it.authorized }

    // host -> day -> distinct badges
    val grouped = mutableMapOf<String, MutableMap<LocalDate, MutableSet<String>>>()
    for (s in sessions) {
      try {
        val date = Instant.parse(s.startTime).atZone(chicago).toLocalDate()
        grouped.getOrPut(s.host) { mutableMapOf() }.getOrPut(date) { mutableSetOf() }.add(s.badge)
      } catch (e: Exception) {
        ElasticsearchClientManager.log.debug(
            "ToolActivityReport: failed to parse startTime: ${e.message}")
      }
    }

    val dataItems =
        dayList.map { date ->
          val values = mutableMapOf<String, JsonElement>()
          values["Day"] = JsonPrimitive(date.format(DAY_LABEL_FORMAT))
          for (tool in tools) {
            val count = grouped[tool.interlockTag]?.get(date)?.size ?: 0
            values[tool.name] = JsonPrimitive(count)
          }
          DataItem(values = values)
        }

    val dataFields = buildList {
      add(DataField(name = "Day", type = DataType.STRING, label = "Day"))
      for (tool in tools) {
        add(DataField(name = tool.name, type = DataType.NUMBER, label = tool.name))
      }
    }

    val metadata = buildMap {
      put("Committee", c.name)
      put("Days", "$days")
      put("Tools", "${tools.size}")
      put("Total sessions", "${sessions.size}")
      put("Timezone", "America/Chicago")
      put("Generated at", DateTimeFormatter.ISO_DATE_TIME.format(Instant.now().atZone(chicago)))
      events
          .buildKibanaDiscoverUrl(
              events.kibanaQueryAuthorizedSwipes(tools.map { it.interlockTag }), days)
          ?.let { put("Kibana", it) }
    }

    return DataVizResponse(data = dataItems, dataFields = dataFields, metadata = metadata)
  }
}
