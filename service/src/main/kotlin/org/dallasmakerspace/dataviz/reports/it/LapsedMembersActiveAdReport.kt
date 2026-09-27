package org.dallasmakerspace.dataviz.reports.it

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.dallasmakerspace.activedirectory.IActiveDirectoryClient
import org.dallasmakerspace.dataviz.DataVizReport
import org.dallasmakerspace.db.master.GenericRepository
import org.dallasmakerspace.db.master.IMakerManagerDataService
import org.dallasmakerspace.db.master.IWhmcsDataService
import org.dallasmakerspace.db.master.TimelineEntryType
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.models.displayAvatarUrl
import org.dallasmakerspace.models.normalizeAvatarUrl

class LapsedMembersActiveAdReport
@Inject
constructor(
    private val adClient: IActiveDirectoryClient,
    private val memberService: MemberService,
    private val genericRepository: GenericRepository,
    private val whmcsDataService: IWhmcsDataService,
    private val makerManagerDataService: IMakerManagerDataService,
) : DataVizReport() {
  override fun getName(): String {
    return "lapsed-members-active-ad"
  }

  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val startTime = System.currentTimeMillis()

    // LDAP is blocking - scoped to Dispatchers.IO like other AD calls on this shared route.
    val allAdUsers = withContext(Dispatchers.IO) { adClient.getAllActiveUsersWithBadges() }

    // Filter for users with badge "0000000" and enabled accounts
    val lapsedMembers =
        allAdUsers
            .filter { (_, attributes) ->
              val badgeNumber = attributes["employeeID"] as? String ?: ""
              val userAccountControl =
                  (attributes["userAccountControl"] as? String)?.toIntOrNull() ?: 0
              val isActive =
                  (userAccountControl and 0x0002) == 0 // Check if account is NOT disabled

              // Look for badge "0000000" (7 zeros) and active account
              badgeNumber == "0000000" && isActive
            }
            .toList()

    // Collect usernames for member service lookup
    val usernames = lapsedMembers.map { it.first }

    // Query MakerManager for ad_active status
    val mmAdActiveStatus = queryMakerManagerAdActiveStatus(usernames)

    // Batch fetch member profiles
    val members = memberService.getMembersByUsernameList(usernames)

    // Resolve WHMCS IDs via MakerManager directly — getMembersByUsernameList does not
    // populate DMSMember.accountInfo (only the single-member fetch does)
    val relatedAccounts = makerManagerDataService.getAccountInfoMap(usernames)
    val whmcsIdByUsername =
        relatedAccounts
            .mapNotNull { (username, accountInfo) ->
              val account =
                  if (accountInfo.isPrimaryAccount) accountInfo.primaryAccount
                  else accountInfo.addonAccounts.find { it.username == username }
              account?.whmcsId?.let { username to it }
            }
            .toMap()

    // Batch query WHMCS account status (only if we have IDs to query)
    val whmcsAccountStatuses =
        if (whmcsIdByUsername.isNotEmpty()) {
          whmcsDataService.getAccountInfoMap(whmcsIdByUsername.values.distinct())
        } else {
          emptyMap()
        }

    // Transform to DataItems
    val dataItems =
        lapsedMembers.map { (username, attributes) ->
          val displayName = attributes["displayName"] as? String ?: username
          val userAccountControl = (attributes["userAccountControl"] as? String)?.toIntOrNull() ?: 0
          val adEnabled = (userAccountControl and 0x0002) == 0
          val mmAdActive = mmAdActiveStatus[username] ?: false
          val member = members[username]

          // Currently active in WHMCS: last timeline entry is an ongoing ACTIVE period —
          // same definition as the profile debug info (MemberService.computeWhmcsStatus)
          val whmcsActive =
              whmcsIdByUsername[username]
                  ?.let { whmcsId -> whmcsAccountStatuses[whmcsId]?.timeline?.lastOrNull() }
                  ?.let { it.type == TimelineEntryType.ACTIVE && it.endDate == null } ?: false

          val avatarUrl = normalizeAvatarUrl(member?.displayAvatarUrl(), size = 144)

          // Build member JSON object
          val memberJson = buildJsonObject {
            put("username", JsonPrimitive(username))
            put("displayName", JsonPrimitive(member?.displayName ?: displayName))
            put("avatarUrl", JsonPrimitive(avatarUrl))
          }

          DataItem(
              values =
                  mapOf(
                      "Member" to memberJson,
                      "AD Account Enabled" to createStatusBadge(adEnabled),
                      "MM AD Active" to createStatusBadge(mmAdActive),
                      "WHMCS Active" to createStatusBadge(whmcsActive),
                  ))
        }

    val timeTaken = System.currentTimeMillis() - startTime
    val metadata =
        mapOf(
            "Generated at" to java.time.Instant.now().toString(),
            "Time taken" to "${timeTaken}ms",
            "Total lapsed members" to "${dataItems.size}",
            "Source" to "Active Directory (LDAP)",
            "Description" to
                "Members with badge number 0000000 and enabled AD accounts (typically for temporary email/file access)",
        )

    return DataVizResponse(
        data = dataItems,
        dataFields = getDataFields(),
        metadata = metadata,
    )
  }

  /**
   * Queries MakerManager database for ad_active status of given users.
   *
   * @param usernames List of usernames to query
   * @return Map of username to ad_active status (true/false)
   */
  private suspend fun queryMakerManagerAdActiveStatus(
      usernames: List<String>
  ): Map<String, Boolean> {
    if (usernames.isEmpty()) return emptyMap()

    // Build OR conditions for username matching
    val usernameConditions = usernames.joinToString(" OR ") { "u.username = '$it'" }

    val result =
        genericRepository.getReportData(
            """
            SELECT u.username, u.ad_active
            FROM `dms-makermanager`.users u
            WHERE $usernameConditions
            """
                .trimIndent())

    return result
        ?.data
        ?.associate { row ->
          val username = row["username"]?.toString()?.removeSurrounding("\"")
          val adActive =
              when (val value = row["ad_active"]) {
                is Number -> value.toInt() == 1
                is Boolean -> value
                is String -> value == "1" || value.lowercase() == "true"
                else -> false
              }
          username to adActive
        }
        ?.filterKeys { it != null } as? Map<String, Boolean> ?: emptyMap()
  }

  private fun getDataFields(): List<DataField> {
    return listOf(
        DataField(name = "Member", type = DataType.MEMBER, label = "Member"),
        DataField(name = "AD Account Enabled", type = DataType.BADGE, label = "AD Account Enabled"),
        DataField(name = "MM AD Active", type = DataType.BADGE, label = "MM AD Active"),
        DataField(name = "WHMCS Active", type = DataType.BADGE, label = "WHMCS Active"),
    )
  }

  /**
   * Creates a badge JSON object for status display.
   *
   * @param isActive Whether the status is active
   * @return JsonObject with variant (success/danger) and text (Active/Inactive)
   */
  private fun createStatusBadge(isActive: Boolean): JsonObject {
    return buildJsonObject {
      put("variant", JsonPrimitive(if (isActive) "success" else "danger"))
      put("text", JsonPrimitive(if (isActive) "Active" else "Inactive"))
    }
  }
}
