package org.dallasmakerspace.activedirectory

import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.RDN
import com.unboundid.ldap.sdk.SearchResultEntry
import org.dallasmakerspace.models.DirectoryOus

/** groupType flags for a global security group (ADS_GROUP_TYPE_GLOBAL_GROUP or SECURITY_ENABLED). */
internal const val GLOBAL_SECURITY_GROUP_TYPE = -2147483646

/** A global security group to create. [parentOuDn] must be the main group OU or an OU inside it. */
data class AdGroupSpec(
    val name: String,
    val parentOuDn: String,
    val description: String? = null,
    val managedByDn: String? = null,
)

/** [created] is false when the group already existed at the same DN. */
data class AdGroupCreateResult(
    val distinguishedName: String,
    val objectGuid: String?,
    val created: Boolean,
)

/** The group exists outside the main group OU, so the portal must not delete it. */
class GroupDeletionRefusedException(val groupName: String, val distinguishedName: String) :
    ADException("Refusing to delete group $groupName: it is not in the main group OU")

/** The group cannot be created as specified (bad parent OU, or the name is taken elsewhere). */
class GroupCreateRefusedException(val groupName: String, reason: String) :
    ADException("Cannot create group $groupName: $reason")

/** The one entry to delete: the earliest-OU match, which must be inside the main group OU. */
internal fun selectGroupToDelete(
    groupName: String,
    entries: List<SearchResultEntry>,
): SearchResultEntry? {
  val entry = preferredByGroupOu(entries) ?: return null
  if (!DirectoryOus.isInMainGroupOu(entry.dn)) {
    throw GroupDeletionRefusedException(groupName, entry.dn)
  }
  return entry
}

internal fun buildGroupDn(name: String, parentOuDn: String): String =
    DN(RDN("CN", name), DN(parentOuDn)).toString()

internal sealed interface GroupCreatePlan {
  data class Exists(val entry: SearchResultEntry) : GroupCreatePlan

  data class Create(val dn: String) : GroupCreatePlan
}

/** Decides create vs already-there from the entries named like the spec; throws on a conflict. */
internal fun planGroupCreate(
    spec: AdGroupSpec,
    sameNameEntries: List<SearchResultEntry>,
): GroupCreatePlan {
  if (spec.name.isBlank()) throw GroupCreateRefusedException(spec.name, "the name is blank")
  if (!DirectoryOus.isMainGroupOuOrChild(spec.parentOuDn)) {
    throw GroupCreateRefusedException(spec.name, "the parent is not in the main group OU")
  }
  val dn = buildGroupDn(spec.name, spec.parentOuDn)
  val wanted = DN(dn)
  sameNameEntries.firstOrNull { DN(it.dn) == wanted }?.let {
    return GroupCreatePlan.Exists(it)
  }
  if (sameNameEntries.isNotEmpty()) {
    throw GroupCreateRefusedException(spec.name, "a group with that name exists at another DN")
  }
  return GroupCreatePlan.Create(dn)
}
