package org.dallasmakerspace.dataviz.reports.membership

import javax.inject.Inject
import org.dallasmakerspace.dataviz.reports.SqlReport
import org.dallasmakerspace.db.master.GenericRepository

class DistributionDistance @Inject constructor(genericRepository: GenericRepository) :
    SqlReport(genericRepository) {
  override fun getName(): String {
    return "dist-distance"
  }

  override fun getQuery() =
      """
 SELECT 
    CASE 
        WHEN zc.distance_to_dms_mi IS NULL THEN 'Unknown'
        WHEN zc.distance_to_dms_mi < 5 THEN '0-5 miles'
        WHEN zc.distance_to_dms_mi < 10 THEN '5-10 miles'
        WHEN zc.distance_to_dms_mi < 25 THEN '10-25 miles'
        WHEN zc.distance_to_dms_mi < 50 THEN '25-50 miles'
        WHEN zc.distance_to_dms_mi < 100 THEN '50-100 miles'
        WHEN zc.distance_to_dms_mi < 500 THEN '100-500 miles'
        ELSE '500+ miles'
    END AS "Distance to DMS",
    COUNT(*) AS "Member count"
FROM `dms-makermanager`.users u
LEFT JOIN `dms-makermanager-dev`.zip_codes zc 
    ON CONVERT(LEFT(u.zip, 5) USING utf8mb4) = CONVERT(zc.zip_code USING utf8mb4)
WHERE u.ad_active = 1 
    AND u.zip <> ''
    AND zc.distance_to_dms_mi IS NOT NULL
GROUP BY 
    CASE 
        WHEN zc.distance_to_dms_mi IS NULL THEN 'Unknown'
        WHEN zc.distance_to_dms_mi < 5 THEN '0-5 miles'
        WHEN zc.distance_to_dms_mi < 10 THEN '5-10 miles'
        WHEN zc.distance_to_dms_mi < 25 THEN '10-25 miles'
        WHEN zc.distance_to_dms_mi < 50 THEN '25-50 miles'
        WHEN zc.distance_to_dms_mi < 100 THEN '50-100 miles'
        WHEN zc.distance_to_dms_mi < 500 THEN '100-500 miles'
        ELSE '500+ miles'
    END
ORDER BY 
    CASE 
        WHEN zc.distance_to_dms_mi IS NULL THEN 999
        ELSE AVG(zc.distance_to_dms_mi)
    END;
      """
}
