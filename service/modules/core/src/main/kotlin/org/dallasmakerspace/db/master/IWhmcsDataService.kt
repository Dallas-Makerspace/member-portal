package org.dallasmakerspace.db.master

import java.time.LocalDate

interface IWhmcsDataService {
  suspend fun getAccountInfoMap(whmcsIdList: List<Int>): Map<Int, AccountStatus>

  suspend fun getAccountInfoMap(
      whmcsIdList: List<Int>,
      startDate: LocalDate
  ): Map<Int, AccountStatus>

  suspend fun getAccountRegdate(whmcsId: Int): LocalDate?
}
