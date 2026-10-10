package org.dallasmakerspace.dataviz.reports

import javax.inject.Inject
import org.dallasmakerspace.db.master.GenericRepository

class VisitorsByDayReport @Inject constructor(genericRepository: GenericRepository) :
    SqlReport(genericRepository) {
  override fun getName(): String {
    return "visitors-by-day"
  }

  override fun getQuery() =
      """
          SELECT
              DATE(CONVERT_TZ(e.date, 'UTC', 'America/Chicago')) AS Date,
            COUNT(DISTINCT e.userId) AS 'Unique Members'
          FROM
            `AccessControl`.events e
          WHERE
            e.date >= DATE_SUB(CURDATE(), INTERVAL 90 DAY)
          GROUP BY
            DATE(CONVERT_TZ(e.date, 'UTC', 'America/Chicago'))
          ORDER BY
            DATE(CONVERT_TZ(e.date, 'UTC', 'America/Chicago'))
      """
}
