-- AI assistant artifacts: documents a bot writes with the member (course outlines, quizzes, notes),
-- each with its saved versions. Run against the Member Portal database after
-- agent-chat-migration.sql. Re-runnable. Apply with the file on stdin so it stops at the first error:
--   mariadb -h <member-portal db host> -u <user> -p member_profile < agent-artifacts-migration.sql

CREATE TABLE IF NOT EXISTS `agent_artifact` (
  `id` char(36) NOT NULL COMMENT 'Random UUID',
  `conversation_id` char(36) NOT NULL,
  `kind` varchar(32) NOT NULL COMMENT 'markdown, course_outline or quiz',
  `title` varchar(200) NOT NULL,
  `current_version` int(11) NOT NULL DEFAULT 1,
  `created_at` datetime NOT NULL COMMENT 'UTC',
  `updated_at` datetime NOT NULL COMMENT 'UTC',
  PRIMARY KEY (`id`),
  KEY `agent_artifact_conversation_IDX` (`conversation_id`,`created_at`),
  CONSTRAINT `agent_artifact_conversation_FK` FOREIGN KEY (`conversation_id`) REFERENCES `agent_conversation` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Documents written in AI assistant conversations.';

CREATE TABLE IF NOT EXISTS `agent_artifact_version` (
  `artifact_id` char(36) NOT NULL,
  `version` int(11) NOT NULL COMMENT '1-based; every save adds one',
  `content` mediumtext NOT NULL COMMENT 'Markdown, or JSON for course_outline and quiz',
  `author` varchar(64) NOT NULL COMMENT '"assistant" or the member who edited it',
  `created_at` datetime NOT NULL COMMENT 'UTC',
  PRIMARY KEY (`artifact_id`,`version`),
  CONSTRAINT `agent_artifact_version_artifact_FK` FOREIGN KEY (`artifact_id`) REFERENCES `agent_artifact` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Saved versions of artifacts.';
