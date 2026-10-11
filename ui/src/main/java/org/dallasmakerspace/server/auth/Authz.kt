package org.dallasmakerspace.server.auth

class Authz(private val permissions: Set<Permission>) {
  fun can(permissionName: String): Boolean = permissions.any { it.name == permissionName }

  companion object {
    /** Same mapping AuthenticatedHandler applies: Keycloak groups -> roles -> permissions. */
    fun fromUserInfo(userInfo: Map<String, Any>): Authz {
      val groups = (userInfo["groups"] as? List<*>) ?: emptyList<String>()
      return Authz(Role.fromKeycloakGroups(groups).flatMap { it.permissions }.toSet())
    }
  }
}
