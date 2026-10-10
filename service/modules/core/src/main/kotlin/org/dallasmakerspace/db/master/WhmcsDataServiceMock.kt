package org.dallasmakerspace.db.master

import java.time.LocalDate
import javax.inject.Inject

class WhmcsDataServiceMock @Inject constructor() : IWhmcsDataService {

  private fun mockStatus(whmcsId: Int) =
      AccountStatus(
          wasActiveInRange = whmcsId != 102,
          lastInactiveDate = null,
          regDate = LocalDate.of(2021, 1, 1),
          daysInCurrentStatus = if (whmcsId == 102) 16 else null,
          timeline = emptyList(),
      )

  override suspend fun getAccountInfoMap(whmcsIdList: List<Int>): Map<Int, AccountStatus> =
      whmcsIdList.associateWith { mockStatus(it) }

  override suspend fun getAccountInfoMap(
      whmcsIdList: List<Int>,
      startDate: LocalDate
  ): Map<Int, AccountStatus> = whmcsIdList.associateWith { mockStatus(it) }

  override suspend fun getAccountRegdate(whmcsId: Int): LocalDate? = LocalDate.of(2021, 1, 1)
}
