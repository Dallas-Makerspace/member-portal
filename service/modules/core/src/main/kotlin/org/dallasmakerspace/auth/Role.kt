package org.dallasmakerspace.auth

enum class Role(val permissions: Set<Permission>) {
  // Organizational roles (mapped from Keycloak groups on the UI side)
  UI_SUPERUSER(Permission.entries.toSet()),
  INFRA(Permission.entries.toSet()),
  OFFICER(setOf(Permission.MANAGE_GROUPS, Permission.MANAGE_BADGES)),
  BOARD(setOf(Permission.MANAGE_CONFIG)),
  MEMBER(setOf(Permission.USE_ASKAI)),

  // Service-account roles (used by API clients in env vars)
  MEMBER_READER(setOf(Permission.MANAGE_MEMBERS)),
  BADGE_READER(setOf(Permission.MANAGE_BADGES)),
  CRON_EXECUTOR(setOf(Permission.EXECUTE_CRON));

  companion object {
    fun fromName(name: String): Role? = entries.find { it.name.equals(name, ignoreCase = true) }
  }
}
