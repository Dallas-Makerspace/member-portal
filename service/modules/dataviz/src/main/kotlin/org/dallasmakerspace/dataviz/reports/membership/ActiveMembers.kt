package org.dallasmakerspace.dataviz.reports.membership

import java.time.Year
import javax.inject.Inject
import org.dallasmakerspace.dataviz.reports.SqlReport
import org.dallasmakerspace.db.master.GenericRepository

class ActiveMembers @Inject constructor(genericRepository: GenericRepository) :
    SqlReport(genericRepository) {
  override fun getName(): String {
    return "active-members"
  }

  override fun getQuery() =
      """
WITH
  -- 1) Month lookup
  months AS (
    SELECT 1 AS mn,'Jan' AS mname UNION ALL
    SELECT 2,'Feb' UNION ALL
    SELECT 3,'Mar' UNION ALL
    SELECT 4,'Apr' UNION ALL
    SELECT 5,'May' UNION ALL
    SELECT 6,'Jun' UNION ALL
    SELECT 7,'Jul' UNION ALL
    SELECT 8,'Aug' UNION ALL
    SELECT 9,'Sep' UNION ALL
    SELECT 10,'Oct' UNION ALL
    SELECT 11,'Nov' UNION ALL
    SELECT 12,'Dec'
  ),

  -- 2) The three years we care about
  years AS (
    SELECT YEAR(CURDATE())     AS yr UNION ALL
    SELECT YEAR(CURDATE()) - 1 AS yr UNION ALL
    SELECT YEAR(CURDATE()) - 2 AS yr
  ),

  -- 3) Every year×month with its month_end date
  month_list AS (
    SELECT
      y.yr,
      m.mn,
      m.mname,
      LAST_DAY(
        STR_TO_DATE(CONCAT(y.yr,'-',LPAD(m.mn,2,'0'),'-01'), '%Y-%m-%d')
      ) AS month_end
    FROM years AS y
    CROSS JOIN months AS m
  ),

  -- 4a) Primary‐member activity at each month_end
  hosting_activity AS (
    SELECT
      ml.yr,
      ml.mn,
      COUNT(DISTINCT h.userid) AS cnt
    FROM month_list AS ml
    JOIN `dms-whmcs`.`tblhosting` AS h
     ON h.regdate        <= ml.month_end
     AND (h.termination_date = '0000-00-00'
          OR h.termination_date > ml.month_end)
    GROUP BY ml.yr, ml.mn
  ),

  -- 4b) Addon (family‐member) activity at each month_end
  addon_activity AS (
    SELECT
      ml.yr,
      ml.mn,
      SUM(a.qty) AS cnt
    FROM month_list AS ml
    JOIN `dms-whmcs`.`tblhostingaddons` AS a
      ON a.status        = 'Active'
     AND a.regdate       <= ml.month_end
     AND (a.termination_date = '0000-00-00'
          OR a.termination_date > ml.month_end)
    GROUP BY ml.yr, ml.mn
  ),

  -- 5) Stack them and sum so we get (hosts + addons) per month
  combined_activity AS (
    SELECT yr, mn, cnt FROM hosting_activity
    UNION ALL
    SELECT yr, mn, cnt FROM addon_activity
  ),

  activity AS (
    SELECT
      yr,
      mn,
      SUM(cnt) AS cnt
    FROM combined_activity
    GROUP BY yr, mn
  )

-- 6) Pivot into one row per month, columns for each year,
--    nulling out future months in the current year
SELECT
  m.mname AS `Month`,
  CASE
    WHEN m.mn > MONTH(CURDATE()) THEN NULL
    ELSE COALESCE(SUM(CASE WHEN a.yr = YEAR(CURDATE())     THEN a.cnt END),0)
  END AS `${Year.now().value}`,
  COALESCE(SUM(CASE WHEN a.yr = YEAR(CURDATE()) - 1 THEN a.cnt END),0) AS `${Year.now().minusYears(1)}`,
  COALESCE(SUM(CASE WHEN a.yr = YEAR(CURDATE()) - 2 THEN a.cnt END),0) AS `${Year.now().minusYears(2)}`
FROM months AS m
LEFT JOIN activity AS a USING (mn)
GROUP BY m.mn, m.mname
ORDER BY m.mn;
      """
}
