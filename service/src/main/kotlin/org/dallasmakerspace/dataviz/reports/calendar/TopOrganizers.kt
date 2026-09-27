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

class TopOrganizers
@Inject
constructor(
    genericRepository: GenericRepository,
    private val memberService: MemberService,
) : SqlReport(genericRepository) {
  override fun getName(): String {
    return "top-organizers"
  }

  override fun getQuery() =
      """
      SET STATEMENT max_statement_time=1.5 FOR
      SELECT
          c.ad_username,
          COUNT(DISTINCT e.id) AS `Event Count`,
          COUNT(DISTINCT CONCAT(e.id, '-', r.ad_username)) AS `Total Attendees`
      FROM
          `dms-calendar`.events e
      JOIN
          `dms-calendar`.contacts c ON e.contact_id = c.id
      LEFT JOIN
          `dms-calendar`.registrations r ON e.id = r.event_id AND r.attended = 1
      WHERE
          e.event_start >= DATE_SUB(CURDATE(), INTERVAL 90 DAY)
          AND e.status = 'completed'
          AND c.ad_username IS NOT NULL
      GROUP BY
          c.ad_username
      ORDER BY
          `Total Attendees` DESC,
          `Event Count` DESC
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
                      "Total Attendees" to (dataItem.values["Total Attendees"] ?: JsonPrimitive(0)),
                      "Event Count" to (dataItem.values["Event Count"] ?: JsonPrimitive(0)),
                  ))
        }

    return baseResponse.copy(data = enrichedData)
  }

  override fun getDataFields(dbData: GenericRepository.QueryResult?): List<DataField> {
    return listOf(
        DataField(name = "Member", type = DataType.MEMBER, label = "Member"),
        DataField(name = "Total Attendees", type = DataType.NUMBER, label = "Total Attendees"),
        DataField(name = "Event Count", type = DataType.NUMBER, label = "Event Count"),
    )
  }
}
