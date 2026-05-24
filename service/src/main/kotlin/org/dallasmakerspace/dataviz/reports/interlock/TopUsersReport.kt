package org.dallasmakerspace.dataviz.reports.interlock

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.dallasmakerspace.core.logging.ElasticsearchClientManager
import org.dallasmakerspace.dataviz.DataVizReport
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.models.Interlocks

class TopUsersReport
@Inject
constructor(
    private val events: InterlockEventsService,
    private val memberService: MemberService,
) : DataVizReport() {

  override fun getName(): String = "interlock-top-users"

  @Suppress("TooGenericExceptionCaught", "LongMethod", "MagicNumber")
  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val tool =
        params["tool"]?.firstOrNull()
            ?: return DataVizResponse(
                data = emptyList(),
                dataFields = emptyList(),
                metadata = mapOf("error" to "Missing required parameter: tool"),
            )

    val days = params["days"]?.firstOrNull()?.toIntOrNull() ?: Interlocks.DEFAULT_REPORT_DAYS

    val raw = events.fetchEvents(listOf(tool), days)
    val sessions = events.reconstructSessions(raw).filter { it.authorized }

    data class Stat(val badge: String, val count: Int, val totalSeconds: Long)

    val stats =
        sessions
            .groupBy { it.badge }
            .map { (badge, list) ->
              val seconds =
                  list.sumOf { s ->
                    val start =
                        try {
                          Instant.parse(s.startTime).epochSecond
                        } catch (e: Exception) {
                          ElasticsearchClientManager.log.debug(
                              "TopUsersReport: failed to parse startTime: ${e.message}")
                          0L
                        }
                    val end =
                        try {
                          s.shutdownTime?.let { Instant.parse(it).epochSecond } ?: start
                        } catch (e: Exception) {
                          ElasticsearchClientManager.log.debug(
                              "TopUsersReport: failed to parse shutdownTime: ${e.message}")
                          start
                        }
                    (end - start).coerceAtLeast(0)
                  }
              Stat(badge, list.size, seconds)
            }
            .sortedByDescending { it.count }
            .take(25)

    val badgeNumbers = stats.map { it.badge }.toSet()
    val badgeToUsername = events.fetchBadgeToUsername(badgeNumbers)
    val usernames = badgeToUsername.values.toSet()
    val members =
        if (usernames.isNotEmpty()) {
          try {
            memberService.getMembersByUsernameList(usernames.toList())
          } catch (e: Exception) {
            ElasticsearchClientManager.log.warn(
                "TopUsersReport: member lookup failed: ${e.message}")
            emptyMap()
          }
        } else {
          emptyMap()
        }

    val dataItems =
        stats.mapIndexed { idx, stat ->
          val username = badgeToUsername[stat.badge]
          val member = username?.let { members[it] }

          val memberJson: JsonElement =
              if (username != null) {
                buildJsonObject {
                  put("username", JsonPrimitive(username))
                  put("displayName", JsonPrimitive(member?.displayName ?: username))
                  put("avatarUrl", JsonPrimitive(events.getAvatarUrl(member?.discourseAvatarUrl)))
                }
              } else {
                JsonPrimitive("Badge: ${events.maskBadge(stat.badge)}")
              }

          val hours = stat.totalSeconds / 3600
          val mins = (stat.totalSeconds % 3600) / 60
          val totalHours = "${hours}h ${mins}m"

          DataItem(
              values =
                  mapOf(
                      "Rank" to JsonPrimitive(idx + 1),
                      "Member" to memberJson,
                      "Sessions" to JsonPrimitive(stat.count),
                      "Total Hours" to JsonPrimitive(totalHours),
                  ))
        }

    val dataFields =
        listOf(
            DataField(name = "Rank", type = DataType.NUMBER, label = "Rank"),
            DataField(name = "Member", type = DataType.MEMBER, label = "Member"),
            DataField(name = "Sessions", type = DataType.NUMBER, label = "Sessions"),
            DataField(name = "Total Hours", type = DataType.STRING, label = "Total Hours"),
        )

    val metadata = buildMap {
      put("Tool", tool)
      put("Days", "$days")
      put("Unique users", "${stats.size}")
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
}
