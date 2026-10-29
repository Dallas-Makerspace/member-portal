package org.dallasmakerspace.agenttools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.agent.AgentTool
import org.dallasmakerspace.agent.MemberAccess
import org.dallasmakerspace.agent.ToolContext
import org.dallasmakerspace.agent.ToolResult
import org.dallasmakerspace.config.ConfigCurrentValue
import org.dallasmakerspace.config.ConfigKey
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.ConfigValueType
import org.dallasmakerspace.config.FeatureFlagValue

private const val MAX_DETAIL = 15
private const val SEARCH_CHARS = 100
private const val VALUE_CHARS = 120
private const val DESCRIPTION_CHARS = 220
private const val MAX_GROUPS_SHOWN = 10
private const val TOKEN_LIKE_LENGTH = 24
private val USERNAME = Regex("[A-Za-z0-9._@-]{1,100}")
private val SECRET_KEY =
    Regex(
        "secret|passw|token|api[-_.]?key|credential|private[-_.]?key|connection[-_.]?string|\\bdsn\\b|jdbc|salt",
        RegexOption.IGNORE_CASE)
private val CREDENTIAL_URL = Regex("(?i)(\\w+://)[^/\\s:@]+(:[^/\\s@]*)?@")
private val TOKEN_LIKE = Regex("[A-Za-z0-9+/=_-]+")

/** What was learned about the member's directory groups, for group-based flag piloting. */
private sealed interface GroupLookup {
  data class Found(val names: List<String>) : GroupLookup

  data object NotFound : GroupLookup

  data object Failed : GroupLookup
}

/**
 * Read-only view of site config and feature flags, optionally evaluated for one member. Secret,
 * infra-only and structured values are never shown, and piloted usernames are only counted.
 */
