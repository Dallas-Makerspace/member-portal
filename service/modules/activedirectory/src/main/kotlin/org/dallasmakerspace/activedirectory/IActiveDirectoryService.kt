package org.dallasmakerspace.activedirectory

interface IActiveDirectoryService {

  /**
   * Gets the ADUser object for the given username.
   *
   * @param username The username of the user to get.
   * @return The ADUser object for the given username.
   */
  fun getMemberByUsername(username: String): ADUser

  /**
   * Gets the ADUser object for the given list of usernames.
   *
   * @param usernameList The list of usernames to get the ADUser object for.
   * @return The map of ADUser objects for the given usernames.
   */
  fun getMembersByUsernameList(usernameList: List<String>): Map<String, ADUser?>

  /**
   * Gets the ADUser object for the given badgeNumber.
   *
   * @param badgeNumber The badgeNumber of the user to get.
   * @return The ADUser object for the given badgeNumber.
   */
  fun getMemberByBadgeNumber(badgeNumber: String): ADUser

  /**
   * Gets the ADUser objects for the given list of badgeNumbers.
   *
   * @param badgeNumberList The list of badgeNumbers to get the ADUser objects for.
   * @return The map of ADUser objects for the given badgeNumbers.
   */
  fun getMembersByBadgeNumberList(badgeNumberList: List<String>): Any

  /** Gets the ADGroup object for the given groupname. */
  fun getGroup(groupname: String): ADGroup

  /**
   * Like [getGroup], but refuses when two or more groups of that name exist in the group OUs.
   *
   * @throws AmbiguousGroupException on such a duplicate
   */
  fun getGroupStrict(groupname: String): ADGroup

  /** True if a group of this name exists anywhere in the domain (cheap, no member load). */
  fun groupExists(groupName: String): Boolean

  /**
   * Counts the objects whose managedBy is the named group (read-only, blocking).
   *
   * @throws GroupNotFoundException if there is no such group
   * @throws AmbiguousGroupException if the name is duplicated in the group OUs
   */
  fun countManagedObjects(groupName: String): ManagedObjectCounts

  /**
   * Adds the named group as a member of [parentGroupDn] (idempotent; blocking). Both must be inside
   * the main group OU.
   *
   * @return true if the membership was added, false if it already existed
   * @throws GroupMembershipRefusedException if either group is outside the main group OU
   */
  fun addGroupToParentGroup(groupName: String, parentGroupDn: String): Boolean

  /** Gets multiple ADGroup objects for the given list of group names with their members. */
  fun getMultipleGroups(groupnames: List<String>): List<ADGroup>

  /** Gets all ADGroup objects from Active Directory. */
  fun getAllGroups(): List<ADGroup>

  /**
   * Gets the ADUser objects for the given list of distinguished names.
   *
   * @param dnList The list of distinguished names to get the ADUser objects for. The format of the
   *   distinguished name is "CN=John Doe,OU=Users,DC=example,DC=com".
   */
  fun getMembersByDnList(dnList: List<String>): List<ADUser>

  /** Gets the users that have been logged in the last `days` days. */
  fun getMembersByLoggedInDays(days: Int): List<ADUser>

  /** Remove given list of users from group * */
  fun removeUsersFromGroup(dmsUsernames: List<String>, group: String): Unit

  /** Add given list of users to group * */
  fun addUsersToGroup(dmsUsernames: List<String>, group: String): Unit

  /**
   * Updates the badge number (employeeID) for a user in Active Directory.
   *
   * @param username The username of the user to update
   * @param newBadgeNumber The new badge number (employeeID) to set
   */
  fun updateBadgeNumber(username: String, newBadgeNumber: String)

  /**
   * Deletes the group whose objectGUID is [expectedObjectGuid] and whose name is [groupName].
   * Blocking LDAP call: run it on Dispatchers.IO.
   *
   * @return true if a group was deleted, false if it vanished meanwhile
   * @throws GroupGuidNotFoundException if no group has that GUID (never falls back to the name)
   * @throws GroupDeletionRefusedException if it is outside the main group OU, protected, or has a
   *   different name
   * @throws AmbiguousGroupException if the name is duplicated in the group OUs
   */
  fun deleteGroup(groupName: String, expectedObjectGuid: String): Boolean

  /**
   * Creates a global security group. Blocking LDAP call: run it on Dispatchers.IO. Succeeds without
   * changes (created = false) if the group already exists at the same DN.
   *
   * @throws GroupCreateRefusedException if the parent is outside the main group OU or the name is
   *   taken at another DN
   */
  fun createGroup(spec: AdGroupSpec): AdGroupCreateResult
}
