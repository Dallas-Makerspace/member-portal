package org.dallasmakerspace.dataviz.reports

import javax.inject.Inject
import org.dallasmakerspace.db.master.GenericRepository

class TimeOfDayReport @Inject constructor(genericRepository: GenericRepository) :
    SqlReport(genericRepository) {
  override fun getName(): String {
    return "time-of-day"
  }

  override fun getQuery() =
      """
          SELECT
              Day,
              `Time Slot`,
              ROUND(AVG(daily_unique_users), 0) AS `Average Activity`
          FROM (
              SELECT
                  DAYNAME(CONVERT_TZ(e.created, 'UTC', 'America/Chicago')) AS Day,
                  DAYOFWEEK(CONVERT_TZ(e.created, 'UTC', 'America/Chicago')) AS day_num,
                  DATE(CONVERT_TZ(e.created, 'UTC', 'America/Chicago')) AS event_date,
                  CASE
                      WHEN HOUR(CONVERT_TZ(e.created, 'UTC', 'America/Chicago')) >= 7
                           AND HOUR(CONVERT_TZ(e.created, 'UTC', 'America/Chicago')) < 12
                      THEN '7AM - 12PM'
                      WHEN HOUR(CONVERT_TZ(e.created, 'UTC', 'America/Chicago')) >= 12
                           AND HOUR(CONVERT_TZ(e.created, 'UTC', 'America/Chicago')) < 16.5
                      THEN '12PM - 4:30PM'
                      WHEN HOUR(CONVERT_TZ(e.created, 'UTC', 'America/Chicago')) >= 16.5
                           AND HOUR(CONVERT_TZ(e.created, 'UTC', 'America/Chicago')) < 21
                      THEN '4:30PM - 9PM'
                      ELSE 'Other Hours'
                  END AS `Time Slot`,
                  COUNT(DISTINCT e.userId) AS daily_unique_users
              FROM
                  `AccessControl`.events e
              WHERE
                  e.created >= DATE_SUB(CURDATE(), INTERVAL 90 DAY)
                  AND HOUR(CONVERT_TZ(e.created, 'UTC', 'America/Chicago')) BETWEEN 7 AND 20
              GROUP BY
                  event_date,
                  DAYOFWEEK(CONVERT_TZ(e.created, 'UTC', 'America/Chicago')),
                  DAYNAME(CONVERT_TZ(e.created, 'UTC', 'America/Chicago')),
                  CASE
                      WHEN HOUR(CONVERT_TZ(e.created, 'UTC', 'America/Chicago')) >= 7
                           AND HOUR(CONVERT_TZ(e.created, 'UTC', 'America/Chicago')) < 12
                      THEN '7AM - 12PM'
                      WHEN HOUR(CONVERT_TZ(e.created, 'UTC', 'America/Chicago')) >= 12
                           AND HOUR(CONVERT_TZ(e.created, 'UTC', 'America/Chicago')) < 16.5
                      THEN '12PM - 4:30PM'
                      WHEN HOUR(CONVERT_TZ(e.created, 'UTC', 'America/Chicago')) >= 16.5
                           AND HOUR(CONVERT_TZ(e.created, 'UTC', 'America/Chicago')) < 21
                      THEN '4:30PM - 9PM'
                      ELSE 'Other Hours'
                  END
          ) daily_stats
          GROUP BY
              day_num,
              Day,
              `Time Slot`
          ORDER BY
              day_num,
              CASE
                  WHEN `Time Slot` = '7AM - 12PM' THEN 1
                  WHEN `Time Slot` = '12PM - 4:30PM' THEN 2
                  WHEN `Time Slot` = '4:30PM - 9PM' THEN 3
                  ELSE 4
              END;
      """
}
