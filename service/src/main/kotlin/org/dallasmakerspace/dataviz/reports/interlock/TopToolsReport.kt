package org.dallasmakerspace.dataviz.reports.interlock

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.dallasmakerspace.core.logging.ElasticsearchClientManager
import org.dallasmakerspace.dataviz.DataVizReport
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.models.Interlocks
import org.dallasmakerspace.models.Tools

private const val SECONDS_PER_HOUR = 3600
private const val SECONDS_PER_MINUTE = 60

class TopToolsReport
@Inject
constructor(
    private val events: InterlockEventsService,
) : DataVizReport() {

  override fun getName(): String = "interlock-top-tools"

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

    val tools = Tools.byCommittee()[c] ?: emptyList()
    if (tools.isEmpty()) {
      return DataVizResponse(
          data = emptyList(),
          dataFields = emptyList(),
          metadata = mapOf("Committee" to c.name, "Tools" to "0"),
      )
    }

    val tagToTool = tools.associateBy { it.interlockTag }
    val raw = events.fetchEvents(tools.map { it.interlockTag }, days)
    val sessions = events.reconstructSessions(raw).filter { it.authorized }

    data class Stat(val tag: String, val members: Int, val sessions: Int, val totalSeconds: Long)

    val sessionsByTool = sessions.groupBy { it.host }

    val stats =
        tools.map { tool ->
          val list = sessionsByTool[tool.interlockTag].orEmpty()
          val totalSeconds =
              list.sumOf { s ->
                val start =
                    try {
                      Instant.parse(s.startTime).epochSecond
                    } catch (e: Exception) {
                      ElasticsearchClientManager.log.debug(
                          "TopToolsReport: failed to parse startTime: ${e.message}")
                      0L
                    }
                val end =
                    try {
                      s.shutdownTime?.let { Instant.parse(it).epochSecond } ?: start
                    } catch (e: Exception) {
                      ElasticsearchClientManager.log.debug(
                          "TopToolsReport: failed to parse shutdownTime: ${e.message}")
                      start
                    }
                (end - start).coerceAtLeast(0)
              }
          Stat(
              tag = tool.interlockTag,
              members = list.map { it.badge }.toSet().size,
              sessions = list.size,
              totalSeconds = totalSeconds,
          )
        }

    val sorted = stats.sortedWith(compareByDescending<Stat> { it.totalSeconds }.thenByDescending { it.members })

    val dataItems =
        sorted.map { stat ->
          val tool = tagToTool[stat.tag]
          val toolLink = buildJsonObject {
            put("text", JsonPrimitive(tool?.name ?: stat.tag))
            put("url", JsonPrimitive("/interlock-tools?tool=${stat.tag}"))
          }
          val hours = stat.totalSeconds / SECONDS_PER_HOUR
          val mins = (stat.totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
          val totalHours = "${hours}h ${mins}m"

          DataItem(
              values =
                  mapOf(
                      "Tool" to toolLink,
                      "Members" to JsonPrimitive(stat.members),
                      "Total Hours" to JsonPrimitive(totalHours),
                      "Sessions" to JsonPrimitive(stat.sessions),
                  ))
        }

    val dataFields =
        listOf(
            DataField(name = "Tool", type = DataType.LINK, label = "Tool"),
            DataField(name = "Members", type = DataType.NUMBER, label = "Members"),
            DataField(name = "Total Hours", type = DataType.STRING, label = "Total Hours"),
            DataField(name = "Sessions", type = DataType.NUMBER, label = "Sessions"),
        )

    val metadata =
        mapOf(
            "Committee" to c.name,
            "Days" to "$days",
            "Tools" to "${tools.size}",
            "Total sessions" to "${sessions.size}",
            "Generated at" to
                DateTimeFormatter.ISO_DATE_TIME.format(
                    Instant.now().atZone(ZoneId.of("America/Chicago"))),
        )

    return DataVizResponse(data = dataItems, dataFields = dataFields, metadata = metadata)
  }
}
