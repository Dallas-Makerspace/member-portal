package org.dallasmakerspace.db.master

import org.dallasmakerspace.models.AccountInfo

interface IMakerManagerDataService {
  suspend fun getAccountInfoMap(usernames: List<String>): Map<String, AccountInfo>

  suspend fun getAllUsers(): List<MakerManagerUserInfo>
}
