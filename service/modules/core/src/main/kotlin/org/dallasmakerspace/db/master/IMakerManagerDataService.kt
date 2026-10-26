package org.dallasmakerspace.db.master

import org.dallasmakerspace.models.AccountInfo

interface IMakerManagerDataService {
  suspend fun getAccountInfoMap(usernames: List<String>): Map<String, AccountInfo>

  suspend fun getAllUsers(): List<MakerManagerUserInfo>

  /** Active users whose trimmed, lowercased email equals [normalizedEmail]. */
  suspend fun findActiveUsersByEmail(normalizedEmail: String): List<MakerManagerEmailMatch>

  /** Every active user with an email, oldest account first. */
  suspend fun getActiveUsersWithEmail(): List<MakerManagerEmailMatch>
}
