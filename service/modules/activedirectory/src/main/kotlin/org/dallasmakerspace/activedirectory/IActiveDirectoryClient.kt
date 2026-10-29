package org.dallasmakerspace.activedirectory

interface IActiveDirectoryClient {
  fun getUsersByUsernameList(usernames: List<String>): Map<String, Map<String, Any?>>

  /**
   * Finds users whose username contains [term] or whose display name contains every word of
   * [term] (case-insensitive), at most [limit]; only sAMAccountName, displayName and
   * userAccountControl are returned. [term] is matched literally.
   */
  fun searchUsers(term: String, limit: Int): Map<String, Map<String, Any?>>

  fun getGroup(groupname: String): Map<String, Any?>

  fun getMultipleGroups(groupnames: List<String>): Map<String, Map<String, Any?>>

  fun getAllGroups(): Map<String, Map<String, Any?>>

  /**
   * Gets the user objects for the given list of distinguished names.
   *
   * @param dnList The list of distinguished names to get the user objects for. The format of the
   *   distinguished name is "CN=John Doe,OU=Users,DC=example,DC=com".
   */
  fun getUsersByDnList(dnList: List<String>): Map<String, Map<String, Any?>>

  /**
   * Gets the user object for the given badgeNumber.
   *
   * @param badgeNumberList The list of badgeNumbers of the users to get.
   * @return The user object for the given badgeNumber.
   */
  fun getUsersByBadgeNumberList(badgeNumberList: List<String>): Map<String, Map<String, Any?>>

  /**
   * Gets the users that have been logged in the last `days` days.
   *
   * @return The map of users that have been logged in the last `days` days. The key is the username
   *   and the value is the map of attributes for the user.
   */
  fun getUsersByLogonDays(days: Int): Map<String, Map<String, Any?>>

  /** Remove given list of users from group * */
  fun removeUsersFromGroup(dmsUsernames: List<String>, group: String)

  /** Add given list of users to group * */
  fun addUsersToGroup(dmsUsernames: List<String>, group: String)

  /**
   * Gets all active users with their badge numbers.
   *
   * @return Map of username to user attributes (including employeeID/badge number)
   */
  fun getAllActiveUsersWithBadges(): Map<String, Map<String, Any?>>

  /**
   * Updates the employeeID (badge number) for a user in Active Directory.
   *
   * @param username The username of the user to update
   * @param newBadgeNumber The new badge number (employeeID) to set
   * @throws Exception if the user is not found or update fails
   */
  fun updateBadgeNumber(username: String, newBadgeNumber: String)

  /**
   * Like [getGroup], but refuses when two or more groups of that name exist in the group OUs.
   *
   * @throws AmbiguousGroupException on such a duplicate
   */
  fun getGroupStrict(groupname: String): Map<String, Any?>

  /** True if a group of this name exists anywhere in the domain. */
  fun groupExists(groupName: String): Boolean

  /**
   * Counts the objects whose managedBy is the named group (read-only).
   *
   * @throws GroupNotFoundException if there is no such group
   * @throws AmbiguousGroupException if the name is duplicated in the group OUs
   */
  fun countManagedObjects(groupName: String): ManagedObjectCounts

  /** The group with this objectGUID, if one exists (read-only; no member load). */
  fun findGroupByGuid(objectGuid: String): GroupIdentity?

  /**
   * Adds users to the group at exactly [groupDn] (idempotent; members already there are skipped).
   *
   * @throws GroupMembershipRefusedException if the group is outside the main group OU
   * @throws GroupNotFoundException if there is no group at that DN
   */
  fun addUsersToGroupByDn(dmsUsernames: List<String>, groupDn: String)

  /**
   * Adds the named group as a member of [parentGroupDn] (idempotent). Both must be inside the main
   * group OU.
   *
   * @return true if the membership was added, false if it already existed
   * @throws GroupMembershipRefusedException if either group is outside the main group OU
   */
  fun addGroupToParentGroup(groupName: String, parentGroupDn: String): Boolean

  /**
   * Deletes the group whose objectGUID is [expectedObjectGuid] and whose name is [groupName].
   *
   * @return true if a group was deleted, false if it vanished meanwhile
   * @throws GroupGuidNotFoundException if no group has that GUID
   * @throws GroupDeletionRefusedException if it is outside the main group OU, protected, or has a
   *   different name
   * @throws AmbiguousGroupException if the name is duplicated in the group OUs
   */
  fun deleteGroup(groupName: String, expectedObjectGuid: String): Boolean

  /**
   * Creates a global security group; succeeds without changes if it already exists at the same DN.
   *
   * @throws GroupCreateRefusedException if the parent is outside the main group OU or the name is
   *   taken at another DN
   */
  fun createGroup(spec: AdGroupSpec): AdGroupCreateResult
}
