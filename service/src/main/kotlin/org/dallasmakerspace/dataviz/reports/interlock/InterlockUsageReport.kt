package org.dallasmakerspace.dataviz.reports.interlock

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
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

class InterlockUsageReport
@Inject
constructor(
    private val events: InterlockEventsService,
    private val memberService: MemberService,
) : DataVizReport() {

  override fun getName(): String = "interlock-usage"

  @Suppress("TooGenericExceptionCaught", "LongMethod")
  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val totalStartTime = System.currentTimeMillis()

    val interlockTag =
        params["tool"]?.firstOrNull()
            ?: return DataVizResponse(
                data = emptyList(),
                dataFields = emptyList(),
                metadata = mapOf("error" to "Missing required parameter: tool"),
            )

    val days = params["days"]?.firstOrNull()?.toIntOrNull() ?: 14

    // Step 1 — ES query
    val esStartTime = System.currentTimeMillis()
    val rawEvents = events.fetchEvents(listOf(interlockTag), days)
    val esTimeTaken = System.currentTimeMillis() - esStartTime

    // Step 2 — Session reconstruction
    val sessions = events.reconstructSessions(rawEvents)

    // Step 4 — Badge → Member lookup via AD
    val badgeNumbers = sessions.map { it.badge }.toSet()
    ElasticsearchClientManager.log.info("InterlockUsageReport: badge lookup for: $badgeNumbers")
    val memberLookupStartTime = System.currentTimeMillis()
    val badgeToUsername = events.fetchBadgeToUsername(badgeNumbers)
    ElasticsearchClientManager.log.info("InterlockUsageReport: resolved badges: $badgeToUsername")
    val usernames = badgeToUsername.values.toSet()
    val members =
        if (usernames.isNotEmpty()) {
          try {
            memberService.getMembersByUsernameList(usernames.toList())
          } catch (e: Exception) {
            ElasticsearchClientManager.log.warn(
                "InterlockUsageReport: member lookup failed: ${e.message}")
            emptyMap()
          }
        } else {
          emptyMap()
        }
    val memberLookupTimeTaken = System.currentTimeMillis() - memberLookupStartTime

    // Step 5 — Build DataVizResponse (most recent first)
    val dataItems =
        sessions.reversed().map { session ->
          val username = badgeToUsername[session.badge]
          val member = username?.let { members[it] }

          val memberJson: JsonElement =
              if (username != null) {
                buildJsonObject {
                  put("username", JsonPrimitive(username))
                  put("displayName", JsonPrimitive(member?.displayName ?: username))
                  put("avatarUrl", JsonPrimitive(events.getAvatarUrl(member?.discourseAvatarUrl)))
                }
              } else {
                JsonPrimitive("Badge: ${events.maskBadge(session.badge)}")
              }

          val authJson = buildJsonObject {
            put("variant", JsonPrimitive(if (session.authorized) "success" else "danger"))
            put("text", JsonPrimitive(if (session.authorized) "AUTHORIZED" else "DENIED"))
          }

          val duration = events.formatDuration(session.startTime, session.shutdownTime)

          val shutdownJson = buildJsonObject {
            when {
              !session.authorized -> {
                put("variant", JsonPrimitive("light"))
                put("text", JsonPrimitive("—"))
              }
              session.shutdownType == "IDLE_TIMEOUT" -> {
                put("variant", JsonPrimitive("secondary"))
                put("text", JsonPrimitive("IDLE TIMEOUT"))
              }
              session.shutdownType == "STARTUP_CURRENT" -> {
                put("variant", JsonPrimitive("danger"))
                put("text", JsonPrimitive("STARTUP CURRENT"))
                put("tooltip", JsonPrimitive("User error: start button pressed at the same time as badging in"))
              }
              session.shutdownType == "MANUAL_BADGE_OUT" -> {
                put("variant", JsonPrimitive("primary"))
                put("text", JsonPrimitive("BADGE OUT"))
              }
              else -> {
                put("variant", JsonPrimitive("light"))
                put("text", JsonPrimitive("RUNNING"))
              }
            }
          }

          DataItem(
              values =
                  mapOf(
                      "Time" to JsonPrimitive(session.startTime),
                      "Member" to memberJson,
                      "Auth" to authJson,
                      "Duration" to JsonPrimitive(duration),
                      "Shutdown" to shutdownJson,
                  ))
        }

    val dataFields =
        listOf(
            DataField(name = "Time", type = DataType.RELATIVE_DATE, label = "Time"),
            DataField(name = "Member", type = DataType.MEMBER, label = "Member"),
            DataField(name = "Auth", type = DataType.BADGE, label = "Auth"),
            DataField(name = "Duration", type = DataType.STRING, label = "Duration"),
            DataField(name = "Shutdown", type = DataType.BADGE, label = "Shutdown"),
        )

    val totalTimeTaken = System.currentTimeMillis() - totalStartTime
    val timeTakenInSec = String.format(Locale.US, "%.3f", totalTimeTaken / 1000f)

    val metadata =
        mapOf(
            "Generated at" to
                DateTimeFormatter.ISO_DATE_TIME.format(
                    Instant.now().atZone(java.time.ZoneId.of("America/Chicago"))),
            "Time taken" to "$timeTakenInSec sec",
            "ES query time" to "${esTimeTaken}ms",
            "Member lookup time" to "${memberLookupTimeTaken}ms",
            "Total sessions" to "${sessions.size}",
            "Tool" to interlockTag,
        )

    return DataVizResponse(data = dataItems, dataFields = dataFields, metadata = metadata)
  }
}
