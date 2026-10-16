package org.dallasmakerspace.activedirectory

import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.RDN
import com.unboundid.ldap.sdk.ResultCode
import com.unboundid.ldap.sdk.SearchResultEntry
import org.dallasmakerspace.models.DirectoryOus
import org.dallasmakerspace.models.ProtectedGroups

/** groupType flags for a global security group (ADS_GROUP_TYPE_GLOBAL_GROUP or SECURITY_ENABLED). */
internal const val GLOBAL_SECURITY_GROUP_TYPE = -2147483646

/**
 * A group to create. [parentOuDn] must be the main group OU or an OU inside it. [samAccountName]
 * and [groupType] default to the name and a global security group.
 */
data class AdGroupSpec(
    val name: String,
    val parentOuDn: String,
    val description: String? = null,
    val managedByDn: String? = null,
    val samAccountName: String? = null,
    val groupType: Int? = null,
)

/** Characters AD forbids in a sAMAccountName (control characters are rejected separately). */
private const val INVALID_SAM_CHARS = "\"/\\[]:;|=,+*?<>@"
private const val MAX_SAM_LENGTH = 256
private const val SPACE_CODE = 0x20

/** The spec's sAMAccountName if it is usable, else the group name. */
internal fun samAccountNameFor(spec: AdGroupSpec): String =
    spec.samAccountName?.trim()?.takeIf { sam ->
      sam.isNotEmpty() &&
          sam.length <= MAX_SAM_LENGTH &&
          sam.none { it.code < SPACE_CODE || it in INVALID_SAM_CHARS }
    } ?: spec.name

/** The spec's groupType if it is a security group (bit 31 set), else global security. */
internal fun groupTypeFor(spec: AdGroupSpec): Int =
    spec.groupType?.takeIf { it and SECURITY_ENABLED_FLAG != 0 } ?: GLOBAL_SECURITY_GROUP_TYPE

private const val SECURITY_ENABLED_FLAG = Int.MIN_VALUE

/** [created] is false when the group already existed at the same DN. */
data class AdGroupCreateResult(
    val distinguishedName: String,
    val objectGuid: String?,
    val created: Boolean,
)

/** The portal must not delete this group (outside the main group OU, protected, wrong name). */
class GroupDeletionRefusedException(
    val groupName: String,
    val distinguishedName: String,
    val reason: String = "it is not in the main group OU",
) : ADException("Refusing to delete group $groupName: $reason")

/** The group cannot be created as specified (bad parent OU, or the name is taken elsewhere). */
class GroupCreateRefusedException(val groupName: String, reason: String) :
    ADException("Cannot create group $groupName: $reason")

/**
 * The one entry to delete: the entry with the expected objectGUID ([byGuid]), provided it carries
 * the expected name, is inside the main group OU, is not a protected group, and no other group of
 * that name exists in the group OUs ([byName], which includes the entry itself).
 */
internal fun selectGroupToDelete(
    groupName: String,
    expectedGuid: String,
    byGuid: List<SearchResultEntry>,
    byName: List<SearchResultEntry>,
): SearchResultEntry {
  val entry = byGuid.singleOrNull() ?: throw GroupGuidNotFoundException(groupName)
  val actualGuid = entry.getAttributeValueBytes("objectGUID")?.let { AdGuid.fromBytes(it) }
  if (actualGuid != null && actualGuid != AdGuid.normalize(expectedGuid)) {
    throw GroupGuidNotFoundException(groupName)
  }
  fun refuse(reason: String): Nothing =
      throw GroupDeletionRefusedException(groupName, entry.dn, reason)
  val cn = entry.getAttributeValue("cn")
  if (cn != null && !cn.equals(groupName.trim(), ignoreCase = true)) {
    refuse("its name does not match")
  }
  if (!DirectoryOus.isInMainGroupOu(entry.dn)) refuse("it is not in the main group OU")
  val protected = listOfNotNull(groupName.trim(), cn).any { ProtectedGroups.find(it) != null }
  if (protected) refuse("it is a protected group")
  val sameName = byName.filter { DirectoryOus.isInGroupOu(it.dn) }
  if (sameName.size > 1) throw AmbiguousGroupException(groupName, sameName.size)
  return entry
}

/**
 * The one group of this name in the group OUs, else (when none is in a group OU) the earliest
 * match elsewhere, else null. Refuses with [AmbiguousGroupException] when two or more group-OU
 * entries share the name, e.g. one in a sub-OU of the main OU and one in the main OU.
 */
internal fun selectUniqueGroup(
    groupName: String,
    entries: List<SearchResultEntry>,
): SearchResultEntry? {
  val inOus = entries.filter { DirectoryOus.isInGroupOu(it.dn) }
  if (inOus.size > 1) throw AmbiguousGroupException(groupName, inOus.size)
  return inOus.singleOrNull() ?: preferredByGroupOu(entries)
}

/** A group found by objectGUID: just enough to tell whether it is still the one we meant. */
data class GroupIdentity(val name: String, val distinguishedName: String, val objectGuid: String)

private const val LOG_NAME_MAX = 200

/** Control characters become '_' and long text is cut, so a name cannot forge or flood a log. */
internal fun logSafe(value: String?): String {
  val text = value.orEmpty()
  val cleaned = text.take(LOG_NAME_MAX).map { if (it.isISOControl()) '_' else it }.joinToString("")
  return if (text.length > LOG_NAME_MAX) "$cleaned..." else cleaned
}

/** The result codes LDAP and AD answer when the member is already in the group. */
internal fun isAlreadyMember(code: ResultCode): Boolean =
    code == ResultCode.ATTRIBUTE_OR_VALUE_EXISTS || code == ResultCode.ENTRY_ALREADY_EXISTS

/** Guards a parent-membership write: both groups must be inside the main group OU. */
internal fun requireMainOuMembership(groupName: String, childDn: String, parentDn: String) {
  if (!DirectoryOus.isInMainGroupOu(childDn)) {
    throw GroupMembershipRefusedException(groupName, "it is not in the main group OU")
  }
  if (!DirectoryOus.isInMainGroupOu(parentDn)) {
    throw GroupMembershipRefusedException(groupName, "the parent group is not in the main group OU")
  }
}

/** Objects whose managedBy is a group, by kind; counts only. */
data class ManagedObjectCounts(
    val groups: Int = 0,
    val users: Int = 0,
    val computers: Int = 0,
    val others: Int = 0,
) {
  val total: Int
    get() = groups + users + computers + others
}

/** Counts [entries] (carrying objectClass) by kind; a computer is also a user class in AD. */
internal fun classifyManagedObjects(entries: List<SearchResultEntry>): ManagedObjectCounts {
  var groups = 0
  var users = 0
  var computers = 0
  var others = 0
  entries.forEach { entry ->
    val classes = entry.getAttributeValues("objectClass").orEmpty().map { it.lowercase() }
    when {
      "computer" in classes -> computers++
      "group" in classes -> groups++
      "user" in classes -> users++
      else -> others++
    }
  }
  return ManagedObjectCounts(groups, users, computers, others)
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
    if (sameNameEntries.count { e -> DirectoryOus.isInGroupOu(e.dn) } > 1) {
      throw GroupCreateRefusedException(spec.name, "more than one group with that name exists")
    }
    return GroupCreatePlan.Exists(it)
  }
  if (sameNameEntries.isNotEmpty()) {
    throw GroupCreateRefusedException(spec.name, "a group with that name exists at another DN")
  }
  return GroupCreatePlan.Create(dn)
}
