-- Committee follow: members subscribe to a committee to be notified about its new classes.
-- Run against the Member Portal database (the one that holds `profile` and `profile_subscriptions`).
-- Re-runnable. Apply with the file on stdin so it stops at the first error, e.g.:
--   mariadb -h <member-portal db host> -u <user> -p member_profile < committee-subscriptions-migration.sql

CREATE TABLE IF NOT EXISTS `committee_subscriptions` (
  `profile_id` int(10) unsigned NOT NULL COMMENT 'The id of the subscribing (follower) member profile',
  `committee_id` int(11) NOT NULL COMMENT 'Committee.id from common-models (stable, never reused), not the slug',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp() COMMENT 'When the subscription was created',
  PRIMARY KEY (`profile_id`,`committee_id`),
  KEY `committee_subscriptions_committee_id_IDX` (`committee_id`) USING BTREE,
  CONSTRAINT `committee_subscriptions_profile_FK` FOREIGN KEY (`profile_id`) REFERENCES `profile` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Members following a committee, e.g. to be notified when it adds a class.';
