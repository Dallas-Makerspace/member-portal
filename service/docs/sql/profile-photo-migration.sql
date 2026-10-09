-- Schema changes for the profile-photo feature. create.sql (the reference DDL dump) has already
-- been updated to match; these are the actual ALTERs to run by hand against the prod `profile`
-- table, in order. Write these down and run them - don't let the schema drift.

-- Step 2: add the uploaded-photo columns.
ALTER TABLE `profile`
  ADD COLUMN `profile_photo_key` varchar(512) DEFAULT NULL
    COMMENT 'Object-store key of the member''s uploaded profile photo' AFTER `discord_webhook_id`,
  ADD COLUMN `profile_photo_updated_at` datetime DEFAULT NULL
    COMMENT 'UTC timestamp of the last profile photo upload' AFTER `profile_photo_key`;

-- Step 8: drop the legacy, always-NULL avatar_url column now that every read site has moved to
-- profile_photo_key / discourse_avatar_url (see docs/features/profile-photo/README.md for the
-- 2026-09-27 prod verification that avatar_url was 0/5601 populated).
ALTER TABLE `profile` DROP COLUMN `avatar_url`;
