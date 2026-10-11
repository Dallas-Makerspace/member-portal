-- Schema for the workflows framework (docs/features/workflows/README.md). create.sql (the
-- reference DDL dump) has already been updated to match; this is the script to run by hand against
-- the prod member_profile database. Purely additive (four new tables) and safe to re-run. Nothing
-- reads the tables until the `workflows.enabled` feature flag is switched on.

-- Step 1: returns no rows the first time (tables not created yet).
SHOW TABLES LIKE 'workflow\_%';

-- Step 2: create the tables, parent first (the others have foreign keys to workflow_run).
--
-- Table structure for table `workflow_run`
--

CREATE TABLE IF NOT EXISTS `workflow_run` (
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

--
-- Table structure for table `workflow_step_run`
--

CREATE TABLE IF NOT EXISTS `workflow_step_run` (
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

--
-- Table structure for table `workflow_run_subject`
--

CREATE TABLE IF NOT EXISTS `workflow_run_subject` (
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

--
-- Table structure for table `workflow_event`
--

CREATE TABLE IF NOT EXISTS `workflow_event` (
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

-- Step 3: must return four rows: workflow_event, workflow_run, workflow_run_subject, workflow_step_run.
SHOW TABLES LIKE 'workflow\_%';

-- Step 4: the unique keys the engine depends on must exist: idempotency_key on workflow_run, and
-- (subject_type, subject_key, active_key) on workflow_run_subject, plus the primary keys.
SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'workflow\_%' AND NON_UNIQUE = 0
GROUP BY TABLE_NAME, INDEX_NAME;

-- Step 5: lease timing uses the database clock via UTC_TIMESTAMP(); confirm it reads as UTC.
SELECT UTC_TIMESTAMP() AS db_utc_now, NOW() AS db_session_now, @@global.time_zone, @@session.time_zone;

-- Rollback (children first; only sensible while no run has ever been created):
-- DROP TABLE `workflow_event`;
-- DROP TABLE `workflow_run_subject`;
-- DROP TABLE `workflow_step_run`;
-- DROP TABLE `workflow_run`;
