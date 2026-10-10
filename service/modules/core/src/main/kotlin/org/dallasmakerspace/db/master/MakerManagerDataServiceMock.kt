package org.dallasmakerspace.db.master

import javax.inject.Inject
import org.dallasmakerspace.models.Account
import org.dallasmakerspace.models.AccountInfo

class MakerManagerDataServiceMock @Inject constructor() : IMakerManagerDataService {

  override suspend fun getAccountInfoMap(usernames: List<String>): Map<String, AccountInfo> =
      usernames.associateWith { username ->
        val user = MOCK_USERS.find { it.username == username }
        AccountInfo(
            isPrimaryAccount = true,
            wasActivePast90Days = user?.adActive ?: true,
            primaryAccount =
                user?.let { Account(it.username, it.whmcsUserId, it.adActive) }
                    ?: Account(username, 0, true),
            addonAccounts = emptyList(),
        )
      }

  override suspend fun getAllUsers(): List<MakerManagerUserInfo> = MOCK_USERS

  companion object {
    val MOCK_USERS =
        listOf(
            MakerManagerUserInfo(
                makerManagerId = 1,
                firstName = "User",
                lastName = "One",
                username = "user1",
                email = "user1@dallasmakerspace.org",
                whmcsUserId = 101,
                adActive = true,
                phone = "214-555-0001",
                badgeNumber = "1001",
                isPrimaryAccount = true,
            ),
            MakerManagerUserInfo(
                makerManagerId = 2,
                firstName = "User",
                lastName = "Two",
                username = "user2",
                email = "user2@dallasmakerspace.org",
                whmcsUserId = 102,
                adActive = false,
                phone = "214-555-0002",
                badgeNumber = "1002",
                isPrimaryAccount = true,
            ),
        )
  }
}
