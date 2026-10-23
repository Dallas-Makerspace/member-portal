-- Bambu printer notifications: one-shot print watches, held printer-offline committee alerts,
-- and committee-alert subscriptions.
-- Run against the Member Portal database (the one that holds `profile`).
-- Re-runnable. Apply with the file on stdin so it stops at the first error, e.g.:
--   mariadb -h <member-portal db host> -u <user> -p member_profile < bambu-notifications-migration.sql

CREATE TABLE IF NOT EXISTS `bambu_ephemeral_notifications` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `profile_id` int(10) unsigned NOT NULL COMMENT 'Watch owner; FK to profile.id',
  `scope` varchar(32) NOT NULL COMMENT 'OWN_JOB or PRINTER_AVAILABLE',
  `printer_id` int(11) NOT NULL COMMENT 'BamBuddy printer id',
  `printer_name` varchar(128) NOT NULL COMMENT 'Printer name snapshot at arm time',
  `queue_id` int(11) DEFAULT NULL COMMENT 'OWN_JOB: the watched queue item. PRINTER_AVAILABLE: the blocking (currently printing) queue item',
  `filename` varchar(512) DEFAULT NULL COMMENT 'Queue archive_name snapshot; correlation key with the webhook payload',
  `job_name` varchar(512) DEFAULT NULL COMMENT 'Display name for emails (same value as filename in v1)',
  `trigger_events` varchar(128) NOT NULL COMMENT 'Comma-separated BamBuddy event types that can fire this watch',
  `status` varchar(16) NOT NULL COMMENT 'ARMED, FIRED, EXPIRED, CANCELLED',
  `rearm_count` int(11) NOT NULL DEFAULT 0 COMMENT '0 or 1; max 1 re-arm',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `expires_at` timestamp NOT NULL COMMENT 'Current TTL deadline (UTC); rewritten on re-arm',
  `terminal_at` timestamp NULL DEFAULT NULL COMMENT 'When status left ARMED',
  `fired_notification_id` bigint(20) unsigned DEFAULT NULL COMMENT 'notifications.id produced on fire / overdue / expiry notice',
  `terminal_reason` varchar(255) DEFAULT NULL COMMENT 'Human-readable resolution note for support/debugging',
  PRIMARY KEY (`id`),
  KEY `bambu_watches_status_expires_IDX` (`status`,`expires_at`),
  KEY `bambu_watches_profile_status_IDX` (`profile_id`,`status`),
  KEY `bambu_watches_printer_status_IDX` (`printer_id`,`status`),
  CONSTRAINT `bambu_watches_profile_FK` FOREIGN KEY (`profile_id`) REFERENCES `profile` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='One-shot Bambu print watches (EphemeralNotification); terminal rows are tombstones purged after retention.';

CREATE TABLE IF NOT EXISTS `bambu_pending_offline_alerts` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `printer_id` int(11) NOT NULL,
  `printer_name` varchar(128) NOT NULL,
  `offline_since` timestamp NOT NULL COMMENT 'Portal receipt time (UTC) of the first printer_offline webhook of this episode',
  `release_at` timestamp NOT NULL COMMENT 'offline_since + hold (default 5 min); sweeper acts at/after this',
  `status` varchar(16) NOT NULL COMMENT 'PENDING, PROCESSING, SENT, SUPPRESSED',
  `resolved_at` timestamp NULL DEFAULT NULL,
  `notifications_created` int(11) DEFAULT NULL COMMENT 'Fan-out result when SENT (0 is valid: no subscribers)',
  PRIMARY KEY (`id`),
  KEY `bambu_offline_status_release_IDX` (`status`,`release_at`),
  KEY `bambu_offline_printer_IDX` (`printer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Held printer-offline committee alerts awaiting the still-offline re-check.';

CREATE TABLE IF NOT EXISTS `bambu_committee_subscriptions` (
  `profile_id` int(10) unsigned NOT NULL COMMENT 'The subscribing member profile',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`profile_id`),
  CONSTRAINT `bambu_committee_subs_profile_FK` FOREIGN KEY (`profile_id`) REFERENCES `profile` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Members subscribed to Bambu committee alerts (printer offline).';
