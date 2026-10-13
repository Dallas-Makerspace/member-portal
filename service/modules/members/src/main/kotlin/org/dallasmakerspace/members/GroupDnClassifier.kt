package org.dallasmakerspace.members

import org.dallasmakerspace.activedirectory.AdGuid

/** A planned DN change; applied only while the row still holds [guid]. */
data class DnUpdate(val guid: String, val dn: String)

data class GroupDnClassification(
    /** Row id to the change. */
    val updates: Map<Int, DnUpdate>,
    val unchanged: List<Int>,
    /** Rows left alone because the target DN is held by another row or wanted by two rows. */
    val conflicts: List<Int>,
)

/**
 * Plans DN updates for rows that have a GUID (stored, or in [assignedGuids] from this run) whose AD
 * group, found by GUID, has a different DN. Conflicts are reported, never resolved.
 */
fun classifyGroupDns(
    rows: List<GroupRowRef>,
    adGroups: List<AdGroupRef>,
    assignedGuids: Map<Int, String>,
): GroupDnClassification {
  val usableGuid = usableGuids(adGroups)
  val indexByGuid =
      usableGuid.withIndex().filter { it.value != null }.associate { it.value!! to it.index }
  val holderByDn = rows.groupBy { normalizeDn(it.dn) }

  val unchanged = mutableListOf<Int>()
  val candidates = sortedMapOf<Int, DnUpdate>()
  for (row in rows.sortedBy { it.id }) {
    val guid =
        assignedGuids[row.id]
            ?: row.objectGuid?.let { AdGuid.normalize(it) ?: it.trim().lowercase() }
            ?: continue
    val ad = indexByGuid[guid]?.let { adGroups[it] } ?: continue
    if (normalizeDn(ad.dn) == normalizeDn(row.dn)) unchanged.add(row.id)
    else candidates[row.id] = DnUpdate(guid, ad.dn)
  }

  val wantedBy = candidates.entries.groupBy({ normalizeDn(it.value.dn) }, { it.key })
  val conflicts = sortedSetOf<Int>()
  val updates = sortedMapOf<Int, DnUpdate>()
  for ((id, update) in candidates) {
    val target = normalizeDn(update.dn)
    val heldByOther = holderByDn[target].orEmpty().any { it.id != id }
    val wantedTwice = wantedBy.getValue(target).size > 1
    if (heldByOther || wantedTwice) conflicts.add(id) else updates[id] = update
  }
  return GroupDnClassification(updates, unchanged.sorted(), conflicts.toList())
}
