package org.dallasmakerspace.workflows.groups

import java.util.concurrent.CopyOnWriteArrayList
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.AdGroupCreateResult
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.ResultCode
import org.dallasmakerspace.activedirectory.AmbiguousGroupException
import org.dallasmakerspace.activedirectory.GroupIdentity
import org.dallasmakerspace.activedirectory.GroupGuidNotFoundException
import org.dallasmakerspace.activedirectory.GroupMembershipRefusedException
import org.dallasmakerspace.activedirectory.ManagedObjectCounts
import org.dallasmakerspace.models.ProtectedGroups
import org.dallasmakerspace.activedirectory.AdGroupSpec
import org.dallasmakerspace.activedirectory.GroupCreateRefusedException
import org.dallasmakerspace.activedirectory.GroupDeletionRefusedException
import org.dallasmakerspace.activedirectory.GroupNotFoundException
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.members.GroupArchiveRepositoryMock
import org.dallasmakerspace.members.GroupHistoryRepositoryMock
import org.dallasmakerspace.members.IGroupArchiveRepository
import org.dallasmakerspace.members.IGroupHistoryRepository
import org.dallasmakerspace.models.DirectoryOus
import org.dallasmakerspace.workflows.groups.references.GroupReference
import org.dallasmakerspace.workflows.groups.references.GroupReferenceFinder

private val READS =
    listOf("getGroup", "getMembers", "findGroupByGuid", "groupExists", "countManagedObjects")

val MAIN_OU: String = DirectoryOus.GROUP_OUS.first()
val ADMIN_OU: String = DirectoryOus.GROUP_OUS[1]

fun archiveUser(name: String) =
    ADUser(
        sAMAccountName = name,
        mail = "$name@example.invalid",
        givenName = "Given",
        sn = "Family",
        telephoneNumber = null,
        employeeID = null,
        displayName = "Display $name",
        objectGuid = "guid-$name",
        whenCreated = null,
        enabled = true,
        groups = emptyList(),
    )

/** One group held by [FakeDirectory]; every field can be edited by a test. */
class FakeGroup(
    var name: String,
    var dn: String = "CN=$name,$MAIN_OU",
    var guid: String = "11111111-2222-3333-4444-555555555555",
    var description: String? = "A fake group",
    var managedBy: String? = "CN=Fake Owner,OU=Users,DC=example,DC=invalid",
    val members: MutableSet<String> = mutableSetOf("asmith", "bsmith", "csmith"),
    var nested: List<String> = emptyList(),
    var parents: List<String> = emptyList(),
    var unresolved: Int = 0,
    var incomplete: Boolean = false,
    var sam: String? = null,
    var groupType: Int? = null,
)

/**
 * In-memory directory with a call log. Group deletion and creation follow the real client's rules
 * (main OU only), so a step that strays outside it fails here too.
 */
class FakeDirectory : IActiveDirectoryService {
  val groups = linkedMapOf<String, FakeGroup>()
  val knownUsers = mutableSetOf("asmith", "bsmith", "csmith", "dsmith")
  val calls = CopyOnWriteArrayList<String>()
  private var nextGuid = 1

  /** Extra groups sharing a name with a primary one (e.g. in a sub-OU or the admin OU). */
  val twins = mutableListOf<FakeGroup>()

  /** Objects managed by a group (lowercase name), as countManagedObjects reports. */
  val managed = mutableMapOf<String, ManagedObjectCounts>()

  /** DNs of parent groups that no longer exist. */
  val missingParents = mutableSetOf<String>()

  /** A parent DN whose next membership add fails once, as a flaky directory would. */
  var failParentAddOnce: String? = null

  /** The GUIDs passed to deleteGroup, in order. */
  val deleteGuids = CopyOnWriteArrayList<String>()

  /** Applied to the next deleteGroup call after the group was removed: simulates a lost reply. */
  var failAfterDelete: Throwable? = null

  /** Thrown by the next deleteGroup call, before anything is removed. */
  var failDelete: Throwable? = null

  /** When true, addUsersToGroup silently adds nobody (a directory that ignores the write). */
  var ignoreAdds = false

  /** Called on every getGroup, before the lookup (to change the directory mid-run). */
  var onGetGroup: (String) -> Unit = {}

  fun add(group: FakeGroup): FakeGroup {
    groups[group.name.lowercase()] = group
    return group
  }

  fun find(name: String) = groups[name.lowercase()]

  private fun named(name: String) =
      (groups.values + twins).filter { it.name.equals(name.trim(), ignoreCase = true) }

  fun writes() = calls.filter { call -> READS.none { call.startsWith(it) } }

  private fun toAd(g: FakeGroup) =
      ADGroup(
          cn = g.name,
          description = g.description,
          distinguishedName = g.dn,
          objectGuid = g.guid,
          members = g.members.sorted().map(::archiveUser),
          membersListIncomplete = g.incomplete,
          nestedGroups = g.nested,
          sourceMemberCount = g.members.size + g.nested.size + g.unresolved,
          unresolvedMemberCount = g.unresolved,
          managedBy = g.managedBy,
          parentGroups = g.parents,
          samAccountName = g.sam,
          groupType = g.groupType,
      )

