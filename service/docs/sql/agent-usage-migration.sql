-- AI assistant usage ledger: one row per finished turn, kept after its chat is deleted so the monthly
-- spend cap and the per-member daily question cap can't be reset by deleting chats. No foreign key
-- to the chat on purpose. Rows are deleted after 13 months by the nightly cleanup. Run against the
-- Member Portal database after agent-chat-migration.sql. Re-runnable (the backfill runs only while the
-- table is empty). Apply with the file on stdin so it stops at the first error:
--   mariadb -h <member-portal db host> -u <user> -p member_profile < agent-usage-migration.sql

CREATE TABLE IF NOT EXISTS `agent_usage` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `username` varchar(64) NOT NULL COMMENT 'Who asked',
  `bot_key` varchar(32) NOT NULL,
  `cost_usd` double NOT NULL DEFAULT 0,
  `created_at` datetime NOT NULL COMMENT 'UTC',
  PRIMARY KEY (`id`),
  KEY `agent_usage_created_IDX` (`created_at`),
  KEY `agent_usage_user_IDX` (`username`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='One row per AI assistant turn; outlives deleted chats.';

INSERT INTO `agent_usage` (`username`, `bot_key`, `cost_usd`, `created_at`)
SELECT c.`owner_username`, c.`bot_key`,
       (SELECT COALESCE(SUM(m.`cost_usd`), 0) FROM `agent_message` m
         WHERE m.`conversation_id` = u.`conversation_id` AND m.`turn_seq` = u.`turn_seq`),
       u.`created_at`
FROM `agent_message` u
JOIN `agent_conversation` c ON c.`id` = u.`conversation_id`
WHERE u.`role` = 'user'
  AND NOT EXISTS (SELECT 1 FROM `agent_usage`);
