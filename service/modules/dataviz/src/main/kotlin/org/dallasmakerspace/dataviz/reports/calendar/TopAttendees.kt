package org.dallasmakerspace.dataviz.reports.calendar

import javax.inject.Inject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.dallasmakerspace.dataviz.reports.SqlReport
import org.dallasmakerspace.db.master.GenericRepository
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.models.displayAvatarUrl
import org.dallasmakerspace.models.normalizeAvatarUrl

class TopAttendees
@Inject
constructor(
    genericRepository: GenericRepository,
    private val memberService: MemberService,
) : SqlReport(genericRepository) {
  override fun getName(): String {
    return "top-attendees"
  }

  override fun getQuery() =
      """
      SET STATEMENT max_statement_time=1.5 FOR
      SELECT
          r.ad_username,
          COUNT(DISTINCT r.event_id) AS `Events Attended`
      FROM
          `dms-calendar`.registrations r
      JOIN
          `dms-calendar`.events e ON r.event_id = e.id
      WHERE
          r.attended = 1
          AND e.event_start >= DATE_SUB(CURDATE(), INTERVAL 90 DAY)
          AND e.status = 'completed'
          AND r.ad_username IS NOT NULL
          AND r.ad_username != ''
      GROUP BY
          r.ad_username
      ORDER BY
          `Events Attended` DESC
      LIMIT 10
      """

  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    // Get base data from parent (calendar DB query)
    val baseResponse = super.getData(params)

    // Collect all usernames from the data
    val usernames =
        baseResponse.data
            ?.mapNotNull { it.values["ad_username"]?.toString()?.removeSurrounding("\"") }
            ?.filter { it.isNotBlank() } ?: emptyList()

    // Batch fetch all members at once
    val members = memberService.getMembersByUsernameList(usernames)

    // Enrich with profile data
    val enrichedData =
        baseResponse.data?.map { dataItem ->
          val adUsername = dataItem.values["ad_username"]?.toString()?.removeSurrounding("\"")
          val member = adUsername?.let { members[it] }

          val avatarUrl = normalizeAvatarUrl(member?.displayAvatarUrl(), size = 144)

          // Build member JSON object
          val memberJson = buildJsonObject {
            put("username", JsonPrimitive(adUsername ?: ""))
            put("displayName", JsonPrimitive(member?.displayName ?: adUsername ?: ""))
            put("avatarUrl", JsonPrimitive(avatarUrl))
          }

          DataItem(
              values =
                  mapOf(
                      "Member" to memberJson,
                      "Events Attended" to (dataItem.values["Events Attended"] ?: JsonPrimitive(0)),
                  ))
        }

    return baseResponse.copy(data = enrichedData)
  }

  override fun getDataFields(dbData: GenericRepository.QueryResult?): List<DataField> {
    return listOf(
        DataField(name = "Member", type = DataType.MEMBER, label = "Member"),
        DataField(name = "Events Attended", type = DataType.NUMBER, label = "Events Attended"),
    )
  }
}
