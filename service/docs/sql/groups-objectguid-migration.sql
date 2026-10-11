-- Stage 1 of the groups.object_guid migration (docs/features/workflows/README.md, "Key groups by AD
-- objectGUID"). create.sql has already been updated to match; this is the script to run by hand
-- against the prod member_profile database BEFORE deploying the code that reads these columns.
-- Purely additive and safe to re-run (needs MariaDB 10.0.2+ for IF NOT EXISTS). groups_name_UNQ,
-- groups_dn_UNQ and every foreign key to groups.id are left untouched.

-- Step 1: shows the current columns and unique indexes (none of the new ones the first time).
SHOW COLUMNS FROM `groups`;
SHOW INDEX FROM `groups`;

-- Step 2: add the columns and the unique index. MariaDB allows many NULLs in a unique index, so rows
-- without a GUID do not collide.
ALTER TABLE `groups`
  ADD COLUMN IF NOT EXISTS `object_guid` varchar(36) DEFAULT NULL COMMENT 'AD objectGUID, canonical lowercase hyphenated; NULL until backfilled' AFTER `created`,
  ADD COLUMN IF NOT EXISTS `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, DISABLED or ARCHIVED' AFTER `object_guid`,
  ADD COLUMN IF NOT EXISTS `archived_at` timestamp NULL DEFAULT NULL COMMENT 'When the group was archived; NULL unless status is ARCHIVED' AFTER `status`,
  ADD UNIQUE INDEX IF NOT EXISTS `groups_object_guid_UNQ` (`object_guid`);

-- Step 3: must return three rows: object_guid, status, archived_at.
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'groups'
  AND COLUMN_NAME IN ('object_guid', 'status', 'archived_at');

-- Step 4: groups_name_UNQ, groups_dn_UNQ and groups_object_guid_UNQ must all be listed.
SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'groups' AND NON_UNIQUE = 0
GROUP BY INDEX_NAME;

-- Step 5: every existing row must be ACTIVE with a NULL GUID and NULL archived_at.
SELECT status, COUNT(*) AS total, SUM(object_guid IS NULL) AS without_guid,
       SUM(archived_at IS NULL) AS without_archived_at
FROM `groups`
GROUP BY status;

-- Rollback (only while no deployed code depends on the columns; drops any GUIDs already written):
-- ALTER TABLE `groups`
--   DROP INDEX IF EXISTS `groups_object_guid_UNQ`,
--   DROP COLUMN IF EXISTS `archived_at`,
--   DROP COLUMN IF EXISTS `status`,
--   DROP COLUMN IF EXISTS `object_guid`;
