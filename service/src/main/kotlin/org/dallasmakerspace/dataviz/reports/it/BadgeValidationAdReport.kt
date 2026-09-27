package org.dallasmakerspace.dataviz.reports.it

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.dallasmakerspace.activedirectory.IActiveDirectoryClient
import org.dallasmakerspace.dataviz.DataVizReport
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.models.displayAvatarUrl
import org.dallasmakerspace.models.normalizeAvatarUrl

class BadgeValidationAdReport
@Inject
constructor(
    private val adClient: IActiveDirectoryClient,
    private val memberService: MemberService,
) : DataVizReport() {
  override fun getName(): String {
    return "badge-validation-ad"
  }

  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val startTime = System.currentTimeMillis()

    // LDAP is blocking - scoped to Dispatchers.IO like other AD calls on this shared route.
    val allUsersWithBadges = withContext(Dispatchers.IO) { adClient.getAllActiveUsersWithBadges() }

    // Filter for badge numbers < 10 digits and active accounts
    val invalidBadges =
        allUsersWithBadges
            .filter { (_, attributes) ->
              val badgeNumber = attributes["employeeID"] as? String ?: ""
              val userAccountControl =
                  (attributes["userAccountControl"] as? String)?.toIntOrNull() ?: 0
              val isActive =
                  (userAccountControl and 0x0002) == 0 // Check if account is NOT disabled

              badgeNumber.isNotEmpty() && badgeNumber.length < 10 && isActive
            }
            .toList()
            .sortedWith(
                compareBy<Pair<String, Map<String, Any?>>> {
                      (it.second["employeeID"] as? String)?.length ?: 0
                    }
                    .thenBy { it.second["employeeID"] as? String })

    // Collect usernames for member service lookup
    val usernames = invalidBadges.map { it.first }

    // Batch fetch member profiles
    val members = memberService.getMembersByUsernameList(usernames)

    // Transform to DataItems
    val dataItems =
        invalidBadges.map { (username, attributes) ->
          val badgeNumber = attributes["employeeID"] as? String ?: ""
          val member = members[username]

          val avatarUrl = normalizeAvatarUrl(member?.displayAvatarUrl(), size = 144)

          // Build member JSON object
          val memberJson = buildJsonObject {
            put("username", JsonPrimitive(username))
            put("displayName", JsonPrimitive(member?.displayName ?: username))
            put("avatarUrl", JsonPrimitive(avatarUrl))
          }

          DataItem(
              values =
                  mapOf(
                      "Badge Number" to JsonPrimitive(badgeNumber),
                      "Badge Length" to JsonPrimitive(badgeNumber.length),
                      "Member" to memberJson,
                  ))
        }

    val dataFields =
        listOf(
            DataField(name = "Badge Number", type = DataType.STRING, label = "Badge Number"),
            DataField(name = "Badge Length", type = DataType.NUMBER, label = "Badge Length"),
            DataField(name = "Member", type = DataType.MEMBER, label = "Member"),
        )

    val timeTaken = System.currentTimeMillis() - startTime
    val metadata =
        mapOf(
            "Generated at" to java.time.Instant.now().toString(),
            "Time taken" to "${timeTaken}ms",
            "Total invalid badges" to "${dataItems.size}",
            "Source" to "Active Directory (LDAP)",
        )

    return DataVizResponse(data = dataItems, dataFields = dataFields, metadata = metadata)
  }
}
