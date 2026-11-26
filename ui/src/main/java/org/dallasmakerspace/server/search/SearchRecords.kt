package org.dallasmakerspace.server.search

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import org.dallasmakerspace.models.Committee
import org.dallasmakerspace.models.DMSGroup
import org.dallasmakerspace.server.models.EventSummary
import org.dallasmakerspace.server.models.getSlugFromName

/** Search records the dialog reads; see static/js/search/search-model.js for the shape. */
object SearchRecords {
  const val CALENDAR_EVENT_URL = "https://calendar.dallasmakerspace.org/events/view/"
  private const val MAX_RELATED_GROUPS = 100
  private const val MAX_TERM_LENGTH = 40
  private val EVENT_INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  private val EVENT_OUTPUT = DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a")

  /** One record per committee; chairs come from [chairGroupsBySlug], keyed by group slug. */
  fun committees(
      committees: List<Committee>,
      chairGroupsBySlug: Map<String, DMSGroup>,
  ): List<Map<String, Any?>> =
      committees.map { c ->
        val chairs =
            c.chairGroupName
                ?.let { chairGroupsBySlug[getSlugFromName(it)] }
                ?.members
                ?.mapNotNull { (it.displayName ?: it.username).ifBlank { null } }
                .orEmpty()
        mapOf(
            "type" to "committee",
            "displayName" to c.name,
            "username" to c.slug,
            "url" to "/committees/${c.slug}",
            "description" to c.description.orEmpty(),
            "chairs" to chairs,
            "keywords" to (c.groupPrefixes + c.teacherGroups + listOfNotNull(c.chairGroupName)).distinct(),
        )
      }

  fun event(e: EventSummary): Map<String, Any?> =
      mapOf(
          "type" to "event",
          "id" to e.id.toString(),
          "title" to e.name,
          "subtitle" to formatStart(e.eventStart),
          "url" to "$CALENDAR_EVENT_URL${e.id}",
          "external" to true,
          "badges" to if (e.fulfillsPrerequisiteGroup.isNullOrBlank()) emptyList() else listOf("Training"),
      )

  /** The event start is already Central time (converted in SQL), so it is only reformatted. */
  fun formatStart(eventStart: String): String =
      try {
        LocalDateTime.parse(eventStart.substringBefore("."), EVENT_INPUT).format(EVENT_OUTPUT)
      } catch (e: DateTimeParseException) {
        eventStart
      }

  /**
   * Active, non-dynamic short links as "link" records. They open through /go/ (so clicks are
   * counted) but show the description and destination site, not the short path.
   */
  fun links(links: List<Map<String, Any?>>, namespaces: List<Map<String, Any?>>): List<Map<String, Any?>> {
    val aliasByNamespace =
        namespaces
            .filter { it["isActive"] != false }
            .mapNotNull { ns ->
              val id = (ns["id"] as? Number)?.toInt() ?: return@mapNotNull null
              val aliases = (ns["aliases"] as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
              val primary = aliases.firstOrNull { it["isPrimary"] == true } ?: aliases.firstOrNull()
              (primary?.get("alias") as? String)?.let { id to it }
            }
            .toMap()
    return links.mapNotNull { link ->
      if (link["isActive"] == false || link["redirectType"] == "DYNAMIC") return@mapNotNull null
      val id = (link["id"] as? Number)?.toInt() ?: return@mapNotNull null
      val slug = (link["slug"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
      val destination = link["destinationUrl"] as? String ?: return@mapNotNull null
      val namespaceId = (link["namespaceId"] as? Number)?.toInt()
      val path =
          if (namespaceId == null) slug
          else aliasByNamespace[namespaceId]?.let { "$it/$slug" } ?: return@mapNotNull null
      val site = siteOf(destination)
      val description = (link["description"] as? String)?.trim().orEmpty()
      mapOf(
          "type" to "link",
          "id" to id.toString(),
          "displayName" to description.ifEmpty { humanize(slug) },
          "url" to "/go/$path",
          "site" to site,
          "keywords" to listOfNotNull(slug, path.substringBefore('/', "").ifEmpty { null }, site),
      )
    }
  }

  internal fun siteOf(url: String): String =
      runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.").orEmpty()

  private fun humanize(slug: String): String =
      slug.replace(Regex("[-_]+"), " ").trim().replaceFirstChar { it.uppercase() }

  /** "a, b, c" per line → [[a, b, c], ...]: lowercased, deduplicated, groups of one dropped. */
  fun relatedTerms(raw: String?): List<List<String>> =
      raw.orEmpty()
          .lineSequence()
          .map { line ->
            line.split(',').map { it.trim().lowercase().take(MAX_TERM_LENGTH) }.filter { it.isNotEmpty() }.distinct()
          }
          .filter { it.size > 1 }
          .take(MAX_RELATED_GROUPS)
          .toList()
}
