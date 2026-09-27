package org.dallasmakerspace.dataviz.reports.accesscontrol

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*
import javax.inject.Inject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.dallasmakerspace.dataviz.DataVizReport
import org.dallasmakerspace.db.master.GenericRepository
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.models.normalizeAvatarUrl

class BadgeSwipesReport
@Inject
constructor(
    private val genericRepository: GenericRepository,
    private val memberService: MemberService,
) : DataVizReport() {

  override fun getName(): String = "badge-swipes"

  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val totalStartTime = System.currentTimeMillis()

    // Execute SQL query to get all badge swipes from past 24 hours
    val queryStartTime = System.currentTimeMillis()
    val dbData = genericRepository.getReportData(buildQuery())
    val queryTimeTaken = System.currentTimeMillis() - queryStartTime

    // Extract data rows
    val rows = dbData?.data ?: emptyList()

    // Collect all usernames for batch fetch
    val usernames = rows.mapNotNull { it["username"] as? String }.filter { it.isNotBlank() }.toSet()

    // Batch fetch all members
    val membersStartTime = System.currentTimeMillis()
    val members = memberService.getMembersByUsernameList(usernames.toList())
    val membersTimeTaken = System.currentTimeMillis() - membersStartTime

    // Transform data to include member cards and door names
    val dataItems =
        rows.map { row ->
          val username = row["username"] as? String
          val cardNumber = row["cardNumber"] as? String ?: ""
          val member = username?.let { members[it] }

          // Build member JSON object for dms-member-card component
          val memberJson: JsonElement =
              if (username != null && username.isNotBlank()) {
                buildJsonObject {
                  put("username", JsonPrimitive(username))
                  put("displayName", JsonPrimitive(member?.displayName ?: username))
                  put(
                      "avatarUrl",
                      JsonPrimitive(normalizeAvatarUrl(member?.discourseAvatarUrl, size = 144)))
                }
              } else {
                // For badge swipes with no associated member, show card number
                JsonPrimitive("Unknown: $cardNumber")
              }

          // Get door name from query (from slots table)
          val doorName = row["name"] as? String ?: "Unknown"

          // Extract timestamp (already in ISO format from SQL)
          val timestamp = row["swipe_time"] as? String ?: ""

          DataItem(
              values =
                  mapOf(
                      "Timestamp" to JsonPrimitive(timestamp),
                      "Member" to memberJson,
                      "Door" to JsonPrimitive(doorName),
                  ))
        }

    val dataFields =
        listOf(
            DataField(name = "Timestamp", type = DataType.STRING, label = "Time"),
            DataField(name = "Member", type = DataType.MEMBER, label = "Member"),
            DataField(name = "Door", type = DataType.STRING, label = "Door"),
        )

    val totalTimeTaken = System.currentTimeMillis() - totalStartTime
    val timeTakenInSec = String.format(Locale.US, "%.3f", totalTimeTaken / 1000f)

    val metadata =
        mapOf(
            "Generated at" to
                DateTimeFormatter.ISO_DATE_TIME.format(
                    Instant.now().atZone(java.time.ZoneId.of("America/Chicago"))),
            "Time taken" to "$timeTakenInSec sec",
            "Query time" to "${queryTimeTaken}ms",
            "Members fetch time" to "${membersTimeTaken}ms",
            "Total swipes" to "${rows.size}",
            "SQL Query" to buildQuery(),
        )

    return DataVizResponse(data = dataItems, dataFields = dataFields, metadata = metadata)
  }

  private fun buildQuery(): String {
    return """
    SELECT DISTINCT
        DATE_FORMAT(CONVERT_TZ(e.date, 'UTC', 'America/Chicago'), '%a, %b %e, %Y at %l:%i %p') as swipe_time,
        e.userId as user_id,
        e.cardNumber as card_number,
        u.username as username,
        CONCAT(u.first_name, ' ', u.last_name) as display_name,
        s.name as door_name
    FROM `AccessControl`.events e
    LEFT JOIN `AccessControl`.slots s ON e.controllerId = s.controllerId AND e.slotNumber = s.slotNumber
    LEFT JOIN `dms-makermanager`.users u ON e.userId = u.id
    WHERE e.date >= DATE_SUB(NOW(), INTERVAL 72 HOUR)
    ORDER BY e.date DESC
    LIMIT 5000
    """
        .trimIndent()
  }
}
