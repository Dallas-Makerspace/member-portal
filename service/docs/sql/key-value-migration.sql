-- Generic key/value store: small durable state addressed by (namespace, key), e.g. which Moodle
-- courses have already been announced. Not for high-volume or queried data.
-- Run against the Member Portal database (the one that holds `profile`). Re-runnable. Apply with
-- the file on stdin so it stops at the first error, e.g.:
--   mariadb -h <member-portal db host> -u <user> -p member_profile < key-value-migration.sql

CREATE TABLE IF NOT EXISTS `key_value` (
  `namespace` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'Feature the value belongs to, e.g. moodle-course-announced',
  `item_key` varchar(190) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'Key within the namespace, case-sensitive',
  `item_value` text NOT NULL COMMENT 'The value, at most 65535 bytes',
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp() COMMENT 'When the value was last written',
  PRIMARY KEY (`namespace`,`item_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Generic key/value store for small durable state.';
