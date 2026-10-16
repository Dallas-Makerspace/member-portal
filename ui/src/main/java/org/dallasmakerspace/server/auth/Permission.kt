package org.dallasmakerspace.server.auth

enum class Permission {
  MANAGE_MEMBERS,
  MANAGE_GROUPS,
  MANAGE_BADGES,
  MANAGE_SHORTLINKS,
  MANAGE_CONFIG,
  EXECUTE_CRON,
  USE_ASKAI,
  MANAGE_REMOTE_ACCESS,
  /** Infra-only (roles holding every permission): download archived group member lists. */
  DOWNLOAD_ARCHIVES,
  /** Infra-only (roles holding every permission): archive any group, not just ones administered. */
  ARCHIVE_ANY_GROUP,
}
