package org.dallasmakerspace.dataviz.reports.groups

import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.dallasmakerspace.dataviz.DataVizReport
import org.dallasmakerspace.members.GroupHistoryRepository
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.models.ActionType
import org.dallasmakerspace.models.ActorDisplayResolver
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.models.normalizeAvatarUrl
import org.dallasmakerspace.routing.Groups

class GroupHistoryReport
@Inject
constructor(
    private val groupHistoryRepository: GroupHistoryRepository,
    private val memberService: MemberService,
) : DataVizReport() {

  override fun getName(): String = "group-history"

  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val groupSlug = params["group"]?.firstOrNull()
    if (groupSlug.isNullOrBlank()) {
      return DataVizResponse(
          data = null,
          dataFields = emptyList(),
          metadata = mapOf("error" to "Group parameter is required"),
      )
    }

    val groupName = Groups.getNameFromSlug(groupSlug)
    val totalStartTime = System.currentTimeMillis()

    // Fetch group history records
    val historyStartTime = System.currentTimeMillis()
    val historyRecords = groupHistoryRepository.getGroupHistoryByName(groupName)
    val historyTimeTaken = System.currentTimeMillis() - historyStartTime

    // Collect all usernames (actors and members)
    val allUsernames =
        (historyRecords.map { it.actorUsername } + historyRecords.map { it.memberUsername })
            .filter { it.isNotBlank() }
            .toSet()

    // Batch fetch all members
    val membersStartTime = System.currentTimeMillis()
    val members = memberService.getMembersByUsernameList(allUsernames.toList())
    val membersTimeTaken = System.currentTimeMillis() - membersStartTime

    val totalTimeTaken = System.currentTimeMillis() - totalStartTime
    val totalTimeTakenInSec = String.format("%.3f", totalTimeTaken / 1000f)

    // Transform history records to data items
    val dataItems =
        historyRecords.map { record ->
          val actorMember = members[record.actorUsername]
          val memberMember = members[record.memberUsername]

          // Resolve actor display info (handles special service accounts)
          val actorInfo =
              ActorDisplayResolver.resolve(
                  actorUsername = record.actorUsername,
                  groupName = groupName,
                  fallbackDisplayName = actorMember?.displayName,
              )

          // Build actor JSON based on type:
          // - "Self" → plain text
          // - External (DMS Learn, DMS Calendar) → link with url
          // - Regular members → member card
          val actorJson: JsonElement =
              when {
                actorInfo.displayName == "Self" -> JsonPrimitive("Self")
                actorInfo.isExternal ->
                    buildJsonObject {
                      put("text", JsonPrimitive(actorInfo.displayName))
                      put("url", JsonPrimitive(actorInfo.link))
                    }
                else -> {
                  val actorAvatarUrl =
                      normalizeAvatarUrl(actorMember?.discourseAvatarUrl, size = 144)
                  buildJsonObject {
                    put("username", JsonPrimitive(record.actorUsername))
                    put("displayName", JsonPrimitive(actorInfo.displayName))
                    put("avatarUrl", JsonPrimitive(actorAvatarUrl))
                  }
                }
              }

          // Build member JSON object
          val memberAvatarUrl = normalizeAvatarUrl(memberMember?.discourseAvatarUrl, size = 144)
          val memberJson = buildJsonObject {
            put("username", JsonPrimitive(record.memberUsername))
            put("displayName", JsonPrimitive(memberMember?.displayName ?: record.memberUsername))
            put("avatarUrl", JsonPrimitive(memberAvatarUrl))
          }

          // Format timestamp as ISO for relative date component
          val isoTimestamp =
              java.time.LocalDateTime.of(
                      record.eventTimestamp.year,
                      record.eventTimestamp.monthNumber,
                      record.eventTimestamp.dayOfMonth,
                      record.eventTimestamp.hour,
                      record.eventTimestamp.minute,
                      record.eventTimestamp.second,
                  )
                  .toString()

          // Action type badge with variant for color
          val actionBadge = buildJsonObject {
            when (record.actionType) {
              ActionType.ADD_USER -> {
                put("variant", JsonPrimitive("success"))
                put("text", JsonPrimitive("Added"))
              }
              ActionType.REMOVE_USER -> {
                put("variant", JsonPrimitive("danger"))
                put("text", JsonPrimitive("Removed"))
              }
            }
          }

          DataItem(
              values =
                  mapOf(
                      "Member" to memberJson,
                      "Action" to actionBadge,
                      "Actor" to actorJson,
                      "Date" to JsonPrimitive(isoTimestamp),
                  ))
        }

    val dataFields =
        listOf(
            DataField(name = "Member", type = DataType.MEMBER, label = "Member"),
            DataField(name = "Action", type = DataType.BADGE, label = "Action"),
            DataField(name = "Actor", type = DataType.MEMBER, label = "Changed By"),
            DataField(name = "Date", type = DataType.RELATIVE_DATE, label = "Date"),
        )

    val metadata: Map<String, String> =
        mapOf(
            "Generated at" to
                DateTimeFormatter.ISO_DATE_TIME.format(
                    Instant.now().atZone(java.time.ZoneId.of("America/Chicago"))),
            "Time taken" to "$totalTimeTakenInSec sec",
            "Total records" to "${historyRecords.size}",
        )

    return DataVizResponse(data = dataItems, dataFields = dataFields, metadata = metadata)
  }
}
