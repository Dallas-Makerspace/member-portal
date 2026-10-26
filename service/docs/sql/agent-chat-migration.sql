-- AI assistant chats: conversations, their messages, the tools each turn called, and per-message feedback.
-- Run against the Member Portal database (the one that holds `profile`). Re-runnable. Apply with the
-- file on stdin so it stops at the first error, e.g.:
--   mariadb -h <member-portal db host> -u <user> -p member_profile < agent-chat-migration.sql

CREATE TABLE IF NOT EXISTS `agent_conversation` (
  `id` char(36) NOT NULL COMMENT 'Random UUID; also the share id',
  `bot_key` varchar(32) NOT NULL COMMENT 'Which assistant, e.g. ask-ai',
  `owner_username` varchar(64) NOT NULL COMMENT 'Member who started the chat',
  `title` varchar(200) NOT NULL COMMENT 'First question, shortened',
  `visibility` varchar(8) NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC (visible to the assistant''s audience) or PRIVATE (owner only); never goes back to PUBLIC',
  `private_reason` varchar(255) DEFAULT NULL COMMENT 'Why the chat became private',
  `total_cost_usd` double NOT NULL DEFAULT 0 COMMENT 'Sum of agent_message.cost_usd',
  `created_at` datetime NOT NULL COMMENT 'UTC',
  `last_message_at` datetime NOT NULL COMMENT 'UTC; chats are deleted two years after this',
  PRIMARY KEY (`id`),
  KEY `agent_conversation_bot_visibility_IDX` (`bot_key`,`visibility`,`last_message_at`),
  KEY `agent_conversation_owner_IDX` (`owner_username`,`last_message_at`),
  KEY `agent_conversation_last_message_IDX` (`last_message_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI assistant conversations.';

CREATE TABLE IF NOT EXISTS `agent_message` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `conversation_id` char(36) NOT NULL,
  `turn_seq` int(11) NOT NULL COMMENT 'Which question/answer round this belongs to (1-based)',
  `role` varchar(16) NOT NULL COMMENT 'user, assistant or tool',
  `content` mediumtext NOT NULL COMMENT 'Model message as JSON (Koog Message)',
  `display_text` mediumtext DEFAULT NULL COMMENT 'Text shown in the chat; null for tool results',
  `model` varchar(64) DEFAULT NULL,
  `input_tokens` int(11) NOT NULL DEFAULT 0,
  `cache_read_tokens` int(11) NOT NULL DEFAULT 0,
  `cache_write_tokens` int(11) NOT NULL DEFAULT 0,
  `output_tokens` int(11) NOT NULL DEFAULT 0,
  `cost_usd` double NOT NULL DEFAULT 0,
  `created_at` datetime NOT NULL COMMENT 'UTC',
  PRIMARY KEY (`id`),
  KEY `agent_message_conversation_IDX` (`conversation_id`,`id`),
  KEY `agent_message_created_IDX` (`created_at`),
  CONSTRAINT `agent_message_conversation_FK` FOREIGN KEY (`conversation_id`) REFERENCES `agent_conversation` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Messages of AI assistant conversations, including tool results.';

CREATE TABLE IF NOT EXISTS `agent_tool_call` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `conversation_id` char(36) NOT NULL,
  `message_id` bigint(20) unsigned NOT NULL COMMENT 'The assistant message that asked for the tool',
  `tool_name` varchar(64) NOT NULL,
  `args` text NOT NULL COMMENT 'Arguments as JSON',
  `result_preview` text DEFAULT NULL COMMENT 'Start of what the tool returned',
  `sources` text DEFAULT NULL COMMENT 'Links the tool returned, as JSON',
  `touches_pii` tinyint(1) NOT NULL DEFAULT 0,
  `ok` tinyint(1) NOT NULL DEFAULT 1,
  `duration_ms` int(11) NOT NULL DEFAULT 0,
  `created_at` datetime NOT NULL COMMENT 'UTC',
  PRIMARY KEY (`id`),
  KEY `agent_tool_call_conversation_IDX` (`conversation_id`,`id`),
  KEY `agent_tool_call_message_FK` (`message_id`),
  CONSTRAINT `agent_tool_call_conversation_FK` FOREIGN KEY (`conversation_id`) REFERENCES `agent_conversation` (`id`) ON DELETE CASCADE,
  CONSTRAINT `agent_tool_call_message_FK` FOREIGN KEY (`message_id`) REFERENCES `agent_message` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Tool calls made by AI assistants (audit and evals).';

CREATE TABLE IF NOT EXISTS `agent_feedback` (
  `message_id` bigint(20) unsigned NOT NULL,
  `username` varchar(64) NOT NULL,
  `helpful` tinyint(1) NOT NULL,
  `created_at` datetime NOT NULL COMMENT 'UTC',
  PRIMARY KEY (`message_id`,`username`),
  CONSTRAINT `agent_feedback_message_FK` FOREIGN KEY (`message_id`) REFERENCES `agent_message` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Thumbs up/down on AI assistant answers.';
