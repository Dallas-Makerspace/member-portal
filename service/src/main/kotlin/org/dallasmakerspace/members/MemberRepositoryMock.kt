package org.dallasmakerspace.members

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import org.dallasmakerspace.models.DMSMember
import org.dallasmakerspace.models.profilePhotoUrl

@Suppress("TooManyFunctions")
class MemberRepositoryMock @Inject constructor() : IMemberRepository {

  private val store = ConcurrentHashMap(SEED_MEMBERS.associateBy { it.username })
  private val profilePhotoKeys = ConcurrentHashMap<String, String>()

  override suspend fun getMemberOrInsert(username: String, enabled: Boolean?): DMSMember =
      store.getOrPut(username) {
        DMSMember(id = store.size + 100, username = username, enabled = enabled ?: true)
      }

  override suspend fun updateMember(username: String, member: DMSMember) {
    store[username] = member
  }

  override suspend fun getAllMembers(): List<DMSMember> = store.values.toList()

  override suspend fun updateMembers(memberList: List<DMSMember>) {
    memberList.forEach { store[it.username] = it }
  }

  override suspend fun updateDiscourseAvatarUrl(username: String, avatarUrl: String): Boolean {
    val m = store[username] ?: return false
    store[username] = m.copy(discourseAvatarUrl = avatarUrl)
    return true
  }

  override suspend fun updateDiscourseAvatarUrls(
      avatarUpdates: Map<String, String>
  ): Map<String, Boolean> =
      avatarUpdates.mapValues { (username, url) -> updateDiscourseAvatarUrl(username, url) }

  override suspend fun getMembersNeedingAvatarRefresh(): List<DMSMember> =
      store.values.filter { it.discourseUsername != null && it.discourseAvatarUrl == null }

  override suspend fun getMembersWithDiscourseUsernames(): List<DMSMember> =
      store.values.filter { it.discourseUsername != null }

  override suspend fun getMembersByUsernames(usernames: List<String>): Map<String, DMSMember> =
      usernames.mapNotNull { store[it] }.associateBy { it.username }

  override suspend fun getMembersByDiscourseUsernames(
      discourseUsernames: List<String>
  ): Map<String, DMSMember> =
      store.values
          .filter { it.discourseUsername in discourseUsernames }
          .associateBy { it.discourseUsername!! }

  override suspend fun getMembersByDiscordUserIds(
      discordUserIds: List<String>
  ): Map<String, DMSMember> =
      store.values.filter { it.discordUserId in discordUserIds }.associateBy { it.discordUserId!! }

  override suspend fun setProfilePhoto(username: String, key: String, updatedAt: Instant): String? {
    val existing =
        store[username] ?: throw IllegalArgumentException("Member does not exist in DB: $username")
    val previousKey = profilePhotoKeys.put(username, key)
    store[username] = existing.copy(profilePhotoUrl = profilePhotoUrl(username, key))
    return previousKey
  }

  override suspend fun clearProfilePhoto(username: String): String? {
    val existing =
        store[username] ?: throw IllegalArgumentException("Member does not exist in DB: $username")
    val previousKey = profilePhotoKeys.remove(username)
    store[username] = existing.copy(profilePhotoUrl = null)
    return previousKey
  }

  override suspend fun getProfilePhotoKey(username: String): String? = profilePhotoKeys[username]

  companion object {
    private val SEED_MEMBERS =
        listOf(
            DMSMember(id = 1, username = "user1", enabled = true, discourseUsername = "user1"),
            DMSMember(
                id = 2,
                username = "user2",
                enabled = false,
                discourseUsername = "user2",
                discordUserId = "987654321012345678",
            ),
        )
  }
}
