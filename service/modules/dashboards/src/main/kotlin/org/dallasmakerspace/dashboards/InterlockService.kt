package org.dallasmakerspace.dashboards

import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.activedirectory.ADException
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.LoggerFactory

/**
 * The interlock system zero-pads badges (a 10-digit "0005978828"); AD stores the number as issued,
 * so the badge is tried as given and without its padding. An all-zero badge is the placeholder for
 * "no badge" and matches nobody.
 */
internal fun badgeForms(rfid: String): List<String> {
  val given = rfid.trim()
  if (given.isEmpty() || given.all { it == '0' }) return emptyList()
  return listOf(given, given.trimStart('0')).distinct()
}

/** A tool as the interlock system reports it right now. */
data class InterlockTool(
    val id: String,
    val name: String,
    val inUse: Boolean,
    val underMaintenance: Boolean,
    val operatorName: String?,
)

/** The tools a card lists; [available] is false when the tool system could not be reached. */
data class ToolList(val available: Boolean, val rows: List<ToolRow>)

/**
 * Which tools are in use or under maintenance, from the interlock system. Results are cached for
 * a short time so a handful of TVs and pages share one call. An operator is matched to a portal
 * member by RFID badge number (operator names repeat and have no email), and the lookup is cached.
 */
@Singleton
class InterlockService
@Inject
constructor(
    private val client: InterlockClient,
    private val activeDirectory: IActiveDirectoryService,
    private val members: DashboardMembers,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)
  private val tools = TtlCache<String, List<InterlockTool>>(TOOLS_TTL)
  private val usernames = TtlCache<String, String>(OPERATOR_TTL)
  private val operatorIds = TtlCache<String, String>(OPERATOR_ID_TTL)

  suspend fun list(mode: ToolMode): ToolList {
    if (!client.isConfigured) return ToolList(false, emptyList())
    val all =
        try {
          tools.getOrLoad(TOOLS_KEY) { loadTools() }
        } catch (e: InterlockException) {
          log.warn("Could not load tool status: ${e.message}")
          return ToolList(false, emptyList())
        }
    return when (mode) {
      ToolMode.IN_USE -> ToolList(true, all.filter { it.inUse }.map { inUseRow(it) })
      ToolMode.MAINTENANCE ->
          ToolList(true, all.filter { it.underMaintenance }.map { ToolRow(name = it.name) })
    }
  }

  /** Portal usernames of the people using a tool right now. */
  suspend fun operatorUsernames(): Set<String> =
      list(ToolMode.IN_USE).rows.mapNotNull { it.operator?.username }.toSet()

  private suspend fun inUseRow(tool: InterlockTool): ToolRow {
    val username = tool.operatorName?.let { operatorUsername(tool) }
    val member = username?.let { members.rows(listOf(it)).firstOrNull() }
    return ToolRow(name = tool.name, operator = member, operatorName = tool.operatorName)
  }

  @Suppress("TooGenericExceptionCaught")
  private suspend fun operatorUsername(tool: InterlockTool): String? =
      try {
        val operatorId =
            operatorIds.getOrLoad("${tool.id}|${tool.operatorName}") { latestOperatorId(tool).orEmpty() }
        operatorId.ifEmpty { null }?.let { id -> usernames.getOrLoad(id) { usernameFor(id) }.ifEmpty { null } }
      } catch (e: Exception) {
        log.warn("Could not match the operator of ${tool.name} to a member: ${e.message}")
        null
      }

  /** Who badged in last on [tool]: the newest in-activity whose name is the one the tool reports. */
  private suspend fun latestOperatorId(tool: InterlockTool): String? {
    val activity =
        client
            .query(
                "{ recentActivity(equipmentId: \"${safe(tool.id)}\", count: $ACTIVITY_WINDOW) " +
                    "{ eventDateTime operatorId operatorName activityKind } }")["recentActivity"]
            ?.jsonArray
            .orEmpty()
    return activity
        .map { it.jsonObject }
        .filter { it.string("activityKind") == USER_ACTIVITY && it.string("operatorName") == tool.operatorName }
        .maxByOrNull { it.string("eventDateTime").orEmpty() }
        ?.string("operatorId")
  }

  /** The username for an operator's badge, or "" when the badge matches no member. */
  private suspend fun usernameFor(operatorId: String): String {
    val rfid =
        client
            .query("{ operators(id: \"${safe(operatorId)}\") { rfid } }")["operators"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.string("rfid")
            ?.takeIf { it.isNotBlank() } ?: return ""
    val forms = badgeForms(rfid)
    if (forms.isEmpty()) return ""
    val found =
        withContext(Dispatchers.IO) {
          try {
            @Suppress("UNCHECKED_CAST")
            activeDirectory.getMembersByBadgeNumberList(forms) as? Map<String, ADUser?>
          } catch (e: ADException) {
            null
          }
        }
    return forms.firstNotNullOfOrNull { found?.get(it) }?.takeIf { it.enabled }?.sAMAccountName.orEmpty()
  }

  private companion object {
    val TOOLS_TTL: Duration = Duration.ofSeconds(30)
    val OPERATOR_TTL: Duration = Duration.ofHours(1)
    val OPERATOR_ID_TTL: Duration = Duration.ofMinutes(5)
    const val TOOLS_KEY = "tools"
    const val PAGE = 100
    const val ACTIVITY_WINDOW = 20
    const val USER_ACTIVITY = "USER_ACTIVITY"
    val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
  }

  private suspend fun loadTools(): List<InterlockTool> {
    val data =
        client.query(
            "{ equipment(count: $PAGE) { id name isRelayOn isUnderMaintenance currentOperatorName } }")
    return data["equipment"]?.jsonArray.orEmpty().map { it.jsonObject }.mapNotNull { tool ->
      val id = tool.string("id") ?: return@mapNotNull null
      val operator = tool.string("currentOperatorName")?.takeIf { it.isNotBlank() }
      InterlockTool(
          id = id,
          name = tool.string("name")?.trim().orEmpty().ifEmpty { id },
          inUse = operator != null || tool.bool("isRelayOn"),
          underMaintenance = tool.bool("isUnderMaintenance"),
          operatorName = operator)
    }
  }

  private fun JsonObject.string(key: String): String? =
      this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content

  private fun JsonObject.bool(key: String): Boolean =
      (this[key] as? JsonElement)?.let { runCatching { it.jsonPrimitive.boolean }.getOrNull() } ?: false

  /** Ids go into a query string, so only the characters the system uses are let through. */
  private fun safe(id: String): String {
    if (!SAFE_ID.matches(id)) throw InterlockException("Unexpected id from the tool system")
    return id
  }
}
