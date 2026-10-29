-- AI assistant: how long each answer took, shown in the chat's answer details. Run against the Member
-- Portal database. Older answers keep NULL. Apply with the file on stdin so it stops at the first error:
--   mariadb -h <member-portal db host> -u <user> -p member_profile < agent-message-duration-migration.sql

ALTER TABLE `agent_message`
  ADD COLUMN IF NOT EXISTS `duration_ms` int(11) DEFAULT NULL COMMENT 'Time the turn took; set on its final assistant message' AFTER `cost_usd`;
