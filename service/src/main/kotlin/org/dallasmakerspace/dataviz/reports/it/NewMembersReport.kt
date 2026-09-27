package org.dallasmakerspace.dataviz.reports.it

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.dallasmakerspace.dataviz.DataVizReport
import org.dallasmakerspace.db.master.GenericRepository
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.models.displayAvatarUrl
import org.dallasmakerspace.models.normalizeAvatarUrl

private val CHICAGO_ZONE = ZoneId.of("America/Chicago")
private val UTC_ZONE = ZoneOffset.UTC
private val MONTH_PATTERN = Regex("""^\d{4}-\d{2}$""")
private val UTC_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private const val MILLIS_IN_SECOND = 1000f

@Singleton
class NewMembersReport
@Inject
constructor(
    private val genericRepository: GenericRepository,
    private val memberService: MemberService,
) : DataVizReport() {

  override fun getName(): String = "new-members"

  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val startTime = System.currentTimeMillis()

    val rawMonth = params["month"]?.firstOrNull()
    val month =
        if (rawMonth != null && MONTH_PATTERN.matches(rawMonth)) rawMonth
        else LocalDate.now(CHICAGO_ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM"))

    val yearMonth = YearMonth.parse(month)
    val startUtc =
        yearMonth
            .atDay(1)
            .atStartOfDay(CHICAGO_ZONE)
            .withZoneSameInstant(UTC_ZONE)
            .toLocalDateTime()
    val endUtc =
        yearMonth
            .plusMonths(1)
            .atDay(1)
            .atStartOfDay(CHICAGO_ZONE)
            .withZoneSameInstant(UTC_ZONE)
            .toLocalDateTime()

    val query =
        """
        SET STATEMENT max_statement_time=5 FOR
        SELECT
            DATE_FORMAT(CONVERT_TZ(created, 'UTC', 'America/Chicago'), '%Y-%m-%dT%H:%i:%s') AS joined,
            first_name,
            last_name,
            username,
            email,
            whmcs_real_user_id
        FROM `dms-makermanager`.users
        WHERE ad_active = 1
          AND created >= '${UTC_FORMATTER.format(startUtc)}'
          AND created < '${UTC_FORMATTER.format(endUtc)}'
        ORDER BY created DESC
        """
            .trimIndent()

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

    val usernames =
        dbData?.data?.mapNotNull { row -> row["username"]?.toString()?.takeIf { it.isNotBlank() } }
            ?: emptyList()

    val members =
        if (usernames.isNotEmpty()) memberService.getMembersByUsernameList(usernames)
        else emptyMap()

    val dataItems =
        dbData?.data?.map { row ->
          val username = row["username"]?.toString()?.takeIf { it.isNotBlank() }
          val member = username?.let { members[it] }

          val memberJson = buildJsonObject {
            put("username", JsonPrimitive(username ?: ""))
            put("displayName", JsonPrimitive(member?.displayName ?: username ?: ""))
            put(
                "avatarUrl",
                JsonPrimitive(normalizeAvatarUrl(member?.displayAvatarUrl(), size = 144)))
          }

          val isAddon = row["whmcs_real_user_id"] == null
          val accountTypeBadge = buildJsonObject {
            if (isAddon) {
              put("variant", JsonPrimitive("info"))
              put("text", JsonPrimitive("Addon"))
            } else {
              put("variant", JsonPrimitive("success"))
              put("text", JsonPrimitive("Primary"))
            }
          }

          DataItem(
              values =
                  mapOf(
                      "Member" to memberJson,
                      "First Name" to JsonPrimitive(row["first_name"]?.toString() ?: ""),
                      "Last Name" to JsonPrimitive(row["last_name"]?.toString() ?: ""),
                      "Email" to JsonPrimitive(row["email"]?.toString() ?: ""),
                      "Account Type" to accountTypeBadge,
                      "Joined" to JsonPrimitive(row["joined"]?.toString() ?: ""),
                  ))
        } ?: emptyList()

    val timeTaken = System.currentTimeMillis() - startTime
    val timeTakenInSec = String.format(Locale.US, "%.3f", timeTaken / MILLIS_IN_SECOND)
    val metadata =
        mapOf(
            "Generated at" to
                DateTimeFormatter.ISO_DATE_TIME.format(Instant.now().atZone(CHICAGO_ZONE)),
            "Month" to month,
            "Total records" to "${dataItems.size}",
            "Time taken" to "$timeTakenInSec sec",
            "SQL Query" to query,
        )

    return DataVizResponse(
        data = dataItems,
        dataFields =
            listOf(
                DataField(name = "Member", type = DataType.MEMBER, label = "Member"),
                DataField(name = "First Name", type = DataType.STRING, label = "First Name"),
                DataField(name = "Last Name", type = DataType.STRING, label = "Last Name"),
                DataField(name = "Email", type = DataType.STRING, label = "Email"),
                DataField(name = "Account Type", type = DataType.BADGE, label = "Account Type"),
                DataField(name = "Joined", type = DataType.RELATIVE_DATE, label = "Joined"),
            ),
        metadata = metadata,
    )
  }
}
