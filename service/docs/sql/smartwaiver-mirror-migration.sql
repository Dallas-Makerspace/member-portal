-- SmartWaiver local mirror (docs/features/smartwaiver-mirror/README.md). Run by hand against the legacy
-- `dms-makermanager` database (the app's DB_MASTER connection) BEFORE deploying the code that reads the new
-- columns and tables. create.sql does not cover this schema (it belongs to the MakerManager database), so this
-- file is the only record of it. Run on a throwaway MariaDB copy first. Written for MariaDB 10.2.5+ (IF NOT
-- EXISTS / IF EXISTS in ALTER); prod is 10.11.
--
-- Re-runnable: every statement is IF NOT EXISTS, and the backup / dedupe steps do nothing on a second run.
--
-- What it does:
--   1. Backs up smartwaiver_data (CREATE TABLE ... AS SELECT, only if the backup does not exist yet).
--   2. Adds a surrogate primary key `id` (the table has none), then removes duplicate waiverId rows and adds
--      UNIQUE(waiverId). The legacy system stored one row per PARTICIPANT on multi-person waivers, so some
--      "duplicates" are real people; they are kept in the backup and re-created in smartwaiver_participants
--      by the Enrich backfill stage (check them with step 9).
--   3. Adds nullable typed columns to smartwaiver_data (nothing legacy reads or writes breaks).
--   4. Creates the child tables, backfill state table, and queue retry columns.
-- Deliberately NOT stored anywhere: driver's license, insurance, client IP, gender, marketing opt-ins, typed
-- signatures, signature images, PDFs, or the raw API response.

USE `dms-makermanager`;

-- Step 1: before-state. Expect smartwaiver_data to have no PRIMARY key and only IX_LOOKUP.
SHOW INDEX FROM smartwaiver_data;
SELECT COUNT(*) AS total_rows, COUNT(DISTINCT waiverId) AS distinct_waivers FROM smartwaiver_data;

-- Step 2: backup. Skipped on a re-run (table already exists), so it always holds the ORIGINAL rows.
CREATE TABLE IF NOT EXISTS smartwaiver_data_backup_20261017 AS SELECT * FROM smartwaiver_data;
SELECT COUNT(*) AS backup_rows FROM smartwaiver_data_backup_20261017;

-- Step 3: surrogate key (no-op if `id` exists). New rows from the app get ids automatically.
ALTER TABLE smartwaiver_data
  ADD COLUMN IF NOT EXISTS `id` bigint unsigned NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;

-- Step 4: keep one row per waiverId: prefer rows with referral answers, then a title, then the higher
-- checkinCount, then the earliest id. The rest are removed (they remain in the backup).
DELETE d FROM smartwaiver_data d
JOIN (
  SELECT id FROM (
    SELECT id, ROW_NUMBER() OVER (
      PARTITION BY waiverId
      ORDER BY (referralType IS NOT NULL) DESC, (documentTitle IS NOT NULL) DESC, checkinCount DESC, id ASC
    ) AS rn
    FROM smartwaiver_data
  ) ranked WHERE rn > 1
) extra ON extra.id = d.id;

-- Step 5: must return no rows.
SELECT waiverId, COUNT(*) AS n FROM smartwaiver_data GROUP BY waiverId HAVING n > 1;

-- Step 6: the typed columns (all nullable; legacy inserts that omit them keep working) and the unique key.
-- createdOnUtc is the API's createdOn (UTC) parsed from the legacy dateCompleted string.
ALTER TABLE smartwaiver_data
  ADD COLUMN IF NOT EXISTS `templateId` varchar(64) DEFAULT NULL AFTER `guardianLastName`,
  ADD COLUMN IF NOT EXISTS `createdOnUtc` datetime DEFAULT NULL AFTER `templateId`,
  ADD COLUMN IF NOT EXISTS `expirationDate` varchar(30) DEFAULT NULL AFTER `createdOnUtc`,
  ADD COLUMN IF NOT EXISTS `verified` tinyint(1) DEFAULT NULL AFTER `expirationDate`,
  ADD COLUMN IF NOT EXISTS `kiosk` tinyint(1) DEFAULT NULL AFTER `verified`,
  ADD COLUMN IF NOT EXISTS `isMinor` tinyint(1) DEFAULT NULL AFTER `kiosk`,
  ADD COLUMN IF NOT EXISTS `tags` text DEFAULT NULL COMMENT 'JSON array of waiver tags' AFTER `isMinor`,
  ADD COLUMN IF NOT EXISTS `phone` varchar(50) DEFAULT NULL COMMENT 'Primary participant phone' AFTER `tags`,
  ADD COLUMN IF NOT EXISTS `addressLineOne` varchar(255) DEFAULT NULL AFTER `phone`,
  ADD COLUMN IF NOT EXISTS `addressLineTwo` varchar(255) DEFAULT NULL AFTER `addressLineOne`,
  ADD COLUMN IF NOT EXISTS `addressCity` varchar(100) DEFAULT NULL AFTER `addressLineTwo`,
  ADD COLUMN IF NOT EXISTS `addressState` varchar(50) DEFAULT NULL AFTER `addressCity`,
  ADD COLUMN IF NOT EXISTS `addressZip` varchar(20) DEFAULT NULL AFTER `addressState`,
  ADD COLUMN IF NOT EXISTS `addressCountry` varchar(50) DEFAULT NULL AFTER `addressZip`,
  ADD COLUMN IF NOT EXISTS `emergencyContactName` varchar(255) DEFAULT NULL AFTER `addressCountry`,
  ADD COLUMN IF NOT EXISTS `emergencyContactRelation` varchar(100) DEFAULT NULL AFTER `emergencyContactName`,
  ADD COLUMN IF NOT EXISTS `emergencyContactPhone` varchar(50) DEFAULT NULL AFTER `emergencyContactRelation`,
  ADD COLUMN IF NOT EXISTS `mirroredAt` datetime DEFAULT NULL COMMENT 'UTC; set when the full mirror ran for this waiver' AFTER `emergencyContactPhone`,
  ADD COLUMN IF NOT EXISTS `mirrorVersion` int DEFAULT NULL COMMENT 'Mirror logic version that produced this row; NULL = legacy row' AFTER `mirroredAt`,
  ADD COLUMN IF NOT EXISTS `photosStatus` varchar(20) DEFAULT NULL COMMENT 'NONE (waiver has no photos), COMPLETE or PARTIAL' AFTER `mirrorVersion`,
  ADD UNIQUE INDEX IF NOT EXISTS `UX_smartwaiver_data_waiverId` (`waiverId`),
  ADD INDEX IF NOT EXISTS `IX_smartwaiver_data_createdOnUtc` (`createdOnUtc`),
  ADD INDEX IF NOT EXISTS `IX_smartwaiver_data_mirrorVersion` (`mirrorVersion`);

UPDATE smartwaiver_data
SET createdOnUtc = STR_TO_DATE(dateCompleted, '%Y-%m-%d %H:%i:%s')
WHERE createdOnUtc IS NULL AND CHAR_LENGTH(dateCompleted) = 19;

-- Step 7: child tables.
CREATE TABLE IF NOT EXISTS smartwaiver_participants (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `waiverId` varchar(100) NOT NULL,
  `position` int NOT NULL COMMENT '0 = primary signer; matches the check-ins position',
  `firstName` varchar(100) NOT NULL,
  `middleName` varchar(100) DEFAULT NULL,
  `lastName` varchar(100) NOT NULL,
  `dob` varchar(30) DEFAULT NULL,
  `isMinor` tinyint(1) DEFAULT NULL,
  `minorExpired` tinyint(1) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UX_smartwaiver_participants_waiver_position` (`waiverId`, `position`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS smartwaiver_guardians (
  `waiverId` varchar(100) NOT NULL,
  `firstName` varchar(100) DEFAULT NULL,
  `middleName` varchar(100) DEFAULT NULL,
  `lastName` varchar(100) DEFAULT NULL,
  `relationship` varchar(100) DEFAULT NULL,
  `phone` varchar(50) DEFAULT NULL,
  PRIMARY KEY (`waiverId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS smartwaiver_photos (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `waiverId` varchar(100) NOT NULL,
  `photoId` varchar(100) NOT NULL,
  `photoType` varchar(30) DEFAULT NULL,
  `tag` varchar(100) DEFAULT NULL,
  `fileType` varchar(10) NOT NULL,
  `takenAt` varchar(30) DEFAULT NULL,
  `s3Key` varchar(255) NOT NULL,
  `sizeBytes` int DEFAULT NULL,
  `mirroredAt` datetime NOT NULL COMMENT 'UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UX_smartwaiver_photos_waiver_photo` (`waiverId`, `photoId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS smartwaiver_checkins (
  `checkinId` bigint unsigned NOT NULL COMMENT 'SmartWaiver checkin id',
  `waiverId` varchar(100) NOT NULL,
  `position` int DEFAULT NULL COMMENT 'Participant position on the waiver',
  `templateId` varchar(64) DEFAULT NULL,
  `isMinor` tinyint(1) DEFAULT NULL,
  `checkedInAt` datetime NOT NULL COMMENT 'UTC',
  `dateSigned` datetime DEFAULT NULL COMMENT 'UTC',
  `mirroredAt` datetime NOT NULL COMMENT 'UTC',
  PRIMARY KEY (`checkinId`),
  KEY `IX_smartwaiver_checkins_waiver` (`waiverId`),
  KEY `IX_smartwaiver_checkins_checkedInAt` (`checkedInAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One row per backfill stage ('discover', 'enrich', 'checkins') plus 'run' for the daily request budget.
CREATE TABLE IF NOT EXISTS smartwaiver_backfill_state (
  `name` varchar(40) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE, RUNNING, WAITING (daily budget), PAUSED, DONE or FAILED',
  `cursorValue` varchar(100) DEFAULT NULL COMMENT 'Stage cursor, e.g. next window start (yyyy-MM-dd)',
  `doneCount` int NOT NULL DEFAULT 0,
  `totalCount` int DEFAULT NULL,
  `requestsToday` int NOT NULL DEFAULT 0,
  `budgetDay` date DEFAULT NULL COMMENT 'America/Chicago day requestsToday counts',
  `lastError` varchar(500) DEFAULT NULL,
  `startedAt` datetime DEFAULT NULL COMMENT 'UTC',
  `updatedAt` datetime DEFAULT NULL COMMENT 'UTC',
  `finishedAt` datetime DEFAULT NULL COMMENT 'UTC',
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Step 8: retry bookkeeping for the webhook queue (nullable / defaulted; the legacy code ignores them).
ALTER TABLE smartwaiver_hooks
  ADD COLUMN IF NOT EXISTS `attempts` int NOT NULL DEFAULT 0 AFTER `processed_at`,
  ADD COLUMN IF NOT EXISTS `last_error` varchar(500) DEFAULT NULL AFTER `attempts`;

-- Step 9: grants for the app's service user. It has SELECT on everything, INSERT on smartwaiver_data and
-- INSERT/UPDATE on smartwaiver_hooks today; the mirror also upserts (INSERT ... ON DUPLICATE KEY UPDATE) into
-- smartwaiver_data and the new tables. No DELETE anywhere: the app never removes mirrored rows.
GRANT SELECT, INSERT, UPDATE ON `dms-makermanager`.smartwaiver_data TO 'member_portal_svc'@'%';
GRANT SELECT, INSERT, UPDATE ON `dms-makermanager`.smartwaiver_participants TO 'member_portal_svc'@'%';
GRANT SELECT, INSERT, UPDATE ON `dms-makermanager`.smartwaiver_guardians TO 'member_portal_svc'@'%';
GRANT SELECT, INSERT, UPDATE ON `dms-makermanager`.smartwaiver_photos TO 'member_portal_svc'@'%';
GRANT SELECT, INSERT, UPDATE ON `dms-makermanager`.smartwaiver_checkins TO 'member_portal_svc'@'%';
GRANT SELECT, INSERT, UPDATE ON `dms-makermanager`.smartwaiver_backfill_state TO 'member_portal_svc'@'%';

-- Step 10: after-state checks.
-- (a) Must show PRIMARY(id) and UX_smartwaiver_data_waiverId unique on waiverId.
SELECT INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'smartwaiver_data'
GROUP BY INDEX_NAME, NON_UNIQUE;
-- (b) rows_now + removed = backup_rows; createdOnUtc must be filled for every row.
SELECT (SELECT COUNT(*) FROM smartwaiver_data) AS rows_now,
       (SELECT COUNT(*) FROM smartwaiver_data_backup_20261017) AS backup_rows,
       (SELECT COUNT(*) FROM smartwaiver_data WHERE createdOnUtc IS NULL) AS createdOnUtc_missing;
-- (c) Lists the five new tables and the two new queue columns.
SELECT TABLE_NAME FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'smartwaiver%' ORDER BY TABLE_NAME;
SELECT COLUMN_NAME FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'smartwaiver_hooks' AND COLUMN_NAME IN ('attempts', 'last_error');
-- (d) Rows removed in step 4 whose (waiverId, firstName, lastName) is not the surviving row: after the Enrich
-- stage each of these must exist in smartwaiver_participants. Run again after the backfill; the result must
-- be 0 (or only waivers SmartWaiver no longer has).
SELECT COUNT(*) AS removed_people_not_in_participants
FROM smartwaiver_data_backup_20261017 b
LEFT JOIN smartwaiver_participants p
  ON p.waiverId = b.waiverId AND p.firstName = b.firstName AND p.lastName = b.lastName
WHERE p.id IS NULL
  AND NOT EXISTS (SELECT 1 FROM smartwaiver_data d
                  WHERE d.waiverId = b.waiverId AND d.firstName = b.firstName AND d.lastName = b.lastName);
