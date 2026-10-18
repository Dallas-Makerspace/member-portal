-- MySQL dump 10.13  Distrib 8.0.19, for Win64 (x86_64)
--
-- Host: localhost    Database: member_profile
-- ------------------------------------------------------
-- Server version	11.1.2-MariaDB-1:11.1.2+maria~ubu2204

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `activity_log`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `activity_log` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT COMMENT '_id is auto increment primary key.',
  `source` tinyint(3) unsigned NOT NULL COMMENT 'the source system that generated the activity log',
  `actor_profile_row_id` int(10) unsigned DEFAULT NULL COMMENT 'the actor that tiggered the log, foreign_key to profile table',
  `subject_profile_row_id` int(10) unsigned DEFAULT NULL COMMENT 'the actor that tiggered the log, foreign_key to profile table',
  `event` tinyint(3) unsigned NOT NULL COMMENT 'the event being logged, this has a different meaning for each source',
  `attributes` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Event attributes' CHECK (json_valid(`attributes`)),
  `created` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `activity_log_actor_profile_FK` (`actor_profile_row_id`),
  KEY `activity_log_subject_profile_FK` (`subject_profile_row_id`),
  CONSTRAINT `activity_log_actor_profile_FK` FOREIGN KEY (`actor_profile_row_id`) REFERENCES `profile` (`id`),
  CONSTRAINT `activity_log_subject_profile_FK` FOREIGN KEY (`subject_profile_row_id`) REFERENCES `profile` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1669 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Table to store activity log of various events for members, usually write ops performed on a member profile';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `group_history`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `group_history` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT COMMENT 'id is auto increment primary key.',
  `actor_id` int(10) unsigned NOT NULL COMMENT 'The ID of the user who performed the group action',
  `member_id` int(10) unsigned NOT NULL COMMENT 'The ID of the member affected by the group action',
  `group_id` int(10) unsigned NOT NULL COMMENT 'The ID of the group that was modified',
  `action_type` tinyint(3) unsigned NOT NULL DEFAULT 0 COMMENT 'The type of action: 0=ADD_USER, 1=REMOVE_USER',
  `event_timestamp` timestamp NOT NULL COMMENT 'The timestamp when the event occurred in Active Directory',
  `created` timestamp NOT NULL DEFAULT current_timestamp() COMMENT 'The timestamp when the record was created in our database',
  PRIMARY KEY (`id`),
  KEY `group_history_actor_id_IDX` (`actor_id`) USING BTREE,
  KEY `group_history_member_id_IDX` (`member_id`) USING BTREE,
  KEY `group_history_group_id_IDX` (`group_id`) USING BTREE,
  KEY `group_history_event_timestamp_IDX` (`event_timestamp`) USING BTREE,
  KEY `group_history_action_type_IDX` (`action_type`) USING BTREE,
  CONSTRAINT `group_history_actor_FK` FOREIGN KEY (`actor_id`) REFERENCES `profile` (`id`),
  CONSTRAINT `group_history_group_FK` FOREIGN KEY (`group_id`) REFERENCES `groups` (`id`),
  CONSTRAINT `group_history_member_FK` FOREIGN KEY (`member_id`) REFERENCES `profile` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2206 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Table to store history of group membership changes from Active Directory';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `groups`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `groups` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT COMMENT 'id is auto increment primary key.',
  `name` varchar(255) NOT NULL COMMENT 'The name of the group',
  `dn` varchar(512) NOT NULL COMMENT 'The distinguished name (DN) of the group in Active Directory',
  `created` timestamp NOT NULL DEFAULT current_timestamp() COMMENT 'The timestamp when the record was created in our database',
  `object_guid` varchar(36) DEFAULT NULL COMMENT 'AD objectGUID, canonical lowercase hyphenated; NULL until backfilled',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, DISABLED or ARCHIVED',
  `archived_at` timestamp NULL DEFAULT NULL COMMENT 'When the group was archived; NULL unless status is ARCHIVED',
  `archived_by` varchar(100) DEFAULT NULL COMMENT 'Username of the member who archived the group; NULL unless status is ARCHIVED',
  `archive_reason` varchar(255) DEFAULT NULL COMMENT 'Why the group was archived; NULL unless status is ARCHIVED',
  `archive_run_id` bigint(20) DEFAULT NULL COMMENT 'Workflow run that archived the group; NULL unless status is ARCHIVED',
  `active_name` varchar(255) GENERATED ALWAYS AS (if(`status` = 'ARCHIVED',NULL,`name`)) STORED COMMENT 'name, or NULL when ARCHIVED; carries the unique index',
  `active_dn` varchar(512) GENERATED ALWAYS AS (if(`status` = 'ARCHIVED',NULL,`dn`)) STORED COMMENT 'dn, or NULL when ARCHIVED; carries the unique index',
  PRIMARY KEY (`id`),
  KEY `groups_name_IDX` (`name`),
  KEY `groups_dn_IDX` (`dn`),
  UNIQUE KEY `groups_active_name_UNQ` (`active_name`),
  UNIQUE KEY `groups_active_dn_UNQ` (`active_dn`),
  UNIQUE KEY `groups_object_guid_UNQ` (`object_guid`)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Reference table for groups in Active Directory';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `profile`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `profile` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT COMMENT '_id is autoincrement id assigned to a profile, this column is used as Foreign Key in other tables. First 100 are reserved for system profiles. primary key',
  `username` varchar(100) NOT NULL COMMENT 'DMS member username, unique',
  `discourse_username` varchar(100) DEFAULT NULL COMMENT 'The discourse username associated with the member, unique',
  `discourse_avatar_url` varchar(2083) DEFAULT NULL COMMENT 'Discourse avatar url',
  `discord_userid` varchar(100) DEFAULT NULL COMMENT 'The discord userid associated with the member, unique',
  `discord_username` varchar(100) DEFAULT NULL COMMENT 'The discord username associated with the member',
  `discord_avatar_url` varchar(2083) DEFAULT NULL COMMENT 'Discord avatar url',
  `discord_webhook_id` varchar(100) DEFAULT NULL COMMENT 'Discord webhook ID for server role sync verification',
  `profile_photo_key` varchar(512) DEFAULT NULL COMMENT 'Object-store key of the member''s uploaded profile photo',
  `profile_photo_updated_at` datetime DEFAULT NULL COMMENT 'UTC timestamp of the last profile photo upload',
  `attributes` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Misc attributes' CHECK (json_valid(`attributes`)),
  `created` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00' ON UPDATE current_timestamp(),
  `is_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT 'Represents if the member account was enabled when it was last fetched from AD',
  PRIMARY KEY (`id`),
  UNIQUE KEY `profile_username_unique` (`username`),
  UNIQUE KEY `profile_discourse_username_unique` (`discourse_username`),
  UNIQUE KEY `profile_discord_userid_unique` (`discord_userid`),
  KEY `profile_isEnabled_IDX` (`is_enabled`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=1694 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Table to store basic profile level data for DMS members';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `short_links`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `short_links` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `namespace_id` int(10) unsigned DEFAULT NULL,
  `slug` varchar(100) NOT NULL,
  `redirect_type` varchar(20) NOT NULL,
  `destination_url` varchar(2048) NOT NULL,
  `description` text DEFAULT NULL,
  `creator_id` int(10) unsigned DEFAULT NULL,
  `updated_by` int(10) unsigned DEFAULT NULL,
  `is_active` tinyint(1) DEFAULT 1,
  `created_at` timestamp NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `namespace_id` (`namespace_id`,`slug`),
  KEY `namespace_id_2` (`namespace_id`,`is_active`,`slug`),
  KEY `creator_id` (`creator_id`),
  KEY `updated_by` (`updated_by`),
  KEY `is_active` (`is_active`),
  KEY `redirect_type` (`redirect_type`),
  CONSTRAINT `short_links_ibfk_1` FOREIGN KEY (`namespace_id`) REFERENCES `short_links_namespaces` (`id`) ON DELETE CASCADE,
  CONSTRAINT `short_links_ibfk_2` FOREIGN KEY (`creator_id`) REFERENCES `profile` (`id`) ON DELETE SET NULL,
  CONSTRAINT `short_links_ibfk_3` FOREIGN KEY (`updated_by`) REFERENCES `profile` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `short_links_clicks`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `short_links_clicks` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `short_link_id` int(10) unsigned NOT NULL,
  `member_id` int(10) unsigned DEFAULT NULL,
  `resolved_path` varchar(255) DEFAULT NULL,
  `variable_values` text DEFAULT NULL,
  `clicked_at` timestamp NULL DEFAULT current_timestamp(),
  `ip_address` varchar(45) DEFAULT NULL,
  `user_agent` varchar(500) DEFAULT NULL,
  `referrer` varchar(2048) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `short_link_id` (`short_link_id`,`clicked_at`),
  KEY `clicked_at` (`clicked_at`),
  KEY `member_id` (`member_id`),
  CONSTRAINT `short_links_clicks_ibfk_1` FOREIGN KEY (`short_link_id`) REFERENCES `short_links` (`id`) ON DELETE CASCADE,
  CONSTRAINT `short_links_clicks_ibfk_2` FOREIGN KEY (`member_id`) REFERENCES `profile` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `short_links_namespace_aliases`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `short_links_namespace_aliases` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `namespace_id` int(10) unsigned NOT NULL,
  `alias` varchar(20) NOT NULL,
  `is_primary` tinyint(1) DEFAULT 0,
  `created_at` timestamp NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `alias` (`alias`),
  KEY `namespace_id` (`namespace_id`),
  CONSTRAINT `short_links_namespace_aliases_ibfk_1` FOREIGN KEY (`namespace_id`) REFERENCES `short_links_namespaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `short_links_namespaces`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `short_links_namespaces` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `owner_type` varchar(20) NOT NULL,
  `owner_group_id` int(10) unsigned DEFAULT NULL,
  `description` text DEFAULT NULL,
  `is_active` tinyint(1) DEFAULT 1,
  `created_at` timestamp NULL DEFAULT current_timestamp(),
  `created_by` int(10) unsigned DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `name` (`name`),
  KEY `owner_group_id` (`owner_group_id`),
  KEY `created_by` (`created_by`),
  KEY `is_active` (`is_active`),
  CONSTRAINT `short_links_namespaces_ibfk_1` FOREIGN KEY (`owner_group_id`) REFERENCES `groups` (`id`) ON DELETE SET NULL,
  CONSTRAINT `short_links_namespaces_ibfk_2` FOREIGN KEY (`created_by`) REFERENCES `profile` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `short_links_reserved_aliases`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `short_links_reserved_aliases` (
  `alias` varchar(20) NOT NULL,
  `reason` varchar(255) DEFAULT NULL,
  `created_at` timestamp NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`alias`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping routines for database 'member_profile'
--
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

--
-- Table structure for table `ask_ai_cache`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ask_ai_cache` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT COMMENT 'id is auto increment primary key.',
  `slug` varchar(100) NOT NULL COMMENT 'URL-friendly slug generated from question text for shareable URLs',
  `question_hash` varchar(64) NOT NULL COMMENT 'SHA-256 hash of the normalized (lowercase, trimmed) question text for fast exact-match lookups',
  `question_text` text NOT NULL COMMENT 'The original question text as submitted by the user',
  `answer_text` text NOT NULL COMMENT 'The AI-generated answer text in markdown format',
  `source_links` text NOT NULL COMMENT 'JSON array of source links used to generate the answer, format: [{title, url, source}]',
  `asked_by_profile_id` int(10) unsigned NOT NULL COMMENT 'Profile ID of the member who originally asked this question (FK to profile.id)',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp() COMMENT 'The timestamp when this cache entry was created',
  `hit_count` int(10) unsigned NOT NULL DEFAULT 0 COMMENT 'Number of times this cached answer was returned (for popularity tracking)',
  `last_hit_at` timestamp NULL DEFAULT NULL COMMENT 'The timestamp when this cached answer was last returned',
  `metadata` text DEFAULT NULL COMMENT 'JSON serialized metadata about the AI response generation (token usage, cost, model name, etc.)',
  `total_cost_usd` decimal(10,6) DEFAULT NULL COMMENT 'Total estimated cost in USD for generating this response (sum of classification + answer generation)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `ask_ai_cache_slug_UNQ` (`slug`),
  KEY `ask_ai_cache_question_hash_IDX` (`question_hash`) USING BTREE,
  KEY `ask_ai_cache_asked_by_profile_id_IDX` (`asked_by_profile_id`) USING BTREE,
  CONSTRAINT `ask_ai_cache_profile_FK` FOREIGN KEY (`asked_by_profile_id`) REFERENCES `profile` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Cache table for Ask DMS AI question-answer pairs to reduce LLM API calls';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ask_ai_feedback`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ask_ai_feedback` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT COMMENT 'id is auto increment primary key.',
  `cache_id` int(10) unsigned NOT NULL COMMENT 'Foreign key to the ask_ai_cache entry being rated',
  `member_id` int(10) unsigned NOT NULL COMMENT 'The member ID who provided the feedback',
  `is_helpful` tinyint(1) NOT NULL COMMENT 'Whether the member found the answer helpful (1=yes, 0=no)',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp() COMMENT 'The timestamp when the feedback was submitted',
  PRIMARY KEY (`id`),
  KEY `ask_ai_feedback_cache_id_IDX` (`cache_id`) USING BTREE,
  KEY `ask_ai_feedback_member_id_IDX` (`member_id`) USING BTREE,
  CONSTRAINT `ask_ai_feedback_cache_FK` FOREIGN KEY (`cache_id`) REFERENCES `ask_ai_cache` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Feedback table for tracking member ratings of Ask DMS AI answers';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `group_settings`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `group_settings` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT COMMENT 'id is auto increment primary key.',
  `group_id` int(10) unsigned NOT NULL COMMENT 'The id of the group this setting belongs to',
  `setting_key` varchar(100) NOT NULL COMMENT 'The key identifying the setting',
  `setting_value` longtext DEFAULT NULL COMMENT 'The serialized value of the setting; NULL records a cleared setting',
  `updated_by` int(10) unsigned DEFAULT NULL COMMENT 'The id of the member who made the change',
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp() COMMENT 'The timestamp when this setting was last updated',
  PRIMARY KEY (`id`),
  KEY `group_settings_group_id_IDX` (`group_id`) USING BTREE,
  KEY `group_settings_setting_key_IDX` (`setting_key`) USING BTREE,
  KEY `group_settings_updated_by_IDX` (`updated_by`) USING BTREE,
  CONSTRAINT `group_settings_group_FK` FOREIGN KEY (`group_id`) REFERENCES `groups` (`id`),
  CONSTRAINT `group_settings_updated_by_FK` FOREIGN KEY (`updated_by`) REFERENCES `profile` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Per-group settings (append-only audit trail). Framework only — see GroupSettingsRegistry for what each key gates.';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `member_settings`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_settings` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT COMMENT 'id is auto increment primary key.',
  `profile_id` int(10) unsigned NOT NULL COMMENT 'The id of the member profile this setting belongs to',
  `setting_key` varchar(100) NOT NULL COMMENT 'The key identifying the setting',
  `setting_value` longtext DEFAULT NULL COMMENT 'The serialized value of the setting; NULL records a cleared setting',
  `updated_by` int(10) unsigned DEFAULT NULL COMMENT 'The id of the member who made the change',
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp() COMMENT 'The timestamp when this setting was last updated',
  PRIMARY KEY (`id`),
  KEY `member_settings_profile_id_IDX` (`profile_id`) USING BTREE,
  KEY `member_settings_updated_by_IDX` (`updated_by`) USING BTREE,
  CONSTRAINT `member_settings_profile_FK` FOREIGN KEY (`profile_id`) REFERENCES `profile` (`id`),
  CONSTRAINT `member_settings_updated_by_FK` FOREIGN KEY (`updated_by`) REFERENCES `profile` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Per-member settings and preferences (append-only audit trail)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `notifications`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notifications` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'id is auto increment primary key.',
  `profile_id` int(10) unsigned NOT NULL COMMENT 'The id of the member profile the notification is for (recipient)',
  `topic` varchar(64) NOT NULL COMMENT 'The notification topic (app feature) that produced this notification, e.g. EVENT_ANNOUNCEMENT, ACCOUNT, SYSTEM',
  `channel` varchar(32) NOT NULL COMMENT 'The delivery channel: EMAIL, SMS, DISCORD, DISCOURSE',
  `aggregation` varchar(16) NOT NULL COMMENT 'How the notification is delivered: IMMEDIATE, DAILY or WEEKLY (digest)',
  `template_name` varchar(128) NOT NULL COMMENT 'The name of the code-defined template used to render this notification',
  `payload` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'JSON template data used to render this notification' CHECK (json_valid(`payload`)),
  `subject` varchar(512) NOT NULL COMMENT 'Rendered subject line; used as the message body for short channels (SMS/Discord)',
  `body` longtext DEFAULT NULL COMMENT 'Rendered full body (HTML for email)',
  `status` varchar(32) NOT NULL COMMENT 'Lifecycle status: QUEUED, IN_DIGEST, SENDING, SENT, FAILED, DEAD_LETTER, SUPPRESSED, SKIPPED',
  `attempts` int(11) NOT NULL DEFAULT 0 COMMENT 'Number of send attempts made so far',
  `next_attempt_at` timestamp NULL DEFAULT NULL COMMENT 'Earliest time the worker may retry this notification (NULL = retry immediately)',
  `dedupe_key` varchar(255) DEFAULT NULL COMMENT 'Caller-supplied idempotency key; unique when present',
  `provider_id` varchar(128) DEFAULT NULL COMMENT 'Provider-side id (e.g. SparkPost transmission id) for event correlation',
  `last_error` text DEFAULT NULL COMMENT 'Human-readable description of the most recent failure',
  `sent_at` timestamp NULL DEFAULT NULL COMMENT 'When the notification was accepted by the provider',
  `read_at` timestamp NULL DEFAULT NULL COMMENT 'When the member read the notification (inbox)',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp() COMMENT 'When the notification was created',
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp() COMMENT 'When the notification was last updated',
  PRIMARY KEY (`id`),
  UNIQUE KEY `notifications_dedupe_key_UNQ` (`dedupe_key`),
  KEY `notifications_profile_id_IDX` (`profile_id`) USING BTREE,
  KEY `notifications_status_next_attempt_IDX` (`status`,`next_attempt_at`) USING BTREE,
  KEY `notifications_created_at_IDX` (`created_at`) USING BTREE,
  CONSTRAINT `notifications_profile_FK` FOREIGN KEY (`profile_id`) REFERENCES `profile` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Notification outbox and unified communication log.';

--
-- Table structure for table `notification_preferences`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notification_preferences` (
  `profile_id` int(10) unsigned NOT NULL COMMENT 'The id of the member profile this preference belongs to',
  `topic` varchar(64) NOT NULL COMMENT 'The notification topic this preference applies to',
  `channel` varchar(32) NOT NULL COMMENT 'The delivery channel this preference applies to',
  `enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT 'Whether notifications of this topic/channel are enabled for the member',
  `aggregation` varchar(16) DEFAULT NULL COMMENT 'Member override for how notifications are delivered; NULL = channel default',
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp() COMMENT 'When this preference was last updated',
  PRIMARY KEY (`profile_id`,`topic`,`channel`),
  CONSTRAINT `notification_preferences_profile_FK` FOREIGN KEY (`profile_id`) REFERENCES `profile` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Per-member notification preferences (opt-in/opt-out and aggregation override)';

--
-- Table structure for table `notification_suppressions`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notification_suppressions` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'id is auto increment primary key.',
  `profile_id` int(10) unsigned NOT NULL COMMENT 'The id of the suppressed member profile',
  `email` varchar(255) DEFAULT NULL COMMENT 'The email address that bounced/unsubscribed, when known',
  `reason` varchar(32) NOT NULL COMMENT 'Suppression reason: BOUNCE or UNSUBSCRIBE',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp() COMMENT 'When the suppression was recorded',
  PRIMARY KEY (`id`),
  KEY `notification_suppressions_profile_id_IDX` (`profile_id`) USING BTREE,
  CONSTRAINT `notification_suppressions_profile_FK` FOREIGN KEY (`profile_id`) REFERENCES `profile` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Members suppressed from receiving notifications (hard bounce or unsubscribe); mirrored from the provider';

--
-- Table structure for table `profile_subscriptions`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `profile_subscriptions` (
  `profile_id` int(10) unsigned NOT NULL COMMENT 'The id of the subscribing (follower) member profile',
  `followed_profile_id` int(10) unsigned NOT NULL COMMENT 'The id of the member profile being followed',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp() COMMENT 'When the subscription was created',
  PRIMARY KEY (`profile_id`,`followed_profile_id`),
  KEY `profile_subscriptions_followed_profile_id_IDX` (`followed_profile_id`) USING BTREE,
  CONSTRAINT `profile_subscriptions_profile_FK` FOREIGN KEY (`profile_id`) REFERENCES `profile` (`id`),
  CONSTRAINT `profile_subscriptions_followed_profile_FK` FOREIGN KEY (`followed_profile_id`) REFERENCES `profile` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Member-to-member follow relationships, e.g. follow an event organizer to be notified when they post a new event.';

--
-- Table structure for table `committee_subscriptions`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `committee_subscriptions` (
  `profile_id` int(10) unsigned NOT NULL COMMENT 'The id of the subscribing (follower) member profile',
  `committee_id` int(11) NOT NULL COMMENT 'Committee.id from common-models (stable; never reused), not the slug',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp() COMMENT 'When the subscription was created',
  PRIMARY KEY (`profile_id`,`committee_id`),
  KEY `committee_subscriptions_committee_id_IDX` (`committee_id`) USING BTREE,
  CONSTRAINT `committee_subscriptions_profile_FK` FOREIGN KEY (`profile_id`) REFERENCES `profile` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Members following a committee, e.g. to be notified when it adds a class.';

--
-- Table structure for table `workflow_run`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `workflow_run` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'id is auto increment primary key.',
  `workflow_id` varchar(100) NOT NULL COMMENT 'Code-defined workflow id, e.g. group.archive',
  `workflow_version` int(10) unsigned NOT NULL COMMENT 'Workflow definition version at the time the run started',
  `status` varchar(32) NOT NULL COMMENT 'WorkflowRunStatus: PENDING, RUNNING, WAITING_RETRY, PAUSED, ROLLING_BACK, ROLLBACK_FAILED, SUCCEEDED, ROLLED_BACK, ABANDONED, CANCELLED',
  `input_json` text NOT NULL COMMENT 'Serialized workflow input; never secrets or bulk PII',
  `actor` varchar(100) NOT NULL COMMENT 'Username of the member (or system actor) that started the run',
  `triggered_by` varchar(32) NOT NULL COMMENT 'UI, API, CRON or WEBHOOK',
  `current_step_index` int(10) unsigned NOT NULL DEFAULT 0 COMMENT 'Index into step_ids_json of the step being executed',
  `step_ids_json` text NOT NULL COMMENT 'JSON array of the ordered step ids captured at start, so a deploy cannot change an in-flight run',
  `locked_by` varchar(100) DEFAULT NULL COMMENT 'Service instance id holding the lease',
  `lease_gen` bigint(20) unsigned NOT NULL DEFAULT 0 COMMENT 'Fence token; incremented on every claim and operator transition',
  `lease_until` datetime DEFAULT NULL COMMENT 'UTC database-clock time the lease expires; NULL or past means claimable',
  `heartbeat_at` datetime DEFAULT NULL COMMENT 'UTC database-clock time of the last claim or heartbeat',
  `next_attempt_at` datetime DEFAULT NULL COMMENT 'UTC time the run is next due (retry backoff); NULL means due now',
  `idempotency_token` varchar(128) NOT NULL COMMENT 'Client supplied token for the start request',
  `idempotency_key` char(64) NOT NULL COMMENT 'SHA-256 of workflow id, normalized subjects and token; unique',
  `cancel_requested` tinyint(1) NOT NULL DEFAULT 0 COMMENT 'Cooperative cancel flag read by the executor between steps',
  `created_at` datetime NOT NULL COMMENT 'UTC timestamp the run was created',
  `started_at` datetime DEFAULT NULL COMMENT 'UTC timestamp execution first started',
  `finished_at` datetime DEFAULT NULL COMMENT 'UTC timestamp the run reached a terminal state',
  `updated_at` datetime NOT NULL COMMENT 'UTC timestamp of the last write',
  `error_summary` text DEFAULT NULL COMMENT 'Short description of the failure that paused or ended the run',
  PRIMARY KEY (`id`),
  UNIQUE KEY `workflow_run_idempotency_key_UNQ` (`idempotency_key`),
  KEY `workflow_run_status_next_attempt_IDX` (`status`,`next_attempt_at`) USING BTREE,
  KEY `workflow_run_status_lease_IDX` (`status`,`lease_until`) USING BTREE,
  KEY `workflow_run_workflow_status_IDX` (`workflow_id`,`status`) USING BTREE,
  KEY `workflow_run_actor_IDX` (`actor`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Workflow runs. See docs/features/workflows/README.md; all lease timing uses the database clock.';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `workflow_step_run`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `workflow_step_run` (
  `run_id` bigint(20) unsigned NOT NULL COMMENT 'The workflow_run this step belongs to',
  `step_index` int(10) unsigned NOT NULL COMMENT 'Position of the step within the run',
  `step_id` varchar(100) NOT NULL COMMENT 'Stable step id, e.g. ad.delete-group',
  `status` varchar(32) NOT NULL COMMENT 'WorkflowStepStatus; RUNNING is the STARTED record committed before the step executes',
  `attempts` int(10) unsigned NOT NULL DEFAULT 0 COMMENT 'Number of times the step was started',
  `output_json` text DEFAULT NULL COMMENT 'Serialized step output used by later steps and rollback; never secrets or bulk PII',
  `output_summary` varchar(500) DEFAULT NULL COMMENT 'Short human-readable result shown in the timeline',
  `error` text DEFAULT NULL COMMENT 'Last failure message',
  `started_at` datetime DEFAULT NULL COMMENT 'UTC timestamp the latest attempt started',
  `finished_at` datetime DEFAULT NULL COMMENT 'UTC timestamp the step reached a finished state',
  PRIMARY KEY (`run_id`,`step_index`),
  CONSTRAINT `workflow_step_run_run_FK` FOREIGN KEY (`run_id`) REFERENCES `workflow_run` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Per-step state of a workflow run';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `workflow_run_subject`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `workflow_run_subject` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'id is auto increment primary key.',
  `run_id` bigint(20) unsigned NOT NULL COMMENT 'The workflow_run holding (or that held) the subject',
  `subject_type` varchar(64) NOT NULL COMMENT 'Normalized (lowercase, trimmed) subject type, e.g. group',
  `subject_key` varchar(255) NOT NULL COMMENT 'Normalized (lowercase, trimmed) subject key, e.g. the group name',
  `active_key` tinyint(3) unsigned DEFAULT NULL COMMENT '1 while the run holds the lock, NULL once it stops; the unique key ignores NULLs so only active runs conflict',
  PRIMARY KEY (`id`),
  UNIQUE KEY `workflow_run_subject_active_UNQ` (`subject_type`,`subject_key`,`active_key`),
  KEY `workflow_run_subject_run_id_IDX` (`run_id`) USING BTREE,
  CONSTRAINT `workflow_run_subject_run_FK` FOREIGN KEY (`run_id`) REFERENCES `workflow_run` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Subjects of workflow runs; at most one active run per subject';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `workflow_event`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `workflow_event` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'Append-only; also the SSE event id',
  `run_id` bigint(20) unsigned NOT NULL COMMENT 'The workflow_run the event belongs to',
  `step_index` int(10) unsigned DEFAULT NULL COMMENT 'Step the event is about, NULL for run-level events',
  `ts` datetime NOT NULL COMMENT 'UTC database-clock timestamp',
  `level` varchar(16) NOT NULL COMMENT 'INFO, WARN or ERROR',
  `message` text NOT NULL COMMENT 'Log or audit message; never secrets or bulk PII',
  `actor` varchar(100) DEFAULT NULL COMMENT 'Username that triggered the event (operator actions), if any',
  PRIMARY KEY (`id`),
  KEY `workflow_event_run_id_IDX` (`run_id`,`id`) USING BTREE,
  CONSTRAINT `workflow_event_run_FK` FOREIGN KEY (`run_id`) REFERENCES `workflow_run` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Append-only run log and audit trail';
/*!40101 SET character_set_client = @saved_cs_client */;

-- Dump completed on 2025-12-31 21:13:59
