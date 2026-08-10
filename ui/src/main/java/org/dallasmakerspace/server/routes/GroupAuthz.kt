package org.dallasmakerspace.server.routes

/**
 * True if the actor may manage a group's membership: either they hold the site-wide
 * MANAGE_GROUPS permission (Officer/Board/Infra), or they are a (possibly nested, up to
 * [org.dallasmakerspace.server.memberservice.MemberServiceClient.isGroupAdmin]'s depth cap) AD
 * member of one of the group's own `administrators` entries.
 */
fun canManageGroup(hasManageGroupsPermission: Boolean, isGroupAdmin: Boolean): Boolean =
    hasManageGroupsPermission || isGroupAdmin
