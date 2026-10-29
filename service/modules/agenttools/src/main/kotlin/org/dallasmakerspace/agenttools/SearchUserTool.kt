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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.activedirectory.ADUserMatch
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.agent.AgentTool
import org.dallasmakerspace.agent.MemberAccess
import org.dallasmakerspace.agent.ToolContext
import org.dallasmakerspace.agent.ToolResult
import org.dallasmakerspace.members.IMemberRepository
import org.dallasmakerspace.models.DMSMember
import org.dallasmakerspace.models.agent.AgentSourceLink

private const val MAX_MATCHES = 10
private const val FETCH = 15
private const val MIN_QUERY = 2
private const val MAX_QUERY = 64
private const val RESULT_CHARS = 3_000

/**
 * Finds members by name, username or Talk username for staff assistants. Names come from one
 * bounded directory search and usernames from one profile query, run together; the few missing
 * halves are then filled in with one batched read each, also together.
 */
@Singleton
class SearchUserTool
@Inject
constructor(
    private val directory: IActiveDirectoryService,
    private val members: IMemberRepository,
) : AgentTool {
  override val descriptor =
      ToolDescriptor(
          "search_user",
          "Find members by name, username or Talk (forum) username. Returns at most $MAX_MATCHES " +
              "matches with username, display name, Talk username and whether the account is " +
              "active. Use it to turn a name into a username before looking up their access.",
          requiredParameters =
              listOf(
                  ToolParameterDescriptor(
                      "query",
                      "Part of a name, username or Talk username, at least $MIN_QUERY characters",
                      ToolParameterType.String)),
          optionalParameters = emptyList())

  override val requiredMemberAccess = MemberAccess.ANY_MEMBER

  override val touchesPersonalInfo = true

  override val maxResultChars = RESULT_CHARS

  private class Hit(val username: String) {
    var displayName: String? = null
    var enabled: Boolean? = null
    var talk: String? = null
    var inDirectory = false
    var inProfile = false
  }

  @Suppress("TooGenericExceptionCaught")
  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    if (context.memberAccess != MemberAccess.ANY_MEMBER) {
      return ToolResult(UserToolSupport.SELF_ONLY_REFUSAL, isError = true)
    }
    val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
    if (query.length < MIN_QUERY || query.length > MAX_QUERY) {
      return ToolResult("Give $MIN_QUERY to $MAX_QUERY characters of a name or username.", isError = true)
    }
    if (!ALLOWED.matches(query)) {
      return ToolResult(
          "Search text may only contain letters, digits, spaces and . _ - ' characters.", isError = true)
    }
    val hits =
        try {
          search(query)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          return ToolResult("Member search is unavailable right now.", isError = true)
        }
    if (hits.shown.isEmpty()) return ToolResult("No members matched \"$query\".")
    val lines =
        hits.shown.map { h ->
          val name = UserToolSupport.clean(h.displayName) ?: "(name unknown)"
          val state =
              when {
                !h.inDirectory -> "not found in the directory"
                h.enabled == true -> "active"
                else -> "not active"
              }
          "- ${h.username} | $name | Talk: ${UserToolSupport.clean(h.talk) ?: "none"} | $state"
        }
    val header =
        if (hits.total > hits.shown.size) {
          "Showing ${hits.shown.size} of at least ${hits.total} matches for \"$query\"; narrow the search.\n"
        } else {
          "${hits.shown.size} match${if (hits.shown.size == 1) "" else "es"} for \"$query\":\n"
        }
    val sources =
        hits.shown.map {
          AgentSourceLink(UserToolSupport.clean(it.displayName) ?: it.username, "/profile/@${it.username}", "Member portal")
        }
    return ToolResult(header + lines.joinToString("\n"), sources)
  }

  private class Found(val shown: List<Hit>, val total: Int)

  private suspend fun search(query: String): Found = coroutineScope {
    val adSearch = async(Dispatchers.IO) { runCatching { directory.searchUsers(query, FETCH) } }
    val dbSearch = async { runCatching { members.searchProfiles(query, FETCH) } }
    val ad = adSearch.await()
    val db = dbSearch.await()
    if (ad.isFailure && db.isFailure) throw ad.exceptionOrNull()!!

    val byName = linkedMapOf<String, Hit>()
    ad.getOrDefault(emptyList()).forEach { fillFromDirectory(byName.getOrPut(key(it.sAMAccountName)) { Hit(it.sAMAccountName) }, it) }
    db.getOrDefault(emptyList()).forEach { fillFromProfile(byName.getOrPut(key(it.username)) { Hit(it.username) }, it) }

    val ranked = byName.values.sortedWith(compareBy<Hit>({ rank(it, query) }, { it.username.lowercase() }))
    val shown = ranked.take(MAX_MATCHES)

    // Fill the half each hit is missing: profile data for directory hits and vice versa.
    val needProfile = shown.filter { !it.inProfile }.map { it.username }
    val needDirectory = shown.filter { !it.inDirectory }.map { it.username }
    val profiles =
        async { if (needProfile.isEmpty() || db.isFailure) emptyMap() else runCatching { members.getMembersByUsernames(needProfile) }.getOrDefault(emptyMap()) }
    val entries =
        async(Dispatchers.IO) {
          if (needDirectory.isEmpty()) emptyMap() else runCatching { directory.getMembersByUsernameList(needDirectory) }.getOrDefault(emptyMap())
        }
    val profileByName = profiles.await().mapKeys { key(it.key) }
    val entryByName = entries.await().mapKeys { key(it.key) }
    shown.forEach { h ->
      if (!h.inProfile) profileByName[key(h.username)]?.let { fillFromProfile(h, it) }
      if (!h.inDirectory) entryByName[key(h.username)]?.let { u ->
        fillFromDirectory(h, ADUserMatch(u.sAMAccountName, u.displayName, u.enabled))
      }
    }
    Found(shown, byName.size)
  }

  private fun key(username: String) = username.lowercase()

  private fun fillFromDirectory(hit: Hit, match: ADUserMatch) {
    hit.displayName = UserToolSupport.clean(match.displayName) ?: hit.displayName
    hit.enabled = match.enabled
    hit.inDirectory = true
  }

  private fun fillFromProfile(hit: Hit, profile: DMSMember) {
    hit.talk = profile.discourseUsername ?: hit.talk
    hit.inProfile = true
    if (hit.enabled == null) hit.enabled = profile.enabled
  }

  /** Exact username or Talk name first, then name or username prefixes, then the rest. */
  private fun rank(hit: Hit, query: String): Int {
    val q = query.lowercase()
    val name = hit.displayName.orEmpty().lowercase()
    return when {
      hit.username.lowercase() == q || hit.talk.orEmpty().lowercase() == q -> 0
      name == q -> 1
      hit.username.lowercase().startsWith(q) || name.startsWith(q) || hit.talk.orEmpty().lowercase().startsWith(q) -> 2
      else -> 3
    }
  }

  private companion object {
    val ALLOWED = Regex("^[\\p{L}\\p{N} ._'-]+$")
  }
}
