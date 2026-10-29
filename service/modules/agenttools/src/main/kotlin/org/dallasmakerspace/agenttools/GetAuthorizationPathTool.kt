package org.dallasmakerspace.agenttools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.agent.AgentTool
import org.dallasmakerspace.agent.ToolContext
import org.dallasmakerspace.agent.ToolResult
import org.dallasmakerspace.calendar.CalendarService
import org.dallasmakerspace.calendar.EventSummary
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.models.agent.AgentSourceLink
import org.dallasmakerspace.moodle.LearnCourse
import org.dallasmakerspace.moodle.MoodleCatalogService

private const val MAX_GROUPS = 3
private const val MAX_EVENTS = 3
private const val MAX_COURSES = 2
private const val MAX_OTHER_GROUPS = 8
private const val MAX_TOPIC_CHARS = 80
private const val EVENT_URL = "https://calendar.dallasmakerspace.org/events/view/"
private val CENTRAL: ZoneId = ZoneId.of("America/Chicago")
private val EVENT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM d, yyyy h:mm a", Locale.US)

/**
 * Answers "how do I get authorized to use X?" from data: the AD group that gates X, the DMS Learn
 * course that grants it, upcoming calendar classes that grant it, and the owning committee.
 * Returns no member data.
 */
@Singleton
// A failed lookup is reported as "unknown" in the text, not thrown.
@Suppress("ReturnCount", "SwallowedException", "TooGenericExceptionCaught")
class GetAuthorizationPathTool
@Inject
constructor(
    private val groups: AuthorizationGroupDirectory,
    private val catalog: MoodleCatalogService,
    private val calendar: CalendarService,
) : AgentTool {
  override val descriptor =
      ToolDescriptor(
          "get_authorization_path",
          "How to get authorized to use a tool or area, from live data: the authorization group, the " +
              "DMS Learn course that grants it, upcoming calendar classes that grant it (with " +
              "links), and the owning committee. Use it for questions like how do I get authorized " +
              "on the laser or the table saw. Anything it does not return is unknown.",
          listOf(
              ToolParameterDescriptor(
                  "topic",
                  "Tool, area or class name, e.g. laser, table saw, kiln",
                  ToolParameterType.String)))

  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val topic = cleanText(args["topic"]?.jsonPrimitive?.contentOrNull, MAX_TOPIC_CHARS)
    if (topic.isEmpty()) return ToolResult("Missing topic.", isError = true)
    val tokens = TopicMatcher.tokens(topic)
    if (tokens.isEmpty()) {
      return ToolResult("The topic \"$topic\" is too generic; name the tool or area, e.g. laser or kiln.")
    }

    val (adNames, courses) = coroutineScope {
      val names = async { groups.groupNames() }
      val learn = async { runCatchingNonCancel { catalog.getCatalog().courses }.orEmpty() }
      names.await() to learn.await()
    }
    if (adNames == null && courses.isEmpty()) {
      return ToolResult("The group list is unavailable right now. Try again later.", isError = true)
    }

    val matches = TopicMatcher.match(tokens, adNames.orEmpty(), courses)
    if (matches.isEmpty()) {
      return ToolResult(
          "No authorization group or DMS Learn course matched \"$topic\". The path to get authorized " +
              "is unknown from this data; try search_source with the tool or area name.")
    }

    val shown = matches.take(MAX_GROUPS)
    val events = coroutineScope { shown.map { async { upcomingFor(it.name) } }.awaitAll() }

    val sources = LinkedHashMap<String, AgentSourceLink>()
    val body = StringBuilder()
    body.append("Today is ${LocalDate.now(CENTRAL)} (Central time). Topic: \"$topic\".\n")
    if (shown.first().strength == Strength.SOME) {
      body.append("No group matched every word; these are weaker matches and may not be what was asked.\n")
    }
    if (adNames == null) body.append("The full group list was unavailable; only groups linked to courses are shown.\n")
    if (courses.isEmpty()) body.append("The DMS Learn course list was unavailable, so courses are unknown.\n")
    shown.forEachIndexed { i, match ->
      body.append("\nGroup: ${cleanText(match.name)}\n")
      appendCommittee(body, match.name, sources)
      appendCourses(body, courses, match.name, sources)
      appendEvents(body, events[i], sources)
    }
    val others = matches.drop(MAX_GROUPS).take(MAX_OTHER_GROUPS)
    if (others.isNotEmpty()) {
      body.append("\nOther matching groups not detailed: ${others.joinToString { cleanText(it.name) }}\n")
    }
    body.append("\nFees, membership level and in-person sign-offs are not covered by this data.")
    return ToolResult(body.toString(), sources.values.toList())
  }

  private fun appendCommittee(body: StringBuilder, group: String, sources: MutableMap<String, AgentSourceLink>) {
    val committees = Committees.forGroup(group)
    if (committees.isEmpty()) {
      body.append("Committee: unknown\n")
      return
    }
    body.append(
        "Committee: " +
            committees.joinToString { c -> c.name + (c.homepageUrl?.let { " ($it)" } ?: "") } +
            "\n")
    committees.forEach { c ->
      c.homepageUrl?.let { sources.putIfAbsent(it, AgentSourceLink("${c.name} committee", it, "Source")) }
    }
  }

  private fun appendCourses(
      body: StringBuilder,
      courses: List<LearnCourse>,
      group: String,
      sources: MutableMap<String, AgentSourceLink>,
  ) {
    val granting = courses.filter { it.adGroup.equals(group, ignoreCase = true) }
    if (granting.isEmpty()) {
      body.append("DMS Learn course: none found that grants this group\n")
      return
    }
    granting.take(MAX_COURSES).forEach { c ->
      body.append("DMS Learn course: ${cleanText(c.name)} - ${c.url} (completing it adds the member to this group)\n")
      sources.putIfAbsent(c.url, AgentSourceLink(cleanText(c.name), c.url, "DMS Learn"))
    }
  }

  private fun appendEvents(body: StringBuilder, info: UpcomingInfo, sources: MutableMap<String, AgentSourceLink>) {
    when {
      info.failed -> body.append("Upcoming classes: the calendar lookup failed, so unknown\n")
      info.events.isNotEmpty() -> {
        body.append("Upcoming classes that grant this group:\n")
        info.events.take(MAX_EVENTS).forEach { e ->
          val url = EVENT_URL + e.id
          body.append("- ${cleanText(e.name)}, ${formatEventStart(e)}, $url\n")
          sources.putIfAbsent(url, AgentSourceLink(cleanText(e.name), url, "Calendar"))
        }
      }
      info.hadRecent ->
          body.append("Upcoming classes: none scheduled right now (classes for this group ran recently)\n")
      else -> body.append("Upcoming classes: none on the calendar for this group\n")
    }
  }

  private suspend fun upcomingFor(group: String): UpcomingInfo =
      try {
        val raw = calendar.getUpcomingPrerequisiteEvents(listOf(group))
        val approved = raw.filter { it.status.equals("approved", ignoreCase = true) }
        UpcomingInfo(
            approved,
            hadRecent = if (raw.isNotEmpty()) true else calendar.hasPrerequisiteEvents(group))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        UpcomingInfo(failed = true)
      }

  private class UpcomingInfo(
      val events: List<EventSummary> = emptyList(),
      val hadRecent: Boolean = false,
      val failed: Boolean = false,
  )

  private inline fun <T> runCatchingNonCancel(block: () -> T): T? =
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      }

  internal companion object {
    /** Event times: UTC millis when present, else the repository's string, already Central. */
    fun formatEventStart(e: EventSummary): String {
      val zoned =
          e.eventStartUtcMs?.let { Instant.ofEpochMilli(it).atZone(CENTRAL) }
              ?: runCatching { LocalDateTime.parse(e.eventStart.trim().replace(' ', 'T')).atZone(CENTRAL) }
                  .getOrNull()
      return zoned?.let { EVENT_TIME.format(it) + " CT" } ?: "time unknown"
    }
  }
}

