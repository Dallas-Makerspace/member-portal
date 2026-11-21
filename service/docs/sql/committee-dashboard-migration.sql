-- Committee TV dashboard: pages, widgets, announcements and the TVs to cast to.
-- Run against the Member Portal database (the one that holds `profile`).
-- Re-runnable. Apply with the file on stdin so it stops at the first error, e.g.:
--   mariadb -h <member-portal db host> -u <user> -p member_profile < committee-dashboard-migration.sql

CREATE TABLE IF NOT EXISTS `dashboard_pages` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `committee_id` int(11) NOT NULL COMMENT 'Committee.id from common-models (stable, never reused), not the slug',
  `position` int(11) NOT NULL COMMENT 'Order of the page in the rotation, from 0',
  `layout` varchar(24) NOT NULL COMMENT 'FULL, TWO_COL, HERO_PLUS_2 or GRID_2X2',
  `title` varchar(80) NOT NULL DEFAULT '',
  `duration_seconds` int(11) NOT NULL DEFAULT 20 COMMENT 'How long the page stays on screen',
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `updated_by` varchar(64) NOT NULL COMMENT 'Username of the last editor',
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() COMMENT 'UTC',
  PRIMARY KEY (`id`),
  KEY `dashboard_pages_committee_IDX` (`committee_id`,`position`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Pages of a committee TV dashboard.';

CREATE TABLE IF NOT EXISTS `dashboard_widgets` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `page_id` int(11) NOT NULL,
  `slot` int(11) NOT NULL COMMENT 'Slot of the page layout, from 0',
  `type` varchar(24) NOT NULL COMMENT 'GROUP_COUNT, UPCOMING_CLASSES or TEXT_IMAGE',
  `config` text NOT NULL COMMENT 'Widget settings as JSON',
  PRIMARY KEY (`id`),
  UNIQUE KEY `dashboard_widgets_page_slot_UQ` (`page_id`,`slot`),
  CONSTRAINT `dashboard_widgets_page_FK` FOREIGN KEY (`page_id`) REFERENCES `dashboard_pages` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Widgets on a dashboard page.';

CREATE TABLE IF NOT EXISTS `dashboard_announcements` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `committee_id` int(11) NOT NULL COMMENT 'Committee.id from common-models',
  `text` varchar(200) NOT NULL,
  `starts_at` timestamp NULL DEFAULT NULL COMMENT 'UTC; null = already started',
  `ends_at` timestamp NULL DEFAULT NULL COMMENT 'UTC; null = never ends',
  `created_by` varchar(64) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp() COMMENT 'UTC',
  PRIMARY KEY (`id`),
  KEY `dashboard_announcements_committee_IDX` (`committee_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Dashboard-only announcements for the bottom row.';

CREATE TABLE IF NOT EXISTS `cast_targets` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `committee_id` int(11) NOT NULL COMMENT 'Committee.id from common-models',
  `name` varchar(80) NOT NULL,
  `host` varchar(255) NOT NULL COMMENT 'Address of the TV on the internal network',
  `port` int(11) NOT NULL DEFAULT 8009,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `display_start` varchar(5) DEFAULT NULL COMMENT 'HH:mm America/Chicago; null with display_end = always on',
  `display_end` varchar(5) DEFAULT NULL COMMENT 'HH:mm America/Chicago',
  `reclaim_after_minutes` int(11) DEFAULT NULL COMMENT 'Take the TV back from another app after this long; null = never',
  `token_generation` int(11) NOT NULL DEFAULT 1 COMMENT 'Bumped to revoke tokens issued for this TV',
  `device_id` varchar(64) DEFAULT NULL COMMENT 'The TV''s own stable id (ssdp_udn), learned by the cast agent so it can find the TV if its address changes',
  `device_name` varchar(80) DEFAULT NULL COMMENT 'What the TV calls itself on the network (read-only in the editor)',
  `last_seen_at` timestamp NULL DEFAULT NULL COMMENT 'UTC; reported by the cast agent',
  `last_status` varchar(120) DEFAULT NULL COMMENT 'What the TV was last reported showing',
  PRIMARY KEY (`id`),
  KEY `cast_targets_committee_IDX` (`committee_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='TVs the cast agent shows a committee dashboard on.';

-- Added after the first release: lets the cast agent recognise a TV whose address changed.
ALTER TABLE `cast_targets`
  ADD COLUMN IF NOT EXISTS `device_id` varchar(64) DEFAULT NULL COMMENT 'The TV''s own stable id (ssdp_udn), learned by the cast agent so it can find the TV if its address changes' AFTER `token_generation`,
  ADD COLUMN IF NOT EXISTS `device_name` varchar(80) DEFAULT NULL COMMENT 'What the TV calls itself on the network (read-only in the editor)' AFTER `device_id`;

-- Cast devices the cast agent's network sweep has found, set up for a committee or not (TVs / Displays page).
CREATE TABLE IF NOT EXISTS `cast_devices` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `device_id` varchar(64) NOT NULL COMMENT 'The device''s own stable id (ssdp_udn), lower case',
  `name` varchar(120) DEFAULT NULL COMMENT 'What the device calls itself on the network',
  `host` varchar(64) NOT NULL COMMENT 'Last address it was seen at',
  `first_seen_at` timestamp NOT NULL DEFAULT current_timestamp() COMMENT 'UTC',
  `last_seen_at` timestamp NOT NULL DEFAULT current_timestamp() COMMENT 'UTC; updated by each sweep that finds it',
  PRIMARY KEY (`id`),
  UNIQUE KEY `cast_devices_device_id_UQ` (`device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Cast devices found on the network by the cast agent.';

-- What each device was doing at the last sweep (TVs / Displays page).
ALTER TABLE `cast_devices`
  ADD COLUMN IF NOT EXISTS `app_name` varchar(255) DEFAULT NULL COMMENT 'The app the device was running at the last sweep',
  ADD COLUMN IF NOT EXISTS `status_text` varchar(255) DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS `media_state` varchar(24) DEFAULT NULL COMMENT 'PLAYING, PAUSED, ...',
  ADD COLUMN IF NOT EXISTS `media_title` varchar(255) DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS `media_artist` varchar(255) DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS `media_url` varchar(1024) DEFAULT NULL COMMENT 'Web address of what is playing, when the app shares one',
  ADD COLUMN IF NOT EXISTS `media_type` varchar(80) DEFAULT NULL;
