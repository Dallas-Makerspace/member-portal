package org.dallasmakerspace.dataviz.reports.calendar

import java.time.Year
import javax.inject.Inject
import org.dallasmakerspace.dataviz.reports.SqlReport
import org.dallasmakerspace.db.master.GenericRepository

class CalTrendsEvents @Inject constructor(genericRepository: GenericRepository) :
    SqlReport(genericRepository) {
  override fun getName(): String {
    return "trends-events"
  }

  override fun getQuery() =
      """
SET STATEMENT max_statement_time=1.5 FOR
SELECT
    CONCAT('Week ', all_possible_weeks.WeekNum) AS `Week#`,

    -- Data for Current Year (e.g., 2025 if CURDATE() is in 2025)
    -- Shows NULL for future weeks of the current year
    IF(all_possible_weeks.WeekNum > WEEK(CURDATE(), 3) AND YEAR(CURDATE()) = YEAR(CURDATE()),
        NULL,
        SUM(CASE WHEN wc.event_year = YEAR(CURDATE()) THEN wc.count_events ELSE 0 END)
    ) AS `${Year.now().value}`,

    -- Data for Previous Year (e.g., 2024 if CURDATE() is in 2025)
    SUM(CASE WHEN wc.event_year = (YEAR(CURDATE()) - 1) THEN wc.count_events ELSE 0 END) AS `${Year.now().minusYears(1)}`,

    -- Data for Year Before Previous (e.g., 2023 if CURDATE() is in 2025)
    SUM(CASE WHEN wc.event_year = (YEAR(CURDATE()) - 2) THEN wc.count_events ELSE 0 END) AS `${Year.now().minusYears(2)}`

FROM
    (   -- This subquery generates week numbers 1 through 52.
        -- For MariaDB 5.3, this is a verbose but direct way.
        -- If you have a persistent 'numbers' or 'calendar_weeks' table, use that instead.
        SELECT 1 AS WeekNum UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL
        SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL
        SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL
        SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15 UNION ALL SELECT 16 UNION ALL
        SELECT 17 UNION ALL SELECT 18 UNION ALL SELECT 19 UNION ALL SELECT 20 UNION ALL
        SELECT 21 UNION ALL SELECT 22 UNION ALL SELECT 23 UNION ALL SELECT 24 UNION ALL
        SELECT 25 UNION ALL SELECT 26 UNION ALL SELECT 27 UNION ALL SELECT 28 UNION ALL
        SELECT 29 UNION ALL SELECT 30 UNION ALL SELECT 31 UNION ALL SELECT 32 UNION ALL
        SELECT 33 UNION ALL SELECT 34 UNION ALL SELECT 35 UNION ALL SELECT 36 UNION ALL
        SELECT 37 UNION ALL SELECT 38 UNION ALL SELECT 39 UNION ALL SELECT 40 UNION ALL
        SELECT 41 UNION ALL SELECT 42 UNION ALL SELECT 43 UNION ALL SELECT 44 UNION ALL
        SELECT 45 UNION ALL SELECT 46 UNION ALL SELECT 47 UNION ALL SELECT 48 UNION ALL
        SELECT 49 UNION ALL SELECT 50 UNION ALL SELECT 51 UNION ALL SELECT 52 -- Limited to 52 weeks
    ) AS all_possible_weeks
LEFT JOIN (
    SELECT
        YEAR(event_start) AS event_year,
        WEEK(event_start, 3) AS week_num, -- ISO 8601 week number
        COUNT(id) AS count_events
    FROM
        `dms-calendar`.events
    WHERE
        event_start >= MAKEDATE(YEAR(CURDATE()) - 2, 1) -- Start of (CurrentYear - 2)
        AND event_start < MAKEDATE(YEAR(CURDATE()) + 1, 1) -- Start of (CurrentYear + 1)
        AND status = 'completed' -- Only 'completed' events
    GROUP BY
        event_year, week_num
) AS wc ON all_possible_weeks.WeekNum = wc.week_num
GROUP BY
    all_possible_weeks.WeekNum
ORDER BY
    all_possible_weeks.WeekNum;
      """
}
