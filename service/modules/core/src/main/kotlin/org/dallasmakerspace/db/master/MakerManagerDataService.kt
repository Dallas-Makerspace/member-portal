package org.dallasmakerspace.db.master

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.models.Account
import org.dallasmakerspace.models.AccountInfo

/**
 * Service for interacting with the dms-master database server. Includes AccessControl,
 * dms-makermanager, and dms-whmcs databases.
 */
@Singleton
class MakerManagerDataService
@Inject
constructor(private val makerManagerDataRepository: MakerManagerDataRepository) :
    IMakerManagerDataService {
  override suspend fun getAccountInfoMap(usernames: List<String>): Map<String, AccountInfo> {
    // Get related accounts - this includes the account(s) being queried and any related
    // accounts like the primary account or addon accounts
    val relatedAccountsMap = makerManagerDataRepository.getRelatedAccountsMap(usernames)
    val result = mutableMapOf<String, AccountInfo>()
    // We got related accounts for all queried users so we need to group the results in a map
    // keyed by username
    usernames.forEach { username ->
      val relatedAccounts = relatedAccountsMap[username] ?: emptyList()
      val primaryAccount =
          relatedAccounts.find { it.username == username && it.makerManagerUserId == 0 }
      val addonAccounts =
          relatedAccounts.filter { it.username == username && it.makerManagerUserId != 0 }
      result[username] =
          AccountInfo(
              isPrimaryAccount = primaryAccount?.relatedUsername == username,
              addonAccounts =
                  addonAccounts.map { Account(it.relatedUsername!!, it.whmcsUserId, it.adActive) },
              primaryAccount =
                  primaryAccount?.let {
                    Account(it.relatedUsername!!, it.whmcsUserId, it.adActive)
                  })
    }
    return result
  }

  /**
   * Get all users from MakerManager database
   *
   * @return List of MakerManagerUserInfo containing all users
   */
  override suspend fun getAllUsers(): List<MakerManagerUserInfo> {
    return makerManagerDataRepository.getAllUsers()
  }
}
