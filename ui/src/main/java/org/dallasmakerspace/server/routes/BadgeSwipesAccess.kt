package org.dallasmakerspace.server.routes

import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import org.dallasmakerspace.models.Areas

internal const val BADGE_SWIPES_PARENT_SLUG = "member-visits"
internal const val BADGE_SWIPES_SLUG = "badge-swipes"
internal const val BADGE_SWIPES_DOORS_PARAM = "doors"
private const val DEFAULT_TITLE = "Recent Badge Swipes"

/** The doors a committee chair may see: those of their committee's areas. */
internal fun chairDoors(groups: List<*>): Set<String> {
  val names = groups.map { it.toString().removePrefix("/") }
  return Areas.ALL.filter { it.chairGroup in names }.flatMap { it.doors }.toSet()
}

/** Officers and Infrastructure read everything; a chair must name only their own area's doors. */
internal fun canReadBadgeSwipes(canManageBadges: Boolean, groups: List<*>, doors: List<String>): Boolean =
    canManageBadges || (doors.isNotEmpty() && chairDoors(groups).containsAll(doors))

internal fun isBadgeSwipesData(path: String): Boolean =
    proxiedSegments(path)?.take(2) == listOf("data-viz", BADGE_SWIPES_SLUG)

/**
 * The query to forward, rebuilt from what [canReadBadgeSwipes] checked. Parameter names here ignore
 * case but the service's don't, so forwarding the original could drop a door filter that was checked.
 */
internal fun badgeSwipesQuery(params: Parameters): String {
  val parts =
      params.getAll(BADGE_SWIPES_DOORS_PARAM).orEmpty().map { "$BADGE_SWIPES_DOORS_PARAM=${it.encodeURLParameter()}" } +
          listOfNotNull(
              params["from"]?.let { "from=${it.encodeURLParameter()}" },
              params["to"]?.let { "to=${it.encodeURLParameter()}" })
  return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
}

/** Template model for the report page: a chair who names no door gets all of theirs. */
internal fun badgeSwipesPageModel(canManageBadges: Boolean, groups: List<*>, params: Parameters): Map<String, Any> {
  val requested = params.getAll(BADGE_SWIPES_DOORS_PARAM).orEmpty()
  val doors = if (canManageBadges || requested.isNotEmpty()) requested else chairDoors(groups).toList()
  val from = params["from"]
  val to = params["to"]
  val query =
      badgeSwipesQuery(
          Parameters.build {
            appendAll(BADGE_SWIPES_DOORS_PARAM, doors)
            from?.let { append("from", it) }
            to?.let { append("to", it) }
          })
  val title =
      listOfNotNull(
              doors.takeIf { it.isNotEmpty() }?.joinToString(", "),
              from?.let { "from ${it.replace('T', ' ')}" },
              to?.let { "to ${it.replace('T', ' ')}" })
          .joinToString(" ")
  return mapOf(
      "allowed" to canReadBadgeSwipes(canManageBadges, groups, doors),
      "dataUrl" to "/backend-api/data-viz/$BADGE_SWIPES_SLUG$query",
      "title" to if (title.isEmpty()) DEFAULT_TITLE else "Badge swipes: $title",
  )
}