  override fun getGroup(groupname: String): ADGroup {
    calls += "getGroup:$groupname"
    onGetGroup(groupname)
    return toAd(find(groupname) ?: throw GroupNotFoundException(groupname))
  }

  override fun getGroupStrict(groupname: String): ADGroup {
    calls += "getGroupStrict:$groupname"
    onGetGroup(groupname)
    val matches = named(groupname)
    val inOus = matches.filter { DirectoryOus.isInGroupOu(it.dn) }
    if (inOus.size > 1) throw AmbiguousGroupException(groupname, inOus.size)
    return toAd(inOus.singleOrNull() ?: matches.firstOrNull() ?: throw GroupNotFoundException(groupname))
  }

  override fun groupExists(groupName: String): Boolean {
    calls += "groupExists:$groupName"
    return named(groupName).isNotEmpty()
  }

  override fun countManagedObjects(groupName: String): ManagedObjectCounts {
    calls += "countManagedObjects:$groupName"
    val matches = named(groupName)
    if (matches.isEmpty()) throw GroupNotFoundException(groupName)
    val inOus = matches.filter { DirectoryOus.isInGroupOu(it.dn) }
    if (inOus.size > 1) throw AmbiguousGroupException(groupName, inOus.size)
    return managed[groupName.trim().lowercase()] ?: ManagedObjectCounts()
  }

  override fun addGroupToParentGroup(groupName: String, parentGroupDn: String): Boolean {
    calls += "addGroupToParentGroup:$groupName:$parentGroupDn"
    val child = find(groupName) ?: throw GroupNotFoundException(groupName)
    if (!DirectoryOus.isInMainGroupOu(child.dn) || !DirectoryOus.isInMainGroupOu(parentGroupDn)) {
      throw GroupMembershipRefusedException(groupName, "outside the main group OU")
    }
    if (parentGroupDn in missingParents) throw GroupNotFoundException(parentGroupDn)
    if (parentGroupDn == failParentAddOnce) {
      failParentAddOnce = null
      throw java.io.IOException("directory hiccup")
    }
    if (child.parents.any { DirectoryOus.sameDn(it, parentGroupDn) }) return false
    child.parents = child.parents + parentGroupDn
    return true
  }

  override fun deleteGroup(groupName: String, expectedObjectGuid: String): Boolean {
    calls += "deleteGroup:$groupName"
    deleteGuids += expectedObjectGuid
    failDelete?.let {
      failDelete = null
      throw it
    }
    val withGuid =
        (groups.values + twins).filter { it.guid.equals(expectedObjectGuid, ignoreCase = true) }
    val g =
        (if (withGuid.size > 1) withGuid.filter { it.name.equals(groupName.trim(), true) } else withGuid)
            .singleOrNull() ?: throw GroupGuidNotFoundException(groupName)
    fun refuse(reason: String): Nothing = throw GroupDeletionRefusedException(groupName, g.dn, reason)
    if (!g.name.equals(groupName.trim(), ignoreCase = true)) refuse("its name does not match")
    if (!DirectoryOus.isInMainGroupOu(g.dn)) refuse("it is not in the main group OU")
    if (ProtectedGroups.find(g.name) != null) refuse("it is a protected group")
    val sameName = named(groupName).filter { DirectoryOus.isInGroupOu(it.dn) }
    if (sameName.size > 1) throw AmbiguousGroupException(groupName, sameName.size)
    groups.entries.removeIf { it.value === g }
    twins.remove(g)
    failAfterDelete?.let {
      failAfterDelete = null
      throw it
    }
    return true
  }

  override fun createGroup(spec: AdGroupSpec): AdGroupCreateResult {
    calls += "createGroup:${spec.name}"
    if (!DirectoryOus.isMainGroupOuOrChild(spec.parentOuDn)) {
      throw GroupCreateRefusedException(spec.name, "the parent is not in the main group OU")
    }
    val dn = "CN=${spec.name},${spec.parentOuDn}"
    find(spec.name)?.let {
      if (it.dn.equals(dn, ignoreCase = true)) return AdGroupCreateResult(it.dn, it.guid, false)
      throw GroupCreateRefusedException(spec.name, "a group with that name exists at another DN")
    }
    val guid = "99999999-0000-0000-0000-%012d".format(nextGuid++)
    add(
        FakeGroup(
            spec.name,
            dn,
            guid,
            spec.description,
            spec.managedByDn,
            members = mutableSetOf(),
            sam = spec.samAccountName,
            groupType = spec.groupType,
        ))
    return AdGroupCreateResult(dn, guid, true)
  }