internal enum class Strength {
  SOME,
  ALL,
  EXACT,
}

internal data class GroupMatch(val name: String, val strength: Strength, val extraWords: Int, val hasCourse: Boolean)

/** Matches a topic to AD group names (and to course names, which point at their group). */
internal object TopicMatcher {
  private val WORD = Regex("[a-z0-9]+")
  private val STOP_WORDS =
      setOf(
          "the", "a", "an", "to", "use", "using", "for", "of", "and", "in", "on", "authorized",
          "authorization", "authorize", "class", "training", "certification", "access")
  private val ROLE_WORDS = setOf("teacher", "chair", "committee", "editor", "admin")

  private fun words(text: String): List<String> =
      WORD.findAll(text.lowercase()).map { stem(it.value) }.toList()

  private fun stem(w: String) =
      if (w.length > MIN_STEM && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w

  fun tokens(topic: String): List<String> = words(topic).filter { it !in STOP_WORDS }.distinct()

  private fun wordMatches(token: String, word: String) =
      word == token || (token.length >= MIN_PREFIX && word.startsWith(token))

  /** Strength of [text] against [tokens], or null when no token appears. */
  private fun strengthOf(tokens: List<String>, text: String): Pair<Strength, Int>? {
    val ws = words(text)
    val hits = tokens.count { t -> ws.any { wordMatches(t, it) } }
    if (hits == 0) return null
    val extra = (ws.size - tokens.size).coerceAtLeast(0)
    val strength =
        when {
          hits < tokens.size -> Strength.SOME
          ws.size == tokens.size -> Strength.EXACT
          else -> Strength.ALL
        }
    return strength to extra
  }

  /**
   * Groups matching [tokens], best first. Weaker (some words) matches are returned only when no
   * group matches every word. Role groups (teachers, chairs) are ignored unless a course grants them.
   */
  fun match(tokens: List<String>, adNames: List<String>, courses: List<LearnCourse>): List<GroupMatch> {
    val canonical = LinkedHashMap<String, String>()
    adNames.forEach { canonical.putIfAbsent(it.lowercase(), it) }
    val byGroup = courses.filter { !it.adGroup.isNullOrBlank() }.groupBy { it.adGroup!!.lowercase() }
    byGroup.values.forEach { cs -> canonical.putIfAbsent(cs.first().adGroup!!.lowercase(), cs.first().adGroup!!) }

    val found = HashMap<String, GroupMatch>()
    fun offer(key: String, strength: Strength, extra: Int) {
      val prior = found[key]
      if (prior == null || strength > prior.strength) {
        found[key] = GroupMatch(canonical.getValue(key), strength, extra, key in byGroup)
      }
    }
    canonical.forEach { (key, name) ->
      if (words(name).any { it in ROLE_WORDS } && key !in byGroup) return@forEach
      strengthOf(tokens, name)?.let { (s, extra) -> offer(key, s, extra) }
    }
    byGroup.forEach { (key, cs) ->
      val best = cs.mapNotNull { strengthOf(tokens, it.name + " " + it.shortname) }.maxByOrNull { it.first }
      if (best != null) {
        offer(key, best.first, (words(canonical.getValue(key)).size - tokens.size).coerceAtLeast(0))
      }
    }

    val ordered =
        found.values.sortedWith(
            compareByDescending<GroupMatch> { it.strength }
                .thenByDescending { it.hasCourse }
                .thenBy { it.extraWords }
                .thenBy { it.name.lowercase() })
    val strong = ordered.filter { it.strength >= Strength.ALL }
    return strong.ifEmpty { ordered }
  }

  private const val MIN_PREFIX = 4
  private const val MIN_STEM = 3
}
