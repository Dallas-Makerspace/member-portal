package org.dallasmakerspace.workflows.groups.references

import kotlinx.serialization.Serializable

/** Where the portal still depends on a group: counts and locations only, never member data. */
@Serializable
data class GroupReference(val system: String, val kind: String, val count: Int, val detail: String)

/** What [GroupReferenceChecker.check] found. Any entry in [blocking] stops an archive. */
@Serializable
data class GroupReferences(val blocking: List<GroupReference>, val notes: List<String>) {
  val isBlocked: Boolean
    get() = blocking.isNotEmpty()
}

object GroupReferenceKinds {
  const val PROTECTED = "PROTECTED"
  const val PREREQUISITE = "PREREQUISITE"
  const val COURSE = "COURSE"
  const val FEATURE_FLAG = "FEATURE_FLAG"
  const val CONFIG_VALUE = "CONFIG_VALUE"
  const val REMOTE_ACCESS = "REMOTE_ACCESS"

  /** Directory objects (groups, users, computers) name this group as their manager. */
  const val MANAGED_OBJECTS = "MANAGED_OBJECTS"

  /** A finder failed, so its system could not be checked. */
  const val UNKNOWN = "UNKNOWN"
}

/**
 * Read-only lookup of one system's references to a group. Throw on any failure instead of
 * returning an empty list: the checker turns a throw into a blocking UNKNOWN reference.
 */
interface GroupReferenceFinder {
  val system: String

  /** What this finder cannot detect, shown next to the results. */
  val notes: List<String>
    get() = emptyList()

  suspend fun find(groupName: String): List<GroupReference>
}

internal fun normalizeGroupName(name: String?): String = name.orEmpty().trim().lowercase()

internal fun sameGroup(a: String?, b: String?): Boolean =
    !a.isNullOrBlank() && normalizeGroupName(a) == normalizeGroupName(b)

/** Up to [limit] items joined, then "and N more". */
internal fun summarize(items: List<String>, limit: Int = 5): String {
  val shown = items.take(limit).joinToString(", ")
  return if (items.size > limit) "$shown and ${items.size - limit} more" else shown
}
