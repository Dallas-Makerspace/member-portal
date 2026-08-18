package org.dallasmakerspace.members

import javax.inject.Inject
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.db.ProfileDAO
import org.dallasmakerspace.members.db.ProfileTable
import org.dallasmakerspace.members.db.suspendTransaction
import org.dallasmakerspace.models.DMSMember
import org.dallasmakerspace.models.daoToProfileModel
import org.jetbrains.exposed.sql.and

/**
 * Manages member data. Fetches and updates member data. Contains validation and orchestration logic
 * for updating member data.
 */
class MemberRepository @Inject constructor(loggerFactory: LoggerFactory) : IMemberRepository {
  private val log = loggerFactory.create(javaClass)

  /**
   * Fetches member data from the database. If the member does not exist in the database, inserts a
   * new member.
   *
   * @param username The username of the member to fetch.
   * @return The member data.
   */
  override suspend fun getMemberOrInsert(username: String, enabled: Boolean?): DMSMember {
    val existingMember = suspendTransaction {
      ProfileDAO.find { ProfileTable.username eq username }
          .firstOrNull()
          ?.let { daoToProfileModel(it) }
    }
    return if (existingMember == null) {
      log.info("Member not found in DB: $username; inserting new record.")
      daoToProfileModel(
          suspendTransaction {
            ProfileDAO.new(username) {
              if (enabled != null) {
                isEnabled = enabled
              }
            }
          })
    } else {
      log.debug("Member found in DB: $username")
      existingMember
    }
  }

  /**
   * Updates member data in the database.
   *
   * @param username The username of the member to update.
   * @param member The member data to update.
   */
  override suspend fun updateMember(username: String, member: DMSMember) {
    suspendTransaction {
      val existingMember =
          ProfileDAO.find { ProfileTable.username eq username }.firstOrNull()
              ?: throw IllegalArgumentException("Member does not exist in DB: $username")
      existingMember.apply {
        avatarUrl = member.avatarUrl
        discourseUsername = member.discourseUsername
        discourseAvatarUrl = member.discourseAvatarUrl
        discordUserId = member.discordUserId
        discordUsername = member.discordUsername
        discordAvatarUrl = member.discordAvatarUrl
        discordWebhookId = member.discordWebhookId
        linkedinUsername = member.linkedinUsername
      }
    }
  }

  /**
   * Fetches all member data from the database.
   *
   * @return A list of all member data.
   */
  override suspend fun getAllMembers(): List<DMSMember> {
    return suspendTransaction { ProfileDAO.all().map { daoToProfileModel(it) } }
  }

  override suspend fun updateMembers(memberList: List<DMSMember>) {
    memberList.forEach { member ->
      suspendTransaction {
        val existingMember =
            ProfileDAO.find { ProfileTable.username eq member.username }.firstOrNull()
                ?: throw IllegalArgumentException("Member does not exist in DB: ${member.username}")
        existingMember.apply {
          avatarUrl = member.avatarUrl
          isEnabled = member.enabled
          discourseUsername = member.discourseUsername
          discourseAvatarUrl = member.discourseAvatarUrl
          discordUserId = member.discordUserId
          discordUsername = member.discordUsername
          discordAvatarUrl = member.discordAvatarUrl
          discordWebhookId = member.discordWebhookId
          linkedinUsername = member.linkedinUsername
        }
      }
    }
  }

  /**
   * Updates the discourse avatar URL for a specific member.
   *
   * @param username The username of the member to update.
   * @param avatarUrl The new discourse avatar URL.
   * @return true if the update was successful, false if the member doesn't exist.
   */
  override suspend fun updateDiscourseAvatarUrl(username: String, avatarUrl: String): Boolean {
    return try {
      suspendTransaction {
        val existingMember = ProfileDAO.find { ProfileTable.username eq username }.firstOrNull()
        if (existingMember != null) {
          existingMember.discourseAvatarUrl = avatarUrl
          log.debug("Updated discourse avatar URL for member: $username")
          true
        } else {
          log.warn("Cannot update discourse avatar URL - member not found: $username")
          false
        }
      }
    } catch (e: Exception) {
      log.error("Failed to update discourse avatar URL for member: $username", e)
      false
    }
  }

