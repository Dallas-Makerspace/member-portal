package org.dallasmakerspace.dataviz.reports.interlock

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.dallasmakerspace.core.logging.ElasticsearchClientManager
import org.dallasmakerspace.dataviz.DataVizReport
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.models.Interlocks
import org.dallasmakerspace.models.Tools

class DeniedAccessReport
@Inject
constructor(
    private val events: InterlockEventsService,
    private val memberService: MemberService,
) : DataVizReport() {

  override fun getName(): String = "interlock-denied-access"

  @Suppress(
      "TooGenericExceptionCaught",
      "LongMethod",
      "MagicNumber",
      "ComplexMethod",
      "ComplexCondition",
      "ReturnCount",
  )
  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val tool = params["tool"]?.firstOrNull()
    val committee = params["committee"]?.firstOrNull()

    if ((tool == null && committee == null) || (tool != null && committee != null)) {
      return DataVizResponse(
          data = emptyList(),
          dataFields = emptyList(),
          metadata = mapOf("error" to "Provide either tool or committee"),
      )
    }

    val days = params["days"]?.firstOrNull()?.toIntOrNull() ?: Interlocks.DEFAULT_REPORT_DAYS
    val actorUsername = params["actorUsername"]?.firstOrNull()
    val isInfra =
        actorUsername?.let { withContext(Dispatchers.IO) { memberService.isUserInInfraGroup(it) } }
            ?: false

    val isCommitteeScope: Boolean
    val tags: List<String>
    val scopeMetadata: MutableMap<String, String> = mutableMapOf()

    if (tool != null) {
      tags = listOf(tool)
      isCommitteeScope = false
      scopeMetadata["Tool"] = tool
    } else {
      val c =
          Committees.findBySlug(committee!!)
              ?: return DataVizResponse(
                  data = emptyList(),
                  dataFields = emptyList(),
                  metadata = mapOf("error" to "Unknown committee: $committee"),
              )
      val committeeTools = Tools.byCommittee()[c] ?: emptyList()
      tags = committeeTools.map { it.interlockTag }
      isCommitteeScope = true
      scopeMetadata["Committee"] = c.name
      if (tags.isEmpty()) {
        return DataVizResponse(
            data = emptyList(),
            dataFields = emptyList(),
            metadata =
                mapOf(
                    "Committee" to c.name,
                    "Tools" to "0",
                ),
        )
      }
    }

    val raw = events.fetchEvents(tags, days)

    val denied =
        raw.mapNotNull { ev ->
          val msg = stripLeadingTimestamp(ev.message)
          if (!(msg.startsWith("User:") && msg.contains("'authorized': False")))
              return@mapNotNull null
          val badge =
              Regex("'id': '(\\d+)'").find(msg)?.groupValues?.get(1) ?: return@mapNotNull null
          Triple(ev.timestamp, ev.host, badge)
        }

    val badgeNumbers = denied.map { it.third }.toSet()
    val badgeToUsername = events.fetchBadgeToUsername(badgeNumbers)
    val usernames = badgeToUsername.values.toSet()
    val members =
        if (usernames.isNotEmpty()) {
          try {
            memberService.getMembersByUsernameList(usernames.toList())
          } catch (e: Exception) {
            ElasticsearchClientManager.log.warn(
                "DeniedAccessReport: member lookup failed: ${e.message}")
            emptyMap()
          }
        } else {
          emptyMap()
        }

    val dataItems =
        denied.reversed().map { (timestamp, host, badge) ->
          val username = badgeToUsername[badge]
          val member = username?.let { members[it] }

          val memberJson: JsonElement =
              if (username != null) {
                buildJsonObject {
                  put("username", JsonPrimitive(username))
                  put("displayName", JsonPrimitive(member?.displayName ?: username))
                  put("avatarUrl", JsonPrimitive(events.getAvatarUrl(member?.discourseAvatarUrl)))
                }
              } else {
                JsonPrimitive("Badge: ${events.maskBadge(badge, isInfra)}")
              }

          val values = mutableMapOf<String, JsonElement>("Time" to JsonPrimitive(timestamp))
          if (isCommitteeScope) {
            val toolName = Tools.findBySlug(host)?.name ?: host
            values["Tool"] = JsonPrimitive(toolName)
          }
          values["Member"] = memberJson
          values["Badge"] = JsonPrimitive(events.maskBadge(badge, isInfra))

          DataItem(values = values)
        }

    val dataFields = mutableListOf<DataField>()
    dataFields.add(DataField(name = "Time", type = DataType.RELATIVE_DATE, label = "Time"))
    if (isCommitteeScope) {
      dataFields.add(DataField(name = "Tool", type = DataType.STRING, label = "Tool"))
    }
    dataFields.add(DataField(name = "Member", type = DataType.MEMBER, label = "Member"))
    dataFields.add(DataField(name = "Badge", type = DataType.STRING, label = "Badge"))

    val metadata = buildMap {
      putAll(scopeMetadata)
      put("Days", "$days")
      put("Total denied", "${denied.size}")
      put(
          "Generated at",
          DateTimeFormatter.ISO_DATE_TIME.format(
              Instant.now().atZone(ZoneId.of("America/Chicago"))))
      events.buildKibanaDiscoverUrl(events.kibanaQueryDeniedSwipes(tags), days)?.let {
        put("Kibana", it)
      }
    }

    return DataVizResponse(data = dataItems, dataFields = dataFields, metadata = metadata)
  }
}
