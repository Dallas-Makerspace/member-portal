package org.dallasmakerspace.dataviz.reports.it

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.dallasmakerspace.activedirectory.IActiveDirectoryClient
import org.dallasmakerspace.dataviz.DataVizReport
import org.dallasmakerspace.db.master.GenericRepository
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.models.displayAvatarUrl
import org.dallasmakerspace.models.normalizeAvatarUrl

class BadgeValidationMergedReport
@Inject
constructor(
    private val genericRepository: GenericRepository,
    private val adClient: IActiveDirectoryClient,
    private val memberService: MemberService,
) : DataVizReport() {
  override fun getName(): String {
    return "badge-validation-merged"
  }

  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val startTime = System.currentTimeMillis()

    // 1. Query MakerManager for all active users with badges
    val mmBadges = queryMakerManagerBadges()

    // 2. Query Active Directory for all users with badges, then filter for active accounts.
    // LDAP is blocking - scoped to Dispatchers.IO like other AD calls on this shared route.
    val allAdUsers = withContext(Dispatchers.IO) { adClient.getAllActiveUsersWithBadges() }
    val adBadges =
        allAdUsers.filter { (_, attributes) ->
          val userAccountControl = (attributes["userAccountControl"] as? String)?.toIntOrNull() ?: 0
          // Check if account is NOT disabled (bit 0x0002 = ACCOUNTDISABLE)
          (userAccountControl and 0x0002) == 0
        }

    // 3. Merge by username and filter to invalid badges FIRST
    val allUsernames = (mmBadges.keys + adBadges.keys).toSet()
    val invalidBadgeData = mutableMapOf<String, Pair<String?, String?>>()

    allUsernames.forEach { username ->
      val mmBadge = mmBadges[username]
      val adBadge = adBadges[username]?.get("employeeID") as? String

      // Only include if either badge is invalid
      if (isInvalidBadge(mmBadge) || isInvalidBadge(adBadge)) {
        invalidBadgeData[username] = Pair(mmBadge, adBadge)
      }
    }

    // 4. Batch fetch ONLY members with invalid badges
    val members = memberService.getMembersByUsernameList(invalidBadgeData.keys.toList())

    // 5. Create combined data items
    val dataItems =
        invalidBadgeData.entries
            .map { (username, badges) ->
              val (mmBadge, adBadge) = badges
              createDataItem(username, mmBadge, adBadge, members[username])
            }
            .sortedWith(
                compareBy<DataItem> {
                      minOf(
                          (it.values["Badge (MM)"] as? JsonPrimitive)?.content?.length ?: 10,
                          (it.values["Badge (AD)"] as? JsonPrimitive)?.content?.length ?: 10,
                      )
                    }
                    .thenBy { (it.values["Badge (MM)"] as? JsonPrimitive)?.content ?: "" })

    val timeTaken = System.currentTimeMillis() - startTime
    val metadata =
        mapOf(
            "Generated at" to java.time.Instant.now().toString(),
            "Time taken" to "${timeTaken}ms",
            "Total invalid badges" to "${dataItems.size}",
            "Source" to "MakerManager + Active Directory",
        )

    return DataVizResponse(
        data = dataItems,
        dataFields = getDataFields(),
        metadata = metadata,
    )
  }

  /**
   * Helper to check if a badge is invalid (< 10 digits and not all zeros).
   *
   * @param badge The badge number to check
   * @return true if the badge is invalid, false otherwise
   */
  private fun isInvalidBadge(badge: String?): Boolean {
    if (badge.isNullOrEmpty()) return false
    if (badge.length >= 10) return false
    // Exclude badges that are entirely zeros (e.g., "0", "00", "000")
    if (badge.all { it == '0' }) return false
    return true
  }

  /**
   * Creates a DataItem for a member with badges from both sources.
   *
   * @param username The member's username
   * @param mmBadge Badge from MakerManager (may be null)
   * @param adBadge Badge from Active Directory (may be null)
   * @param member The member profile data (may be null)
   * @return DataItem with member and badge information
   */
  private fun createDataItem(
      username: String,
      mmBadge: String?,
      adBadge: String?,
      member: org.dallasmakerspace.models.DMSMember?,
  ): DataItem {
    val avatarUrl = normalizeAvatarUrl(member?.displayAvatarUrl(), size = 144)

    // Build member JSON object
    val memberJson = buildJsonObject {
      put("username", JsonPrimitive(username))
      put("displayName", JsonPrimitive(member?.displayName ?: username))
      put("avatarUrl", JsonPrimitive(avatarUrl))
    }

    // Generate status string for easier filtering
    val status = buildStatusString(mmBadge, adBadge)

    return DataItem(
        values =
            mapOf(
                "Member" to memberJson,
                "Status" to JsonPrimitive(status),
                "Badge (MM)" to JsonPrimitive(mmBadge ?: ""),
                "Badge (AD)" to JsonPrimitive(adBadge ?: ""),
            ))
  }

  /**
   * Builds a human-readable status string describing badge validation issues.
   *
   * @param mmBadge Badge from MakerManager (may be null)
   * @param adBadge Badge from Active Directory (may be null)
   * @return Status string like "MM: 6 digits, AD: 8 digits"
   */
  private fun buildStatusString(mmBadge: String?, adBadge: String?): String {
    val mmInvalid = isInvalidBadge(mmBadge)
    val adInvalid = isInvalidBadge(adBadge)

    return when {
      mmInvalid && adInvalid -> {
        val mmLen = mmBadge?.length ?: 0
        val adLen = adBadge?.length ?: 0
        if (mmLen == adLen) {
          "Both: $mmLen digits"
        } else {
          "MM: $mmLen digits, AD: $adLen digits"
        }
      }
      mmInvalid && !adInvalid -> {
        val mmLen = mmBadge?.length ?: 0
        if (adBadge.isNullOrEmpty()) {
          "MM: $mmLen digits, AD: missing"
        } else {
          "MM: $mmLen digits"
        }
      }
      !mmInvalid && adInvalid -> {
        val adLen = adBadge?.length ?: 0
        if (mmBadge.isNullOrEmpty()) {
          "AD: $adLen digits, MM: missing"
        } else {
          "AD: $adLen digits"
        }
      }
      else -> "Unknown"
    }
  }

  /**
   * Queries MakerManager database for all active users with badges.
   *
   * @return Map of username to badge number
   */
  private suspend fun queryMakerManagerBadges(): Map<String, String> {
    val result =
        genericRepository.getReportData(
            """
            SELECT u.username, b.number
            FROM `dms-makermanager`.badges b
            INNER JOIN `dms-makermanager`.users u ON b.user_id = u.id
            WHERE u.ad_active = 1
            """
                .trimIndent())

    return result
        ?.data
        ?.associate { row ->
          val username = row["username"]?.toString()?.removeSurrounding("\"")
          val badgeNumber = row["number"]?.toString()?.removeSurrounding("\"")
          username to badgeNumber
        }
        ?.filterKeys { it != null }
        ?.filterValues { it != null } as? Map<String, String> ?: emptyMap()
  }

  private fun getDataFields(): List<DataField> {
    return listOf(
        DataField(name = "Member", type = DataType.MEMBER, label = "Member"),
        DataField(name = "Status", type = DataType.STRING, label = "Status"),
        DataField(name = "Badge (MM)", type = DataType.STRING, label = "Badge (MM)"),
        DataField(name = "Badge (AD)", type = DataType.STRING, label = "Badge (AD)"),
    )
  }
}
