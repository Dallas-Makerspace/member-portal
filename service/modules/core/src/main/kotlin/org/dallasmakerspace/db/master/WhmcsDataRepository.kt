package org.dallasmakerspace.db.master

import java.sql.ResultSet
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Repository for interacting with the dms-whmcs DB. */
@Singleton
class WhmcsDataRepository @Inject constructor() {
  /**
   * Get account info for a list of WHMCS user IDs.
   *
   * @param whmcsIdList List of WHMCS user IDs to get account info for
   * @param startDate Start date for the account info
   * @return Map of WHMCS user IDs to account info
   */
  suspend fun getAccountProductInfoMap(
      whmcsIdList: List<Int>,
      startDate: LocalDate
  ): Map<Int, List<WhmcsProductInfo>> {
    val result = mutableMapOf<Int, List<WhmcsProductInfo>>()
    suspendTransaction {
      val whmcsIdListString = whmcsIdList.joinToString(",") { it.toString() }
      val query =
          """
        SELECT
          h.userid,
          h.regdate,
          h.termination_date,
          h.domainstatus
        FROM
        `dms-whmcs`.tblhosting h
        JOIN `dms-whmcs`.tblproducts p
        ON
            h.packageid = p.id
        JOIN `dms-whmcs`.tblcustomfields c
        ON
            c.relid = p.id
        WHERE
          h.userid IN ($whmcsIdListString)
          AND c.fieldname = 'voting_rights'
          AND h.packageid = p.id
          AND (h.termination_date >= '$startDate'
            OR h.termination_date IS NULL
            OR h.regdate >= '$startDate')
      """
              .trimIndent()

      this.exec(query) { resultSet: ResultSet ->
        while (resultSet.next()) {
          val whmcsUserId = resultSet.getInt("userid")
          val terminationDate = resultSet.getString("termination_date")
          val productInfo =
              WhmcsProductInfo(
                  regDate = LocalDate.parse(resultSet.getString("regdate")),
                  terminationDate =
                      if ("0000-00-00" == terminationDate) null
                      else LocalDate.parse(terminationDate),
                  domainStatus = WhmcsDomainStatus.valueOf(resultSet.getString("domainstatus")))
          if (result.containsKey(whmcsUserId)) {
            result[whmcsUserId] = result[whmcsUserId]!!.plus(productInfo)
          } else {
            result[whmcsUserId] = listOf(productInfo)
          }
        }
      }
    }

    return result
  }

  /**
   * Get the regdate of the earliest record in tblhosting table
   *
   * @param whmcsId WHMCS user ID to get the earliest registration date for
   * @return The earliest registration date for the user, or null if no records found
   */
  suspend fun getAccountRegdate(whmcsId: Int): LocalDate? {
    var earliestRegdate: LocalDate? = null

    suspendTransaction {
      val query =
          """
        SELECT MIN(regdate) as earliest_regdate
        FROM `dms-whmcs`.tblhosting
        WHERE userid = $whmcsId
      """
              .trimIndent()

      this.exec(query) { resultSet: ResultSet ->
        if (resultSet.next()) {
          val regdateStr = resultSet.getString("earliest_regdate")
          if (regdateStr != null && regdateStr != "0000-00-00") {
            earliestRegdate = LocalDate.parse(regdateStr)
          }
        }
      }
    }

    return earliestRegdate
  }
}

data class WhmcsProductInfo(
    val regDate: LocalDate,
    val terminationDate: LocalDate?,
    val domainStatus: WhmcsDomainStatus,
)

enum class WhmcsDomainStatus(val domainStatus: String) {
  Terminated("Terminated"),
  Cancelled("Cancelled"),
  Active("Active"),
  Completed("Completed"),
  Pending("Pending"),
  Suspended("Suspended"),
}
