package org.dallasmakerspace.dataviz.reports.membership

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
import org.dallasmakerspace.models.normalizeAvatarUrl

class BadgeValidationReport
@Inject
constructor(
    genericRepository: GenericRepository,
    private val memberService: MemberService,
) : SqlReport(genericRepository) {
  override fun getName(): String {
    return "badge-validation"
  }

  override fun getQuery() =
      """
      SELECT
          b.number AS "Badge Number",
          CHAR_LENGTH(b.number) AS "Badge Length",
          u.username AS "username"
      FROM
          `dms-makermanager`.badges b
      INNER JOIN
          `dms-makermanager`.users u ON b.user_id = u.id
      WHERE
          u.ad_active = 1
          AND CHAR_LENGTH(b.number) < 10
      ORDER BY
          CHAR_LENGTH(b.number) ASC,
          b.number ASC
      """

  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    // Get base data from parent (MakerManager DB query)
    val baseResponse = super.getData(params)

    // Collect all usernames from the data
    val usernames =
        baseResponse.data
            ?.mapNotNull { it.values["username"]?.toString()?.removeSurrounding("\"") }
            ?.filter { it.isNotBlank() } ?: emptyList()

    // Batch fetch all members at once
    val members = memberService.getMembersByUsernameList(usernames)

    // Enrich with profile data
    val enrichedData =
        baseResponse.data?.map { dataItem ->
          val username = dataItem.values["username"]?.toString()?.removeSurrounding("\"")
          val member = username?.let { members[it] }

          val avatarUrl = normalizeAvatarUrl(member?.discourseAvatarUrl, size = 144)

          // Build member JSON object
          val memberJson = buildJsonObject {
            put("username", JsonPrimitive(username ?: ""))
            put("displayName", JsonPrimitive(member?.displayName ?: username ?: ""))
            put("avatarUrl", JsonPrimitive(avatarUrl))
          }

          DataItem(
              values =
                  mapOf(
                      "Badge Number" to (dataItem.values["number"] ?: JsonPrimitive("")),
                      "Badge Length" to (dataItem.values["Badge Length"] ?: JsonPrimitive(0)),
                      "Member" to memberJson,
                  ))
        }

    return baseResponse.copy(data = enrichedData)
  }

  override fun getDataFields(dbData: GenericRepository.QueryResult?): List<DataField> {
    return listOf(
        DataField(name = "Badge Number", type = DataType.STRING, label = "Badge Number"),
        DataField(name = "Badge Length", type = DataType.NUMBER, label = "Badge Length"),
        DataField(name = "Member", type = DataType.MEMBER, label = "Member"),
    )
  }
}
