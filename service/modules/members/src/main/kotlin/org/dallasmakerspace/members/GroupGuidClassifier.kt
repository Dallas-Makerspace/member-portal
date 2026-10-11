package org.dallasmakerspace.members

import org.dallasmakerspace.activedirectory.AdGuid

/** An AD group as seen by the backfill: raw (unvalidated) GUID, DN and cn. */
data class AdGroupRef(val guid: String?, val dn: String, val cn: String)

enum class AmbiguityReason {
  /** The row matches more than one AD group. */
  MULTIPLE_AD_GROUPS,
  /** More than one row without a GUID matches the same AD group. */
  MULTIPLE_ROWS,
  /** The AD group's GUID is already stored on another row. */
  GUID_IN_USE,
}

data class GroupGuidClassification(
    /** Row id to the GUID that would be stored. */
    val matched: Map<Int, String>,
    val alreadySet: List<Int>,
    val rowsWithoutAdGroup: List<Int>,
    val ambiguous: Map<Int, AmbiguityReason>,
    /** Rows matched to an AD group whose GUID is missing, malformed or duplicated. */
    val unusableGuid: List<Int>,
    val adGroupsWithoutRow: Int,
)

private val DN_COMMA_SPACING = Regex("""\s*,\s*""")

private fun normalizeDn(dn: String) = dn.replace(DN_COMMA_SPACING, ",").trim().lowercase()

/**
 * Matches groups rows to AD groups: rows that already have a GUID by GUID, the rest by DN and then
 * by case-insensitive name. Ambiguities are reported, never resolved.
 */
fun classifyGroupGuids(rows: List<GroupRowRef>, adGroups: List<AdGroupRef>): GroupGuidClassification {
  val guids = adGroups.map { AdGuid.normalize(it.guid) }
  val duplicated = guids.filterNotNull().groupingBy { it }.eachCount().filterValues { it > 1 }.keys
  val usableGuid = guids.map { if (it != null && it !in duplicated) it else null }
  val indexByGuid =
      usableGuid.withIndex().filter { it.value != null }.associate { it.value!! to it.index }
  val indexesByDn = adGroups.indices.groupBy { normalizeDn(adGroups[it].dn) }
  val indexesByName = adGroups.indices.groupBy { adGroups[it].cn.trim().lowercase() }

  val touched = mutableSetOf<Int>()
  val alreadySet = mutableListOf<Int>()
  val without = mutableListOf<Int>()
  val ambiguous = sortedMapOf<Int, AmbiguityReason>()
  val claimsByAd = sortedMapOf<Int, MutableList<Int>>()
  val claimedByStored = mutableSetOf<Int>()

  val ordered = rows.sortedBy { it.id }
  for (row in ordered.filter { it.objectGuid != null }) {
    val stored = AdGuid.normalize(row.objectGuid) ?: row.objectGuid!!.trim().lowercase()
    val index = indexByGuid[stored]
    if (index == null) {
      without.add(row.id)
    } else {
      alreadySet.add(row.id)
      claimedByStored.add(index)
      touched.add(index)
    }
  }
  for (row in ordered.filter { it.objectGuid == null }) {
    val candidates =
        indexesByDn[normalizeDn(row.dn)].orEmpty().ifEmpty {
          indexesByName[row.name.trim().lowercase()].orEmpty()
        }
    touched.addAll(candidates)
    when (candidates.size) {
      0 -> without.add(row.id)
      1 -> claimsByAd.getOrPut(candidates[0]) { mutableListOf() }.add(row.id)
      else -> ambiguous[row.id] = AmbiguityReason.MULTIPLE_AD_GROUPS
    }
  }

  val matched = sortedMapOf<Int, String>()
  val unusable = mutableListOf<Int>()
  for ((index, rowIds) in claimsByAd) {
    when {
      index in claimedByStored -> rowIds.forEach { ambiguous[it] = AmbiguityReason.GUID_IN_USE }
      rowIds.size > 1 -> rowIds.forEach { ambiguous[it] = AmbiguityReason.MULTIPLE_ROWS }
      usableGuid[index] == null -> unusable.add(rowIds[0])
      else -> matched[rowIds[0]] = usableGuid[index]!!
    }
  }

  return GroupGuidClassification(
      matched = matched,
      alreadySet = alreadySet.sorted(),
      rowsWithoutAdGroup = without.sorted(),
      ambiguous = ambiguous,
      unusableGuid = unusable.sorted(),
      adGroupsWithoutRow = adGroups.indices.count { it !in touched },
  )
}
