package org.dallasmakerspace.workflows.groups.references

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.calendar.CalendarService

/**
 * Calendar prerequisites name an AD group in `ad_group`. Events fulfill them (completing the event
 * adds the member to the group) or require them (registering needs membership). Any prerequisite
 * row blocks, even with no upcoming events: it stays selectable for new events and would point at
 * a group that no longer exists. Upcoming and past event counts are reported in the detail.
 */
@Singleton
class CalendarReferenceFinder @Inject constructor(private val calendar: CalendarService) :
    GroupReferenceFinder {
  override val system = "Calendar"

  override suspend fun find(groupName: String): List<GroupReference> {
    val refs = calendar.countPrerequisiteReferences(groupName)
    if (refs.prerequisites == 0) return emptyList()
    val events = refs.upcomingEvents + refs.pastEvents
    val detail =
        "${plural(refs.prerequisites, "prerequisite")} name this group; " +
            "${refs.upcomingEvents} upcoming and ${refs.pastEvents} past " +
            "${if (events == 1) "event uses" else "events use"} them"
    return listOf(
        GroupReference(system, GroupReferenceKinds.PREREQUISITE, refs.prerequisites, detail))
  }

  private fun plural(n: Int, word: String) = if (n == 1) "$n $word" else "$n ${word}s"
}
