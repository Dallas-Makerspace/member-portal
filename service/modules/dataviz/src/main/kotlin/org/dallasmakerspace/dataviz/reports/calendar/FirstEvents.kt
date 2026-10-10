package org.dallasmakerspace.dataviz.reports.calendar

import javax.inject.Inject
import org.dallasmakerspace.dataviz.reports.SqlReport
import org.dallasmakerspace.db.master.GenericRepository

class FirstEvents @Inject constructor(genericRepository: GenericRepository) :
    SqlReport(genericRepository) {
  override fun getName(): String {
    return "first-events"
  }

  override fun getQuery() =
      """
      SET STATEMENT max_statement_time=1.5 FOR
      WITH RelevantRegistrations AS (
          -- Step 1: only contacts created in the last 90 days, attended an event within 7 days of signup
          SELECT
              c.ad_username,
              e.id           AS event_id,
              e.name         AS event_name,
              e.event_start
          FROM
              `dms-calendar`.contacts c
          JOIN
              `dms-calendar`.registrations r
            ON c.ad_username = r.ad_username
          JOIN
              `dms-calendar`.events e
            ON r.event_id = e.id
          WHERE
              r.attended = 1
              AND e.event_start >= c.created
              AND e.event_start <= DATE_ADD(c.created, INTERVAL 7 DAY)
              AND c.created  >= DATE_SUB(CURDATE(), INTERVAL 90 DAY)
      ),
      MemberMinEventTime AS (
          -- Step 2: for each user, find their earliest event_start
          SELECT
              ad_username,
              MIN(event_start) AS first_event_timestamp
          FROM
              RelevantRegistrations
          GROUP BY
              ad_username
      ),
      CandidateFirstEvents AS (
          -- Step 3: keep only rows matching that earliest timestamp (in case of ties)
          SELECT
              rr.ad_username,
              rr.event_id,
              rr.event_name
          FROM
              RelevantRegistrations rr
          JOIN
              MemberMinEventTime met
            ON rr.ad_username = met.ad_username
           AND rr.event_start = met.first_event_timestamp
      ),
      MemberActualFirstEvent AS (
          -- Step 4: if two events share the same timestamp, pick the one with the lower event_id
          SELECT
              cfe_outer.ad_username,
              (
                  SELECT
                      sub.event_name
                  FROM
                      CandidateFirstEvents sub
                  WHERE
                      sub.ad_username = cfe_outer.ad_username
                  ORDER BY
                      sub.event_id ASC
                  LIMIT 1
              ) AS first_event_name
          FROM
              (SELECT DISTINCT ad_username FROM CandidateFirstEvents) cfe_outer
      )
      -- Final aggregation: group by a normalized version of first_event_name that strips
      -- out any non‐letter characters, but still return the original (non‐normalized) name as the label.
      SELECT
          MIN(mafe.first_event_name)  AS `First Event Name`,
          COUNT(DISTINCT mafe.ad_username) AS `Member Count`
      FROM
          MemberActualFirstEvent mafe
      WHERE
          mafe.first_event_name IS NOT NULL
      GROUP BY
          LOWER(
            REGEXP_REPLACE(mafe.first_event_name, '[^A-Za-z]', '')
          )
      ORDER BY
          `Member Count` DESC,
          `First Event Name` ASC;
      """
}