  /** Like the by-name directory write: a member that is already in the group is an error. */
  override fun addUsersToGroup(dmsUsernames: List<String>, group: String) {
    calls += "addUsersToGroup:$group:${dmsUsernames.joinToString(",")}"
    val g = find(group) ?: throw GroupNotFoundException(group)
    if (ignoreAdds) return
    dmsUsernames.filter { it in knownUsers }.forEach {
      if (!g.members.add(it)) throw LDAPException(ResultCode.ENTRY_ALREADY_EXISTS)
    }
  }

  override fun addUsersToGroupByDn(dmsUsernames: List<String>, groupDn: String) {
    calls += "addUsersToGroupByDn:$groupDn:${dmsUsernames.joinToString(",")}"
    if (!DirectoryOus.isInMainGroupOu(groupDn)) {
      throw GroupMembershipRefusedException(groupDn, "outside the main group OU")
    }
    val g =
        (groups.values + twins).firstOrNull { DirectoryOus.sameDn(it.dn, groupDn) }
            ?: throw GroupNotFoundException(groupDn)
    if (ignoreAdds) return
    dmsUsernames.filter { it in knownUsers }.forEach { g.members += it }
  }

  override fun findGroupByGuid(objectGuid: String): GroupIdentity? {
    calls += "findGroupByGuid:$objectGuid"
    return (groups.values + twins)
        .singleOrNull { it.guid.equals(objectGuid, ignoreCase = true) }
        ?.let { GroupIdentity(it.name, it.dn, it.guid) }
  }

  override fun getMembersByUsernameList(usernameList: List<String>): Map<String, ADUser?> {
    calls += "getMembersByUsernameList:${usernameList.joinToString(",")}"
    return usernameList.associateWith { if (it in knownUsers) archiveUser(it) else null }
  }

  override fun removeUsersFromGroup(dmsUsernames: List<String>, group: String) {
    calls += "removeUsersFromGroup:$group"
  }

  override fun updateBadgeNumber(username: String, newBadgeNumber: String) {
    calls += "updateBadgeNumber:$username"
  }

  override fun getMemberByUsername(username: String): ADUser = error("unused")

  override fun getMemberByBadgeNumber(badgeNumber: String): ADUser = error("unused")

  override fun getMembersByBadgeNumberList(badgeNumberList: List<String>): Any = error("unused")

  override fun getMultipleGroups(groupnames: List<String>): List<ADGroup> = error("unused")

  override fun getAllGroups(): List<ADGroup> = error("unused")

  override fun getMembersByDnList(dnList: List<String>): List<ADUser> = error("unused")

  override fun getMembersByLoggedInDays(days: Int): List<ADUser> = error("unused")
}

/** Archive repository mock that also remembers the GUID updates and finalize calls. */
class RecordingArchive(private val d: GroupArchiveRepositoryMock = GroupArchiveRepositoryMock()) :
    IGroupArchiveRepository by d {
  val guidUpdates = CopyOnWriteArrayList<Pair<String, String>>()
  val finalized = CopyOnWriteArrayList<List<Any?>>()
  val calls = CopyOnWriteArrayList<String>()

  /** When false, finalizeArchived reports that there is no row. */
  var finalizeSucceeds = true

  /** When set, markDisabled returns this instead of the mock's answer. */
  var markDisabledResult: Boolean? = null

  override suspend fun markDisabled(groupName: String): Boolean {
    calls += "markDisabled:$groupName"
    return d.markDisabled(groupName).let { markDisabledResult ?: it }
  }

  override suspend fun markActive(groupName: String): Boolean {
    calls += "markActive:$groupName"
    return d.markActive(groupName)
  }

  override suspend fun finalizeArchived(
      groupName: String,
      archivedBy: String,
      reason: String,
      runId: Long?,
      objectGuid: String?,
  ): Boolean {
    calls += "finalizeArchived:$groupName"
    finalized += listOf(groupName, archivedBy, reason, runId, objectGuid)
    return finalizeSucceeds && d.finalizeArchived(groupName, archivedBy, reason, runId, objectGuid)
  }

  override suspend fun updateObjectGuid(groupName: String, objectGuid: String): Boolean {
    calls += "updateObjectGuid:$groupName"
    guidUpdates += groupName to objectGuid
    return d.updateObjectGuid(groupName, objectGuid)
  }
}

/** Remembers the rows the portal was asked to resolve. */
class RecordingGroupHistory(private val d: IGroupHistoryRepository = GroupHistoryRepositoryMock()) :
    IGroupHistoryRepository by d {
  val resolved = CopyOnWriteArrayList<Triple<String, String?, String?>>()

  override suspend fun resolveGroup(name: String, dn: String?, objectGuid: String?): Int {
    resolved += Triple(name, dn, objectGuid)
    return d.resolveGroup(name, dn, objectGuid)
  }
}

class StaticFinder(
    override val system: String,
    private val hits: List<GroupReference> = emptyList(),
    override val notes: List<String> = emptyList(),
) : GroupReferenceFinder {
  override suspend fun find(groupName: String) = hits
}
