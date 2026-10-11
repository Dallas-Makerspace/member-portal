package org.dallasmakerspace.members

/** The slice of a groups row that the resolution decision needs. */
data class GroupRowRef(val id: Int, val name: String, val dn: String, val objectGuid: String?)

sealed interface GroupSyncDecision {
  /** Use the row as is. */
  data class Use(val id: Int) : GroupSyncDecision

  /** Row found by name without a GUID: store the GUID. */
  data class FillGuid(val id: Int, val guid: String) : GroupSyncDecision

  /** Row found by GUID with a stale name or DN: update them (a null [dn] keeps the stored DN). */
  data class Heal(val id: Int, val name: String, val dn: String?) : GroupSyncDecision

  /** Use the row but write nothing, because a write would collide with another row. */
  data class Conflict(val id: Int, val reason: String) : GroupSyncDecision

  data class Create(val dn: String) : GroupSyncDecision
}

fun placeholderGroupDn(name: String) = "CN=$name,OU=Groups,DC=dms,DC=local"

/** Pure lookup-order logic for [IGroupHistoryRepository.resolveGroup]: GUID first, then name. */
fun decideGroupSync(
    name: String,
    dn: String?,
    guid: String?,
    byGuid: GroupRowRef?,
    byName: GroupRowRef?,
    byDn: GroupRowRef?,
): GroupSyncDecision {
  if (guid != null && byGuid != null) {
    val nameDiffers = !byGuid.name.equals(name, ignoreCase = true)
    val dnDiffers = dn != null && !byGuid.dn.equals(dn, ignoreCase = true)
    return when {
      !nameDiffers && !dnDiffers -> GroupSyncDecision.Use(byGuid.id)
      nameDiffers && byName != null && byName.id != byGuid.id ->
          GroupSyncDecision.Conflict(byGuid.id, "name is held by another row")
      dnDiffers && byDn != null && byDn.id != byGuid.id ->
          GroupSyncDecision.Conflict(byGuid.id, "dn is held by another row")
      else -> GroupSyncDecision.Heal(byGuid.id, name, dn)
    }
  }
  if (byName != null) {
    return when {
      guid == null || byName.objectGuid.equals(guid, ignoreCase = true) ->
          GroupSyncDecision.Use(byName.id)
      byName.objectGuid == null -> GroupSyncDecision.FillGuid(byName.id, guid)
      else -> GroupSyncDecision.Conflict(byName.id, "row has a different GUID")
    }
  }
  val createDn = if (dn == null || byDn != null) placeholderGroupDn(name) else dn
  return GroupSyncDecision.Create(createDn)
}
