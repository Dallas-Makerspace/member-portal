package org.dallasmakerspace.dataviz.reports.it

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.dallasmakerspace.dataviz.DataVizReport
import org.dallasmakerspace.db.master.GenericRepository
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.models.DMSMember
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.models.displayAvatarUrl
import org.dallasmakerspace.models.normalizeAvatarUrl
import org.dallasmakerspace.smartwaiver.ISmartwaiverApiClient

private const val DEFAULT_DAYS = 0L
private const val DAYS_7 = 7L
private const val DAYS_30 = 30L
private val ALLOWED_DAYS = setOf(DEFAULT_DAYS, DAYS_7, DAYS_30)

@Singleton
class RecentSignedWaivers
@Inject
constructor(
    private val smartwaiverClient: ISmartwaiverApiClient,
    private val genericRepository: GenericRepository,
    private val memberService: MemberService,
) : DataVizReport() {

  override fun getName(): String = "recent-signed-waivers"

  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val startTime = System.currentTimeMillis()
    val requestedDays = params["days"]?.firstOrNull()?.toLongOrNull() ?: DEFAULT_DAYS
    val reportDays = if (requestedDays in ALLOWED_DAYS) requestedDays else DEFAULT_DAYS
    val toDate = LocalDate.now()
    val fromDate = toDate.minusDays(reportDays)

    val waivers = smartwaiverClient.getWaiverDetails(fromDate, toDate)

    val emails = waivers.mapNotNull { it.email?.lowercase() }.filter { it.isNotEmpty() }.toSet()
    val emailToUsername = lookupMakerManagerUsers(emails)
    val members =
        if (emailToUsername.isNotEmpty()) {
          memberService.getMembersByUsernameList(emailToUsername.values.distinct())
        } else {
          emptyMap()
        }

    val smartwaiverFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    val displayFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy h:mm a")
    val utcZone = ZoneId.of("UTC")
    val chicagoZone = ZoneId.of("America/Chicago")

    val dataItems =
        waivers
            .sortedByDescending { it.createdOn }
            .map { waiver ->
              val utcDateTime = LocalDateTime.parse(waiver.createdOn, smartwaiverFormatter)
              val chicagoDateTime =
                  utcDateTime.atZone(utcZone).withZoneSameInstant(chicagoZone).toLocalDateTime()
              val username = waiver.email?.lowercase()?.let { emailToUsername[it] }
              DataItem(
                  values =
                      mapOf(
                          "Date Signed" to JsonPrimitive(chicagoDateTime.format(displayFormatter)),
                          "First Name" to JsonPrimitive(waiver.firstName ?: ""),
                          "Last Name" to JsonPrimitive(waiver.lastName ?: ""),
                          "Email" to JsonPrimitive(waiver.email ?: ""),
                          "DMS Member" to buildMemberJson(username, members[username]),
                      ))
            }

    val timeTaken = System.currentTimeMillis() - startTime
    val metadata =
        mapOf(
            "Generated at" to
                DateTimeFormatter.ISO_DATE_TIME.format(java.time.Instant.now().atZone(chicagoZone)),
            "Date range" to
                if (reportDays == DEFAULT_DAYS) "$toDate (today)"
                else "$fromDate to $toDate (past $reportDays days)",
            "Total waivers" to "${waivers.size}",
            "Time taken" to "${timeTaken}ms",
        )

    return DataVizResponse(
        data = dataItems,
        dataFields =
            listOf(
                DataField(name = "Date Signed", type = DataType.STRING, label = "Date Signed"),
                DataField(name = "First Name", type = DataType.STRING, label = "First Name"),
                DataField(name = "Last Name", type = DataType.STRING, label = "Last Name"),
                DataField(name = "Email", type = DataType.STRING, label = "Email"),
                DataField(name = "DMS Member", type = DataType.MEMBER, label = "DMS Member"),
            ),
        metadata = metadata,
    )
  }

  private suspend fun lookupMakerManagerUsers(emails: Set<String>): Map<String, String> {
    if (emails.isEmpty()) return emptyMap()
    val inList = emails.joinToString(", ") { "'${it.replace("'", "''")}'" }
    val result =
        genericRepository.getReportData(
            """
            SELECT u.username, LOWER(u.email) AS email
            FROM `dms-makermanager`.users u
            WHERE LOWER(u.email) IN ($inList)
            """
                .trimIndent())
    return result
        ?.data
        ?.mapNotNull { row ->
          val email = row["email"]?.toString()?.removeSurrounding("\"") ?: return@mapNotNull null
          val username =
              row["username"]?.toString()?.removeSurrounding("\"") ?: return@mapNotNull null
          if (email.isNotEmpty() && username.isNotEmpty()) email to username else null
        }
        ?.toMap() ?: emptyMap()
  }

  private fun buildMemberJson(username: String?, member: DMSMember?): JsonElement {
    if (username == null) return JsonPrimitive("No account")
    return buildJsonObject {
      put("username", JsonPrimitive(username))
      put("displayName", JsonPrimitive(member?.displayName ?: username))
      put("avatarUrl", JsonPrimitive(normalizeAvatarUrl(member?.displayAvatarUrl(), size = 144)))
    }
  }
}
