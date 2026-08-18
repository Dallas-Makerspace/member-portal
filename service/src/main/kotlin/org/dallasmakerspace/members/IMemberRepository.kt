package org.dallasmakerspace.members

import org.dallasmakerspace.models.DMSMember

interface IMemberRepository {
  suspend fun getMemberOrInsert(username: String, enabled: Boolean?): DMSMember

  suspend fun updateMember(username: String, member: DMSMember)

  suspend fun getAllMembers(): List<DMSMember>

  suspend fun updateMembers(memberList: List<DMSMember>)

  suspend fun updateDiscourseAvatarUrl(username: String, avatarUrl: String): Boolean

  suspend fun updateDiscourseAvatarUrls(avatarUpdates: Map<String, String>): Map<String, Boolean>

  suspend fun getMembersNeedingAvatarRefresh(): List<DMSMember>

  suspend fun getMembersWithDiscourseUsernames(): List<DMSMember>

  suspend fun getMembersByUsernames(usernames: List<String>): Map<String, DMSMember>

  suspend fun getMembersByDiscourseUsernames(
      discourseUsernames: List<String>
  ): Map<String, DMSMember>

  suspend fun getMembersByDiscordUserIds(discordUserIds: List<String>): Map<String, DMSMember>
}
