-- Committee messages: a chair's short broadcast to the committee's followers. One row per send; also the
-- audit log, the source for the per-committee send limits, and the id used in notification dedupe keys.
-- Run against the Member Portal database (the one that holds `profile` and `committee_subscriptions`).
-- Re-runnable. Apply with the file on stdin so it stops at the first error, e.g.:
--   mariadb -h <member-portal db host> -u <user> -p member_profile < committee-messages-migration.sql

CREATE TABLE IF NOT EXISTS `committee_messages` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `committee_id` int(11) NOT NULL COMMENT 'Committee.id from common-models (stable, never reused), not the slug',
  `sender_profile_id` int(10) unsigned NOT NULL COMMENT 'The id of the chair who sent the message',
  `message` varchar(120) NOT NULL COMMENT 'The chair''s text, without the short link',
  `talk_topic_id` int(11) DEFAULT NULL COMMENT 'Talk (Discourse) topic the message points to',
  `talk_url` varchar(255) DEFAULT NULL COMMENT 'Full Talk topic URL',
  `short_url` varchar(255) DEFAULT NULL COMMENT 'Short link sent to followers',
  `thread_created` tinyint(1) NOT NULL DEFAULT 0 COMMENT 'Whether the portal created the Talk thread for the chair',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, SENT or FAILED; FAILED rows do not count toward send limits',
  `recipient_count` int(11) NOT NULL DEFAULT 0 COMMENT 'Followers the message was fanned out to',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp() COMMENT 'When the chair sent the message (UTC)',
  PRIMARY KEY (`id`),
  KEY `committee_messages_committee_created_IDX` (`committee_id`,`created_at`) USING BTREE,
  KEY `committee_messages_sender_profile_FK` (`sender_profile_id`),
  CONSTRAINT `committee_messages_sender_profile_FK` FOREIGN KEY (`sender_profile_id`) REFERENCES `profile` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Chair messages to committee followers (audit log and send-limit source).';
