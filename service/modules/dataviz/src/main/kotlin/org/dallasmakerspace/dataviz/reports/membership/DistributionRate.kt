package org.dallasmakerspace.dataviz.reports.membership

import javax.inject.Inject
import org.dallasmakerspace.dataviz.reports.SqlReport
import org.dallasmakerspace.db.master.GenericRepository

class DistributionRate @Inject constructor(genericRepository: GenericRepository) :
    SqlReport(genericRepository) {
  override fun getName(): String {
    return "dist-rate"
  }

  override fun getQuery() =
      """
SELECT CONCAT('$', CAST(amount AS UNSIGNED), ' - ', billingcycle) AS amount_billing, COUNT(*) AS count
FROM (
    SELECT amount, billingcycle FROM `dms-whmcs`.tblhosting WHERE domainstatus = 'Active'
    UNION ALL
    SELECT recurring AS amount, billingcycle FROM `dms-whmcs`.tblhostingaddons WHERE status = 'Active'
) AS combined
GROUP BY amount, billingcycle
ORDER BY CAST(amount AS UNSIGNED);
      """
}
