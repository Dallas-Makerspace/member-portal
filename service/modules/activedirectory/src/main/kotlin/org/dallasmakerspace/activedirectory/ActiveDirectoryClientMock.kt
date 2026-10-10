package org.dallasmakerspace.activedirectory

class ActiveDirectoryClientMock : IActiveDirectoryClient {
  override fun getUsersByUsernameList(usernames: List<String>): Map<String, Map<String, Any?>> =
      emptyMap()

  override fun getGroup(groupname: String): Map<String, Any?> = emptyMap()

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
}