@Singleton
class GetSiteConfigTool
@Inject
constructor(
    private val overrides: ConfigOverrideService,
    private val directory: IActiveDirectoryService,
) : AgentTool {
  private val json = Json { ignoreUnknownKeys = true }

  @Suppress("UNCHECKED_CAST")
  private val flagKeys: Map<String, ConfigKey<FeatureFlagValue>> =
      ConfigRegistry.ALL.filter { it.type == ConfigValueType.FeatureFlagType }
          .associate { it.key to it as ConfigKey<FeatureFlagValue> }

  override val descriptor =
      ToolDescriptor(
          "get_site_config",
          "Read the portal's site config and feature flags (read-only). Without search it lists " +
              "all keys by category. With search (part of a key, description or category) it " +
              "shows each match: whether it is overridden or at its default, its value, and for " +
              "feature flags whether it is on, how many members are piloted and which groups. " +
              "Give username to see whether each feature flag is enabled for that member and why " +
              "(use it for \"why can't X see feature Y\"). Secret values are never shown.",
          requiredParameters = emptyList(),
          optionalParameters =
              listOf(
                  ToolParameterDescriptor("search", "Part of a key, description or category, e.g. notifications", ToolParameterType.String),
                  USERNAME_PARAMETER))

  override fun descriptorFor(access: MemberAccess) =
      if (access == MemberAccess.ANY_MEMBER) descriptor else descriptor.copy(optionalParameters = descriptor.optionalParameters.filter { it.name != "username" })

  @Suppress("ReturnCount")
  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val search = args.string("search")?.trim()?.take(SEARCH_CHARS)?.lowercase().orEmpty()
    val requested = args.string("username")?.trim()?.ifEmpty { null }
    val subject =
        if (requested == null) null
        else context.subjectFor(requested) ?: return ToolResult("That member cannot be looked up.", isError = true)
    if (subject != null && !USERNAME.matches(subject)) return ToolResult("Invalid username.", isError = true)

    val (values, lookup) =
        coroutineScope {
          val current = async { overrides.getCurrentValues() }
          val groups = async { subject?.let { lookupGroups(it) } }
          current.await() to groups.await()
        }

    val matches =
        values.filter {
          search.isEmpty() ||
              it.key.lowercase().contains(search) ||
              it.description.lowercase().contains(search) ||
              it.category.lowercase().contains(search)
        }
    if (matches.isEmpty()) {
      return ToolResult("No config key matched \"${AuditTextSanitizer.oneLine(search, SEARCH_CHARS)}\".")
    }
    if (search.isEmpty() && subject == null) return ToolResult(compactList(matches))

    val detailed = if (search.isEmpty()) matches.filter { it.key in flagKeys } else matches
    val shown = detailed.take(MAX_DETAIL)
    val out = StringBuilder()
    if (subject != null) out.append(memberHeader(subject, lookup)).append('\n')
    for (entry in shown) out.append(entryText(entry, subject, lookup, brief = search.isEmpty())).append('\n')
    if (detailed.size > shown.size) {
      out.append("(${detailed.size - shown.size} more matches not shown; narrow the search.)\n")
    }
    return ToolResult(out.toString().trimEnd())
  }

  private fun compactList(entries: List<ConfigCurrentValue>): String =
      "${entries.size} config keys (* = overridden). Search for one to see its value.\n" +
          entries.groupBy { it.category }.entries.joinToString("\n") { (category, keys) ->
            "$category: " + keys.joinToString(", ") { "${it.key} [${typeLabel(it)}]${if (it.isOverridden) "*" else ""}" }
          }

  private fun memberHeader(subject: String, lookup: GroupLookup?): String =
      when (lookup) {
        is GroupLookup.Found -> "Feature flags evaluated for $subject (${lookup.names.size} directory groups)."
        GroupLookup.NotFound -> "Feature flags evaluated for $subject, who was not found in the directory, so group piloting could not be checked."
        GroupLookup.Failed -> "Feature flags evaluated for $subject; the group lookup failed, so group piloting could not be checked."
        null -> ""
      }

  private suspend fun entryText(entry: ConfigCurrentValue, subject: String?, lookup: GroupLookup?, brief: Boolean): String {
    val flag = flagValue(entry)
    val verdict = if (subject != null && entry.key in flagKeys) memberVerdict(entry, flag, subject, lookup) else null
    if (brief) return "- ${entry.key}: ${verdict ?: valueText(entry, flag)}"
    val state = if (entry.isOverridden) "overridden${changedText(entry)}" else "at default"
    return buildString {
      append("- ${entry.key} (${entry.category}, ${typeLabel(entry)}): $state\n")
      append("  ${AuditTextSanitizer.oneLine(entry.description, DESCRIPTION_CHARS)}\n")
      append("  Value: ${valueText(entry, flag)}")
      if (verdict != null) append("\n  For $subject: $verdict")
    }
  }

  private fun changedText(entry: ConfigCurrentValue): String =
      entry.lastChangedAt?.takeIf { it.length >= 16 }?.let { " (last changed ${it.substring(0, 16).replace('T', ' ')} Central)" }.orEmpty()

  private fun isHidden(entry: ConfigCurrentValue) =
      entry.sensitive || entry.infraOnly || SECRET_KEY.containsMatchIn(entry.key) || entry.type.startsWith("Complex")

  private fun flagValue(entry: ConfigCurrentValue): FeatureFlagValue? =
      if (entry.type != "FeatureFlagType" || isHidden(entry)) null
      else runCatching { json.decodeFromString(FeatureFlagValue.serializer(), entry.currentValue) }.getOrNull()

  private fun valueText(entry: ConfigCurrentValue, flag: FeatureFlagValue?): String {
    if (isHidden(entry)) return "hidden"
    if (entry.type == "FeatureFlagType") {
      if (flag == null) return "unreadable"
      val groups = flag.enabledGroupNames.sorted()
      val groupText =
          if (groups.isEmpty()) "none"
          else groups.take(MAX_GROUPS_SHOWN).joinToString(", ") { AuditTextSanitizer.oneLine(it, 60) } +
              if (groups.size > MAX_GROUPS_SHOWN) ", and ${groups.size - MAX_GROUPS_SHOWN} more" else ""
      return "${if (flag.enabled) "ON for everyone" else "off for everyone"}; piloted members: ${flag.enabledUsernames.size}; piloted groups: $groupText"
    }
    if (entry.key.contains("usernames", ignoreCase = true)) {
      return "${entry.currentValue.split(',').count { it.isNotBlank() }} usernames (not listed)"
    }
    return safeText(entry.currentValue)
  }

  private fun safeText(raw: String): String {
    val value = CREDENTIAL_URL.replace(raw, "$1***@")
    val looksSecret =
        value.contains("-----BEGIN") ||
            value.contains("jdbc:", ignoreCase = true) ||
            (value.length >= TOKEN_LIKE_LENGTH && TOKEN_LIKE.matches(value) && value.any { it.isDigit() } && value.any { it.isLetter() } && !value.contains('.'))
    return if (looksSecret) "hidden" else AuditTextSanitizer.oneLine(value, VALUE_CHARS).ifEmpty { "(empty)" }
  }

  private suspend fun memberVerdict(entry: ConfigCurrentValue, flag: FeatureFlagValue?, subject: String, lookup: GroupLookup?): String {
    val groups = (lookup as? GroupLookup.Found)?.names.orEmpty()
    val enabled = overrides.isFeatureEnabledFor(flagKeys.getValue(entry.key), subject, groups)
    if (flag == null) return if (enabled) "ENABLED" else "NOT enabled"
    if (enabled) {
      val why =
          when {
            flag.enabled -> "the flag is on for everyone"
            subject in flag.enabledUsernames -> "the member is on the pilot list"
            else -> "the member is in piloted group " + groups.firstOrNull { it in flag.enabledGroupNames }?.let { AuditTextSanitizer.oneLine(it, 60) }.orEmpty()
          }
      return "ENABLED ($why)"
    }
    val caveat =
        when (lookup) {
          is GroupLookup.Found -> "none of their groups is piloted"
          else -> "group piloting could not be checked"
        }
    return "NOT enabled (the flag is off, the member is not on the pilot list, $caveat)"
  }

  @Suppress("TooGenericExceptionCaught")
  private suspend fun lookupGroups(subject: String): GroupLookup =
      try {
        val user = withContext(Dispatchers.IO) { directory.getMembersByUsernameList(listOf(subject)).let { it[subject] ?: it.values.firstOrNull() } }
        if (user == null) GroupLookup.NotFound else GroupLookup.Found(user.groups.map { it.cn })
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GroupLookup.Failed
      }

  private fun typeLabel(entry: ConfigCurrentValue) =
      when (entry.type) {
        "StringType" -> "text"
        "IntType" -> "number"
        "BooleanType" -> "on/off"
        "DateType" -> "date"
        "FeatureFlagType" -> "feature flag"
        else -> "structured"
      }

  private companion object {
    val USERNAME_PARAMETER =
        ToolParameterDescriptor("username", "Member to evaluate feature flags for, e.g. jdoe", ToolParameterType.String)
  }
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
