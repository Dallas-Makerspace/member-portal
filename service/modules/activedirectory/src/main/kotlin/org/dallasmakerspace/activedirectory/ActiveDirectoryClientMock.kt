package org.dallasmakerspace.activedirectory

class ActiveDirectoryClientMock : IActiveDirectoryClient {
  override fun getUsersByUsernameList(usernames: List<String>): Map<String, Map<String, Any?>> =
      emptyMap()

  override fun getGroup(groupname: String): Map<String, Any?> = emptyMap()

  override fun getGroupStrict(groupname: String): Map<String, Any?> = emptyMap()

  override fun groupExists(groupName: String): Boolean = false

  override fun countManagedObjects(groupName: String): ManagedObjectCounts = ManagedObjectCounts()

  override fun addGroupToParentGroup(groupName: String, parentGroupDn: String): Boolean = false

  override fun getMultipleGroups(groupnames: List<String>): Map<String, Map<String, Any?>> =
      emptyMap()

  override fun getAllGroups(): Map<String, Map<String, Any?>> = emptyMap()

  override fun getUsersByDnList(dnList: List<String>): Map<String, Map<String, Any?>> = emptyMap()

  override fun getUsersByBadgeNumberList(
      badgeNumberList: List<String>
  ): Map<String, Map<String, Any?>> = emptyMap()

  override fun getUsersByLogonDays(days: Int): Map<String, Map<String, Any?>> = emptyMap()

  override fun removeUsersFromGroup(dmsUsernames: List<String>, group: String) = Unit

  override fun addUsersToGroup(dmsUsernames: List<String>, group: String) = Unit

  override fun getAllActiveUsersWithBadges(): Map<String, Map<String, Any?>> = emptyMap()

  override fun updateBadgeNumber(username: String, newBadgeNumber: String) = Unit

  override fun deleteGroup(groupName: String, expectedObjectGuid: String): Boolean = false

  override fun createGroup(spec: AdGroupSpec): AdGroupCreateResult =
      AdGroupCreateResult(buildGroupDn(spec.name, spec.parentOuDn), null, created = true)
}
