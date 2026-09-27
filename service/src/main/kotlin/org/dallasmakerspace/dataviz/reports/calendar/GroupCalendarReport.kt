package org.dallasmakerspace.dataviz.reports.calendar

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*
import javax.inject.Inject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.dallasmakerspace.dataviz.reports.SqlReport
import org.dallasmakerspace.db.master.GenericRepository
import org.dallasmakerspace.members.GroupService
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.models.normalizeAvatarUrl

class GroupCalendarReport
@Inject
constructor(
    private val genericRepository: GenericRepository,
    private val groupService: GroupService,
    private val memberService: MemberService,
) : SqlReport(genericRepository) {
  override fun getName(): String {
    return "group-calendar"
  }

  override fun getQuery() =
      """
      -- This method is not used; see getData() for parameterized query
      SELECT 1
      """

  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    // Extract and validate group parameter
    val groupSlug = params["group"]?.firstOrNull()
    if (groupSlug.isNullOrBlank()) {
      return DataVizResponse(
          data = null,
          dataFields = emptyList(),
          metadata = mapOf("error" to "Group parameter is required"),
      )
    }

    // Validate group exists in AD (this will throw exception if not found)
    val groupName =
        try {
          groupService.getGroup(groupSlug).name
        } catch (e: Exception) {
          return DataVizResponse(
              data = null,
              dataFields = emptyList(),
              metadata = mapOf("error" to "Group '$groupSlug' not found: ${e.message}"),
          )
        }

    // Build query with group name parameter (escaped for SQL)
    val query =
        """
        SET STATEMENT max_statement_time=1.5 FOR
        SELECT
            e.id as event_id,
            e.name as event_name,
            DATE_FORMAT(CONVERT_TZ(e.event_start, 'GMT', 'America/Chicago'), '%Y-%m-%dT%H:%i:%s') as event_start,
            c.ad_username as organizer_username,
            COUNT(r.ad_username) as attendee_count
        FROM `dms-calendar`.events e
        JOIN `dms-calendar`.prerequisites p ON e.fulfills_prerequisite_id = p.id
        LEFT JOIN `dms-calendar`.contacts c ON e.contact_id = c.id
        LEFT JOIN `dms-calendar`.registrations r ON e.id = r.event_id AND r.attended = 1
        WHERE
            e.event_start >= DATE_SUB(CURDATE(), INTERVAL 90 DAY)
            AND e.event_start < DATE_ADD(CURDATE(), INTERVAL 30 DAY)
            AND p.ad_group = '${groupName.replace("'", "''")}'
        GROUP BY e.id
        ORDER BY e.event_start DESC
        """
            .trimIndent()

    // Execute query and build response
    val startTime = System.currentTimeMillis()
    val dbData =
        try {
          genericRepository.getReportData(query)
        } catch (ex: Exception) {
          val errorMessage =
              if (ex.message?.contains("max_statement_time exceeded") == true) {
                "Query timed out (max_statement_time exceeded)"
              } else {
                "Query failed: ${ex.message}"
              }
          return DataVizResponse(
              data = null,
              dataFields = emptyList(),
              metadata = mapOf("error" to errorMessage),
          )
        }
    val endTime = System.currentTimeMillis()
    val timeTaken = endTime - startTime
    val timeTakenInSec = String.format(Locale.US, "%.3f", timeTaken / 1000f)

    // Get base response structure
    val baseDataFields = getDataFields(dbData)
    val baseDataItems = getDataItems(dbData)

    // Collect all organizer usernames
    val organizerUsernames =
        baseDataItems
            ?.mapNotNull { it.values["ad_username"]?.toString()?.removeSurrounding("\"") }
            ?.filter { it.isNotBlank() }
            ?.toSet() ?: emptySet()

    // Batch fetch all organizer members
    val members = memberService.getMembersByUsernameList(organizerUsernames.toList())

    // Enrich data with member information
    val enrichedData =
        baseDataItems?.map { dataItem ->
          val organizerUsername =
              dataItem.values["ad_username"]?.toString()?.removeSurrounding("\"")
          val member = organizerUsername?.let { members[it] }

          val avatarUrl = normalizeAvatarUrl(member?.discourseAvatarUrl, size = 144)

          // Build organizer member JSON object
          val organizerJson = buildJsonObject {
            put("username", JsonPrimitive(organizerUsername ?: ""))
            put("displayName", JsonPrimitive(member?.displayName ?: organizerUsername ?: ""))
            put("avatarUrl", JsonPrimitive(avatarUrl))
          }

          // Build event link
          val eventLinkJson = buildJsonObject {
            put("text", JsonPrimitive(dataItem.values["name"]?.toString()?.replace("\"", "") ?: ""))
            put(
                "url",
                JsonPrimitive(
                    "https://calendar.dallasmakerspace.org/events/view/" +
                        "${dataItem.values["id"]?.toString()}"),
            )
          }

          DataItem(
              values =
                  mapOf(
                      "Event" to eventLinkJson,
                      "Date" to (dataItem.values["event_start"] ?: JsonPrimitive("")),
                      "Organizer" to organizerJson,
                  ))
        }

    val metadata: Map<String, String> =
        mapOf(
            "Generated at" to
                DateTimeFormatter.ISO_DATE_TIME.format(
                    Instant.now().atZone(java.time.ZoneId.of("America/Chicago"))),
            "Time taken" to "$timeTakenInSec sec",
            "SQL Query" to query,
        )

    return DataVizResponse(data = enrichedData, dataFields = baseDataFields, metadata = metadata)
  }

  override fun getDataFields(dbData: GenericRepository.QueryResult?): List<DataField> {
    return listOf(
        DataField(name = "Event", type = DataType.LINK, label = "Event"),
        DataField(name = "Date", type = DataType.RELATIVE_DATE, label = "Date"),
        DataField(name = "Organizer", type = DataType.MEMBER, label = "Organizer"),
    )
  }
}
