-- Archive metadata plus stage 4 of the groups.object_guid migration (docs/features/workflows/README.md,
-- docs/features/workflows/archive-group.md). create.sql has already been updated to match; this is the
-- script to run by hand against the prod member_profile database BEFORE deploying the code that
-- reads archived_by / archive_reason / archive_run_id (the archive repository, GET /groups/archived).
-- Needs groups-objectguid-migration.sql (stages 1-3) applied first. Run on a throwaway MariaDB copy
-- first. Written for MariaDB 10.2.5+ (IF NOT EXISTS / IF EXISTS in ALTER, PERSISTENT generated columns).
--
-- What it does, in ONE ALTER so it is atomic (a failure leaves the table untouched):
--   * adds archived_by, archive_reason, archive_run_id (nullable; set only when status is ARCHIVED);
--   * adds generated columns active_name / active_dn, which are NULL for ARCHIVED rows;
--   * replaces the unique indexes groups_name_UNQ / groups_dn_UNQ with plain indexes on name and dn
--     plus unique indexes on active_name / active_dn. MariaDB allows many NULLs in a unique index, so
--     a new group can reuse the name of an ARCHIVED group while ACTIVE/DISABLED names stay unique.
-- The columns keep the table collation (utf8mb4_general_ci), so uniqueness stays case-insensitive.
-- The old dn index already covered the full varchar(512), so no prefix length is needed.
-- groups_object_guid_UNQ and every foreign key to groups.id are left untouched.

-- Step 1: shows the current columns and indexes (groups_name_UNQ and groups_dn_UNQ are unique).
SHOW COLUMNS FROM `groups`;
SHOW INDEX FROM `groups`;

-- Step 2: rows that would collide in the new unique indexes. Both queries must return no rows
-- (they cannot, while the old unique indexes exist; this guards a partial earlier run).
SELECT name, COUNT(*) AS n FROM `groups` WHERE status <> 'ARCHIVED' GROUP BY name HAVING n > 1;
SELECT dn, COUNT(*) AS n FROM `groups` WHERE status <> 'ARCHIVED' GROUP BY dn HAVING n > 1;

-- Step 3: the migration.
ALTER TABLE `groups`
  ADD COLUMN IF NOT EXISTS `archived_by` varchar(100) DEFAULT NULL COMMENT 'Username of the member who archived the group; NULL unless status is ARCHIVED' AFTER `archived_at`,
  ADD COLUMN IF NOT EXISTS `archive_reason` varchar(255) DEFAULT NULL COMMENT 'Why the group was archived; NULL unless status is ARCHIVED' AFTER `archived_by`,
  ADD COLUMN IF NOT EXISTS `archive_run_id` bigint DEFAULT NULL COMMENT 'Workflow run that archived the group; NULL unless status is ARCHIVED' AFTER `archive_reason`,
  ADD COLUMN IF NOT EXISTS `active_name` varchar(255) GENERATED ALWAYS AS (IF(`status` = 'ARCHIVED', NULL, `name`)) PERSISTENT COMMENT 'name, or NULL when ARCHIVED; carries the unique index' AFTER `archive_run_id`,
  ADD COLUMN IF NOT EXISTS `active_dn` varchar(512) GENERATED ALWAYS AS (IF(`status` = 'ARCHIVED', NULL, `dn`)) PERSISTENT COMMENT 'dn, or NULL when ARCHIVED; carries the unique index' AFTER `active_name`,
  DROP INDEX IF EXISTS `groups_name_UNQ`,
  DROP INDEX IF EXISTS `groups_dn_UNQ`,
  ADD INDEX IF NOT EXISTS `groups_name_IDX` (`name`),
  ADD INDEX IF NOT EXISTS `groups_dn_IDX` (`dn`),
  ADD UNIQUE INDEX IF NOT EXISTS `groups_active_name_UNQ` (`active_name`),
  ADD UNIQUE INDEX IF NOT EXISTS `groups_active_dn_UNQ` (`active_dn`);

-- Step 4: must return five rows: archived_by, archive_reason, archive_run_id, active_name, active_dn
-- (the last two with a non-empty GENERATION_EXPRESSION).
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, GENERATION_EXPRESSION, EXTRA
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'groups'
  AND COLUMN_NAME IN ('archived_by', 'archive_reason', 'archive_run_id', 'active_name', 'active_dn');

-- Step 5: unique indexes must be exactly groups_active_name_UNQ(active_name),
-- groups_active_dn_UNQ(active_dn) and groups_object_guid_UNQ(object_guid) plus PRIMARY.
-- Non-unique: groups_name_IDX(name) and groups_dn_IDX(dn). groups_name_UNQ / groups_dn_UNQ gone.
SELECT INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'groups'
GROUP BY INDEX_NAME, NON_UNIQUE;

-- Step 6: every existing row is not archived, so active_name = name and active_dn = dn for all.
-- not_mirrored must be 0 and every archive_* column NULL.
SELECT status, COUNT(*) AS total,
       SUM(NOT (active_name <=> name) OR NOT (active_dn <=> dn)) AS not_mirrored,
       SUM(archived_by IS NOT NULL OR archive_reason IS NOT NULL OR archive_run_id IS NOT NULL) AS with_archive_meta
FROM `groups`
GROUP BY status;

-- Rollback (only while no deployed code depends on the columns). Fails if a name or DN is held by
-- both an ARCHIVED and a non-archived row (a reused name): resolve those rows first, see Step R1.
-- Step R1: must return no rows before rolling back.
-- SELECT name, COUNT(*) AS n FROM `groups` GROUP BY name HAVING n > 1;
-- SELECT dn, COUNT(*) AS n FROM `groups` GROUP BY dn HAVING n > 1;
-- Step R2:
-- ALTER TABLE `groups`
--   ADD UNIQUE INDEX IF NOT EXISTS `groups_name_UNQ` (`name`),
--   ADD UNIQUE INDEX IF NOT EXISTS `groups_dn_UNQ` (`dn`),
--   DROP INDEX IF EXISTS `groups_active_name_UNQ`,
--   DROP INDEX IF EXISTS `groups_active_dn_UNQ`,
--   DROP INDEX IF EXISTS `groups_name_IDX`,
--   DROP INDEX IF EXISTS `groups_dn_IDX`,
--   DROP COLUMN IF EXISTS `active_dn`,
--   DROP COLUMN IF EXISTS `active_name`,
--   DROP COLUMN IF EXISTS `archive_run_id`,
--   DROP COLUMN IF EXISTS `archive_reason`,
--   DROP COLUMN IF EXISTS `archived_by`;