  /**
   * Updates discourse avatar URLs for multiple members in a batch operation. This is more efficient
   * than individual updates for bulk operations.
   *
   * @param avatarUpdates A map of username to avatar URL.
   * @return A map of username to success status (true if updated, false if failed).
   */
  override suspend fun updateDiscourseAvatarUrls(
      avatarUpdates: Map<String, String>
  ): Map<String, Boolean> {
    val results = mutableMapOf<String, Boolean>()

    avatarUpdates.forEach { (username, avatarUrl) ->
      results[username] =
          try {
            suspendTransaction {
              val existingMember =
                  ProfileDAO.find { ProfileTable.username eq username }.firstOrNull()
              if (existingMember != null) {
                existingMember.discourseAvatarUrl = avatarUrl
                true
              } else {
                log.warn("Cannot update discourse avatar URL - member not found: $username")
                false
              }
            }
          } catch (e: Exception) {
            log.error("Failed to update discourse avatar URL for member: $username", e)
            false
          }
    }

    val successCount = results.values.count { it }
    val totalCount = results.size
    log.info("Batch updated discourse avatar URLs: $successCount/$totalCount successful")

    return results
  }

  /**
   * Gets members who have discourse usernames but missing or outdated avatar URLs. This is useful
   * for bulk avatar refresh operations.
   *
   * @return A list of members with discourse usernames that need avatar updates.
   */
  override suspend fun getMembersNeedingAvatarRefresh(): List<DMSMember> {
    return suspendTransaction {
      ProfileDAO.find {
            ProfileTable.discourseUsername.isNotNull() and ProfileTable.discourseAvatarUrl.isNull()
          }
          .map { daoToProfileModel(it) }
    }
  }

  /**
   * Gets members who have discourse usernames, used for avatar refresh operations.
   *
   * @return A list of members with discourse usernames.
   */
  override suspend fun getMembersWithDiscourseUsernames(): List<DMSMember> {
    return suspendTransaction {
      ProfileDAO.find { ProfileTable.discourseUsername.isNotNull() }.map { daoToProfileModel(it) }
    }
  }

  /**
   * Fetches member data for a specific list of usernames. This is more efficient than getAllMembers
   * when you only need a subset of members.
   *
   * @param usernames The list of usernames to fetch.
   * @return A map of username to DMSMember for found members.
   */
  override suspend fun getMembersByUsernames(usernames: List<String>): Map<String, DMSMember> {
    if (usernames.isEmpty()) return emptyMap()
    return suspendTransaction {
      ProfileDAO.find { ProfileTable.username inList usernames }
          .map { daoToProfileModel(it) }
          .associateBy { it.username }
    }
  }

  override suspend fun getMembersByDiscourseUsernames(
      discourseUsernames: List<String>
  ): Map<String, DMSMember> {
    if (discourseUsernames.isEmpty()) return emptyMap()
    return suspendTransaction {
      ProfileDAO.find { ProfileTable.discourseUsername inList discourseUsernames }
          .map { daoToProfileModel(it) }
          .filter { it.discourseUsername != null }
          .associateBy { it.discourseUsername!! }
    }
  }

  override suspend fun getMembersByDiscordUserIds(
      discordUserIds: List<String>
  ): Map<String, DMSMember> {
    if (discordUserIds.isEmpty()) return emptyMap()
    return suspendTransaction {
      ProfileDAO.find { ProfileTable.discordUserId inList discordUserIds }
          .map { daoToProfileModel(it) }
          .filter { it.discordUserId != null }
          .associateBy { it.discordUserId!! }
    }
  }
}
