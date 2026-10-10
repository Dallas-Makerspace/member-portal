package org.dallasmakerspace.discourse

import javax.inject.Inject

class DiscourseService @Inject constructor(private val discourseApiClient: IDiscourseApiClient) {

  /**
   * Adds a discourse user to a group
   *
   * @param usernames the list of usernames of the user to add to the group
   * @param groupId the id of the group to add the user to, must be a value from the GroupId enum
   */
  private suspend fun addUserToGroup(usernames: List<String>, groupId: GroupId) {
    discourseApiClient.addUsersToGroup(usernames, groupId)
  }

  /**
   * Adds a user to the DMS Members V2 group
   *
   * @param usernames the list of username of the user to add to the group
   */
  suspend fun addUserToDmsMembersV2Group(usernames: List<String>) =
      addUserToGroup(usernames, GroupId.GROUP_DMS_MEMBERS_V2)

  /**
   * Removes a list of discourse user from a group
   *
   * @param usernames the list of usernames of the users to remove from the group
   * @param groupId the id of the group to remove the users from, must be a value from the GroupId
   *   enum
   */
  private suspend fun removeUsersFromGroup(usernames: List<String>, groupId: GroupId) {
    discourseApiClient.removeMembersFromGroup(usernames, groupId)
  }

  /**
   * Remove users from the DMS Members V2 group
   *
   * @param usernames the list of usernames of the user to remove from the group
   */
  suspend fun removeUserFromDmsMembersV2Group(usernames: List<String>) =
      removeUsersFromGroup(usernames, GroupId.GROUP_DMS_MEMBERS_V2)

  // Enum to store group ids
  @Suppress("Unused")
  enum class GroupId(val id: Int) {
    GROUP_DMS_MEMBERS_V1(DISCOURSE_GROUP_DMS_MEMBERS_V1_ID),
    GROUP_DMS_MEMBERS_V2(DISCOURSE_GROUP_DMS_MEMBERS_V2_ID)
  }

  private companion object {
    const val DISCOURSE_GROUP_DMS_MEMBERS_V1_ID = 41
    const val DISCOURSE_GROUP_DMS_MEMBERS_V2_ID = 109
  }
}
