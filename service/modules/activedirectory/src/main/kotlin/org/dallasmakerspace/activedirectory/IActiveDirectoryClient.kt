package org.dallasmakerspace.activedirectory

interface IActiveDirectoryClient {
  fun getUsersByUsernameList(usernames: List<String>): Map<String, Map<String, Any?>>

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
}
