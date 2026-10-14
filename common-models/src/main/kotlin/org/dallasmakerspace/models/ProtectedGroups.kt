package org.dallasmakerspace.models

/** Why the code depends on a group by name; shown in the archive dialog. */
data class ProtectedGroup(val name: String, val reason: String)

/**
 * Groups the code depends on by exact name. Archiving or renaming one silently breaks a role, a
 * chair or an election. Lives here because both the service and the UI map groups by name; a UI
 * test keeps the UI role mapping inside this list.
 */
object ProtectedGroups {
  const val REASON_ROLE = "Maps to a portal role or permission"
  const val REASON_CHAIR = "Committee chair group"
  const val REASON_TEACHER = "Committee teacher group"
  const val REASON_EDITORS = "Committee online course editors group (Moodle chair sync)"
  const val REASON_VOTING = "Voting registration group"
  const val REASON_DIRECTORY = "Directory administration group"

  const val EDITORS_GROUP_SUFFIX = "Online Course Editors"

  /** Group names the UI maps to roles (Role.fromKeycloakGroups) or access checks. */
  val ROLE_GROUPS =
      listOf(
          "Infrastructure",
          "Board",
          "President",
          "Treasurer",
          "Secretary",
          "Logistics Committee Chair",
          "Infrastructure Committee Chair",
      )

  val VOTING_GROUPS = listOf("Voting Members", "Voting Members - Test")

  val DIRECTORY_GROUPS = listOf("Domain Admins")

  val ALL: List<ProtectedGroup> by lazy {
    buildList {
      ROLE_GROUPS.forEach { add(ProtectedGroup(it, REASON_ROLE)) }
      VOTING_GROUPS.forEach { add(ProtectedGroup(it, REASON_VOTING)) }
      DIRECTORY_GROUPS.forEach { add(ProtectedGroup(it, REASON_DIRECTORY)) }
      Committees.ALL.forEach { committee ->
        committee.chairGroupName?.let { add(ProtectedGroup(it, REASON_CHAIR)) }
        committee.teacherGroups.forEach { add(ProtectedGroup(it, REASON_TEACHER)) }
        (committee.groupPrefixes + committee.name).distinct().forEach {
          add(ProtectedGroup("$it $EDITORS_GROUP_SUFFIX", REASON_EDITORS))
        }
      }
    }
  }

  private val byKey: Map<String, ProtectedGroup> by lazy {
    ALL.groupBy { key(it.name) }.mapValues { (_, entries) -> entries.first() }
  }

  /** The first reason the group is protected (case and surrounding spaces ignored), or null. */
  fun find(groupName: String?): ProtectedGroup? = groupName?.let { byKey[key(it)] }

  fun isProtected(groupName: String?): Boolean = find(groupName) != null

  private fun key(name: String) = name.trim().lowercase()
}
