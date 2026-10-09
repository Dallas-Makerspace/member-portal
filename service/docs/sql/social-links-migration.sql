-- Social links moved from profile.linkedin_username to member_settings (key `social_linkedin`).
-- Run by hand in prod after the service change that stops mapping linkedin_username has deployed,
-- and before the matching UI change. Safe to re-run: members who already have a social_linkedin
-- row are skipped. Ran in prod on 2026-10-09, then step 5 dropped the column.

-- Step 1: must return one row.
SHOW COLUMNS FROM `profile` LIKE 'linkedin_username';

-- Step 2: preview. Rows with will_copy = 0 aren't valid LinkedIn vanity names and are skipped.
SELECT p.`username`, p.`linkedin_username`,
       TRIM(p.`linkedin_username`) REGEXP '^([A-Za-z0-9-]|%[0-9A-Fa-f]{2}){3,100}$' AS will_copy
FROM `profile` p
WHERE TRIM(COALESCE(p.`linkedin_username`, '')) <> '';

-- Step 3: copy, attributed to the member (who entered it originally).
INSERT INTO `member_settings` (`profile_id`, `setting_key`, `setting_value`, `updated_by`, `updated_at`)
SELECT p.`id`, 'social_linkedin', TRIM(p.`linkedin_username`), p.`id`, UTC_TIMESTAMP()
FROM `profile` p
WHERE TRIM(p.`linkedin_username`) REGEXP '^([A-Za-z0-9-]|%[0-9A-Fa-f]{2}){3,100}$'
  AND NOT EXISTS (SELECT 1 FROM `member_settings` s
                  WHERE s.`profile_id` = p.`id` AND s.`setting_key` = 'social_linkedin');

-- Step 4: should equal the will_copy = 1 count from step 2.
SELECT COUNT(*) FROM `member_settings` WHERE `setting_key` = 'social_linkedin';

-- Step 5: once every old value is in member_settings history (not_in_history = 0), drop the column.
SELECT COUNT(*) AS old_values,
       SUM(NOT EXISTS (SELECT 1 FROM `member_settings` s
                       WHERE s.`profile_id` = p.`id` AND s.`setting_key` = 'social_linkedin'
                         AND s.`setting_value` = TRIM(p.`linkedin_username`))) AS not_in_history
FROM `profile` p
WHERE TRIM(COALESCE(p.`linkedin_username`, '')) <> '';

ALTER TABLE `profile` DROP COLUMN `linkedin_username`;
