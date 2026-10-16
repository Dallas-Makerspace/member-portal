package org.dallasmakerspace.activedirectory

import dagger.Reusable
import javax.inject.Inject

private const val GROUP_NAME_PREFIX_LENGTH = 3
private const val MAX_GROUP_MEMBERS = 10000

@Reusable
class ActiveDirectoryService
@Inject
constructor(private val activeDirectoryClient: IActiveDirectoryClient) : IActiveDirectoryService {

  /** {@inheritDoc} */
  override fun getMemberByUsername(username: String): ADUser {
    return getMembersByUsernameList(listOf(username))[username]
        ?: throw ADException("User $username not found in AD")
  }

  /** {@inheritDoc} */
  override fun getMemberByBadgeNumber(badgeNumber: String): ADUser {
    return getMembersByBadgeNumberList(listOf(badgeNumber))[badgeNumber]
        ?: throw ADException("User with badge $badgeNumber not found in AD")
  }

  /** {@inheritDoc} */
  override fun getMembersByBadgeNumberList(badgeNumberList: List<String>): Map<String, ADUser?> {
    val adSearchResult: Map<String, Map<String, Any?>> =
        activeDirectoryClient.getUsersByBadgeNumberList(badgeNumberList)

    // For each badgeNumber in the list, get the ADUser object.
    return badgeNumberList.associateWith { badgeNumber ->
      val memberMap = adSearchResult[badgeNumber] ?: return@associateWith null
      val memberOf = memberMap["memberOf"]
      val groups =
          if (memberOf is Array<*>) {
            val list = memberOf.toList()
            parseGroups(list.filterIsInstance<String>())
          } else {
            emptyList()
          }
      ADUser(
          sAMAccountName = memberMap["sAMAccountName"].toString(),
          givenName = memberMap["givenName"].toString(),
          sn = memberMap["sn"].toString(),
          displayName = memberMap["displayName"].toString(),
          mail = memberMap["mail"].toString(),
          telephoneNumber = memberMap["telephoneNumber"].toString(),
          employeeID = memberMap["employeeID"].toString(),
          objectGuid = memberMap["objectGUID"].toString(),
          whenCreated = memberMap["whenCreated"].toString(),
          enabled = memberMap["userAccountControl"].toString().toInt() and 2 != 2,
          groups = groups,
          distinguishedName = memberMap["distinguishedName"].toString(),
      )
    }
  }

  /** {@inheritDoc} */
  override fun getMembersByDnList(dnList: List<String>): List<ADUser> {
    val adSearchResult: Map<String, Map<String, Any?>> =
        activeDirectoryClient.getUsersByDnList(dnList)

    // For each DN in the list, get the ADUser object.
    return dnList
        .map { dn ->
          val memberMap = adSearchResult[dn] ?: return@map null
          val groups = emptyList<ADGroup>()
          ADUser(
              sAMAccountName = memberMap["sAMAccountName"].toString(),
              givenName = memberMap["givenName"].toString(),
              sn = memberMap["sn"].toString(),
              displayName = memberMap["displayName"].toString(),
              mail = memberMap["mail"].toString(),
              telephoneNumber = memberMap["telephoneNumber"].toString(),
              employeeID = memberMap["employeeID"].toString(),
              objectGuid = memberMap["objectGUID"].toString(),
              whenCreated = memberMap["whenCreated"].toString(),
              enabled = memberMap["userAccountControl"].toString().toInt() and 2 != 2,
              groups = groups,
              // dnList's own key is already the canonical DN for this entry.
              distinguishedName = dn,
          )
        }
        .filterNotNull()
  }

  /** {@inheritDoc} */
  override fun getMembersByUsernameList(usernameList: List<String>): Map<String, ADUser?> {
    val adSearchResult: Map<String, Map<String, Any?>> =
        activeDirectoryClient.getUsersByUsernameList(usernameList)

    // For each username in the list, get the ADUser object.
    return usernameList.associateWith { username ->
      val memberMap = adSearchResult[username] ?: return@associateWith null
      val memberOf = memberMap["memberOf"]
      val groups =
          if (memberOf is Array<*>) {
            val list = memberOf.toList()
            parseGroups(list.filterIsInstance<String>())
          } else {
            emptyList()
          }
      ADUser(
          sAMAccountName = memberMap["sAMAccountName"].toString(),
          givenName = memberMap["givenName"].toString(),
          sn = memberMap["sn"].toString(),
          displayName = memberMap["displayName"].toString(),
          mail = memberMap["mail"].toString(),
          telephoneNumber = memberMap["telephoneNumber"].toString(),
          employeeID = memberMap["employeeID"].toString(),
          objectGuid = memberMap["objectGUID"].toString(),
          whenCreated = memberMap["whenCreated"].toString(),
          enabled = memberMap["userAccountControl"].toString().toInt() and 2 != 2,
          groups = groups,
          distinguishedName = memberMap["distinguishedName"].toString(),
      )
    }
  }

  /** {@inheritDoc} */
  override fun getGroup(groupname: String): ADGroup =
      toGroup(activeDirectoryClient.getGroup(groupname))

  /** {@inheritDoc} */
  override fun getGroupStrict(groupname: String): ADGroup =
      toGroup(activeDirectoryClient.getGroupStrict(groupname))

  private fun toGroup(adResult: Map<String, Any?>): ADGroup {

    // Extract administrators from the result if available
    val administrators =
        (adResult["administrators"] as? Array<*>)?.map { it.toString() } ?: emptyList()

    // Parse members and nested groups, including disabled accounts for the single-group view
    val parsed = parseMembersAndGroups(adResult, includeDisabled = true)
    val members = parsed.users
    val nestedGroups = parsed.groupDns

    return ADGroup(
        cn = adResult["cn"].toString(),
        description = adResult["description"]?.toString(),
        distinguishedName = adResult["distinguishedName"].toString(),
        objectGuid = adResult["objectGUID"]?.toString(), // Convert bytes to GUID String
        members = members,
        membersListIncomplete = (adResult["member"] as Array<*>).size > MAX_GROUP_MEMBERS,
        administrators = administrators,
        nestedGroups = nestedGroups,
        sourceMemberCount = parsed.sourceCount,
        unresolvedMemberCount = parsed.unresolvedCount,
        managedBy = adResult["managedBy"]?.toString()?.takeIf { it.isNotBlank() },
        parentGroups = attributeStrings(adResult["memberOf"]),
        samAccountName = adResult["sAMAccountName"]?.toString()?.takeIf { it.isNotBlank() },
        groupType = adResult["groupType"]?.toString()?.toIntOrNull(),
    )
  }

  override fun getMultipleGroups(groupnames: List<String>): List<ADGroup> {
    val adResults = activeDirectoryClient.getMultipleGroups(groupnames)
    return adResults.values.map { groupData ->
      val administrators =
          (groupData["administrators"] as? Array<*>)?.map { it.toString() } ?: emptyList()

      val parsed = parseMembersAndGroups(groupData)
      val members = parsed.users
      val nestedGroups = parsed.groupDns

      ADGroup(
          cn = groupData["cn"].toString(),
          description = groupData["description"]?.toString(),
          distinguishedName = groupData["distinguishedName"].toString(),
          objectGuid = groupData["objectGUID"]?.toString(),
          members = members,
          membersListIncomplete = (groupData["member"] as Array<*>).size > MAX_GROUP_MEMBERS,
          administrators = administrators,
          nestedGroups = nestedGroups,
          managedBy = groupData["managedBy"]?.toString()?.takeIf { it.isNotBlank() },
      )
    }
  }

  /** {@inheritDoc} */
  override fun getAllGroups(): List<ADGroup> {
    val adResults = activeDirectoryClient.getAllGroups()
    return adResults.map { (groupName, groupData) -> parseGroup(groupData) }
  }

  /** {@inheritDoc} */
  override fun getMembersByLoggedInDays(days: Int): List<ADUser> {
    val adSearchResult: Map<String, Map<String, Any?>> =
        activeDirectoryClient.getUsersByLogonDays(days)

    // For each username in the list, get the ADUser object.
    return adSearchResult.values.map { memberMap ->
      val memberOf = memberMap["memberOf"]
      val groups =
          if (memberOf is Array<*>) {
            val list = memberOf.toList()
            parseGroups(list.filterIsInstance<String>())
          } else {
            emptyList()
          }
      ADUser(
          sAMAccountName = memberMap["sAMAccountName"].toString(),
          givenName = memberMap["givenName"].toString(),
          sn = memberMap["sn"].toString(),
          displayName = memberMap["displayName"].toString(),
          mail = memberMap["mail"].toString(),
          telephoneNumber = memberMap["telephoneNumber"].toString(),
          employeeID = memberMap["employeeID"].toString(),
          objectGuid = memberMap["objectGUID"].toString(),
          whenCreated = memberMap["whenCreated"].toString(),
          enabled = memberMap["userAccountControl"].toString().toInt() and 2 != 2,
          groups = groups,
          distinguishedName = memberMap["distinguishedName"].toString(),
      )
    }
  }

  private class ParsedMembers(
      val users: List<ADUser>,
      val groupDns: List<String>,
      val sourceCount: Int,
      val unresolvedCount: Int,
  )

  /**
   * Parses the list of members and separates them into users and nested groups.
   *
   * @param attributeMap The map of attributes for the group
   * @return the resolved users, the nested group DNs, the raw member count, and how many
   *   user-looking DNs did not resolve to a user
   */
  private fun parseMembersAndGroups(
      attributeMap: Map<String, Any?>?,
      includeDisabled: Boolean = false,
  ): ParsedMembers {
    if (attributeMap.isNullOrEmpty()) return ParsedMembers(emptyList(), emptyList(), 0, 0)

    // Get the list of members from the attribute map
    val allMembers = (attributeMap["member"] as? Array<*>) ?: emptyList<String>()
    val sourceCount = (allMembers as Array<*>).size
    val members = allMembers.take(MAX_GROUP_MEMBERS).map { it.toString() }

    // Separate user DNs and group DNs
    val (groupDNs, userDNs) = members.partition { isGroupDN(it) }

    // Fetch ADUser objects for user DNs only
    val resolved = userDNs.chunked(100).flatMap { subChunk -> getMembersByDnList(subChunk) }
    val users =
        resolved
            .let { if (includeDisabled) it else it.filter { u -> u.enabled } }
            .distinctBy { it.sAMAccountName }

    return ParsedMembers(
        users, groupDNs, sourceCount, (userDNs.size - resolved.size).coerceAtLeast(0))
  }

  /**
   * Parses the list of group names into a list of ADGroup objects.
   *
   * @param list The list of group names in distinguishedName format
   *   "cn=Members,ou=Security,ou=Groups,dc=dms,dc=local".
   * @return The list of ADGroup objects. Empty list if the input list is null.
   */
  private fun parseGroups(list: List<String>?): List<ADGroup> {
    return list?.map {
      val parts = it.split(",")
      val startIndex = GROUP_NAME_PREFIX_LENGTH // to remove "cn=" from the start of the string.
      val cn = parts.first().substring(startIndex)
      val dn = it
      ADGroup(
          cn,
          distinguishedName = dn,
          description = null,
          objectGuid = null,
          members = listOf(),
          membersListIncomplete = false,
      )
    } ?: emptyList()
  }

  /**
   * Parses a single group's attribute map into an ADGroup object.
   *
   * @param attributeMap The map of attributes for the group
   * @return The ADGroup object
   */
  private fun parseGroup(attributeMap: Map<String, Any?>): ADGroup {
    val cn = attributeMap["cn"]?.toString() ?: ""
    val description = attributeMap["description"]?.toString()
    val distinguishedName = attributeMap["distinguishedName"]?.toString() ?: ""
    val objectGuid = attributeMap["objectGUID"]?.toString()

    // For getAllGroups, we just want the member count, not the full member list
    val memberCount =
        when (val memberData = attributeMap["member"]) {
          is Int -> memberData
          is Array<*> -> memberData.size
          else -> 0
        }

    val administrators =
        (attributeMap["administrators"] as? Array<*>)?.map { it.toString() } ?: emptyList()

    return ADGroup(
        cn = cn,
        description = description,
        distinguishedName = distinguishedName,
        objectGuid = objectGuid,
        members = emptyList(), // Don't load members for getAllGroups to improve performance
        membersListIncomplete = memberCount > 0, // Indicate that members are not loaded
        administrators = administrators,
    )
  }

  /** {@inheritDoc} */
  override fun removeUsersFromGroup(dmsUsernames: List<String>, group: String) {
    activeDirectoryClient.removeUsersFromGroup(dmsUsernames, group)
  }

  /** {@inheritDoc} */
  override fun addUsersToGroup(dmsUsernames: List<String>, group: String) {
    activeDirectoryClient.addUsersToGroup(dmsUsernames, group)
  }

  /** {@inheritDoc} */
  override fun updateBadgeNumber(username: String, newBadgeNumber: String) {
    activeDirectoryClient.updateBadgeNumber(username, newBadgeNumber)
  }

  /** {@inheritDoc} */
  override fun deleteGroup(groupName: String, expectedObjectGuid: String): Boolean =
      activeDirectoryClient.deleteGroup(groupName, expectedObjectGuid)

  /** {@inheritDoc} */
  override fun groupExists(groupName: String): Boolean =
      activeDirectoryClient.groupExists(groupName)

  /** {@inheritDoc} */
  override fun countManagedObjects(groupName: String): ManagedObjectCounts =
      activeDirectoryClient.countManagedObjects(groupName)

  /** {@inheritDoc} */
  override fun addGroupToParentGroup(groupName: String, parentGroupDn: String): Boolean =
      activeDirectoryClient.addGroupToParentGroup(groupName, parentGroupDn)

  /** {@inheritDoc} */
  override fun createGroup(spec: AdGroupSpec): AdGroupCreateResult =
      activeDirectoryClient.createGroup(spec)
}

/** A multi-valued LDAP attribute as strings: an array or collection, a single value, or empty. */
internal fun attributeStrings(value: Any?): List<String> =
    when (value) {
      null -> emptyList()
      is Array<*> -> value.mapNotNull { it?.toString() }
      is Collection<*> -> value.mapNotNull { it?.toString() }
      else -> listOf(value.toString())
    }
