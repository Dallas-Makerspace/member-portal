package org.dallasmakerspace.members

import java.time.Instant
import org.dallasmakerspace.models.DMSMember

@Suppress("TooManyFunctions")
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

  /**
   * Sets only the member's Talk username (and clears the stored avatar) if they have none. False
   * when they already have one or another profile holds [discourseUsername].
   */
  suspend fun claimDiscourseUsernameIfUnlinked(username: String, discourseUsername: String): Boolean

  /** Undoes [claimDiscourseUsernameIfUnlinked]; only clears if the value is still [discourseUsername]. */
  suspend fun releaseDiscourseUsername(username: String, discourseUsername: String): Boolean

  suspend fun getMembersByDiscordUserIds(discordUserIds: List<String>): Map<String, DMSMember>

  /**
   * Sets the member's uploaded profile photo key. Returns the previously-set key (if any) so the
   * caller can delete the now-orphaned object from storage.
   */
  suspend fun setProfilePhoto(username: String, key: String, updatedAt: Instant): String?

  /** Clears the member's uploaded profile photo. Returns the previously-set key, if any. */
  suspend fun clearProfilePhoto(username: String): String?

  /** The member's current profile photo object-store key, if one is set. */
  suspend fun getProfilePhotoKey(username: String): String?
}
