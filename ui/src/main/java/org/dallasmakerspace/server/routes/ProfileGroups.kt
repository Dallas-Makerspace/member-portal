package org.dallasmakerspace.server.routes

import org.dallasmakerspace.models.DMSGroup
import org.dallasmakerspace.models.DirectoryOus

/** The groups listed on a profile: only those in the portal's group OUs, sorted by name. */
internal fun profileDisplayGroups(groups: List<DMSGroup>): List<DMSGroup> =
    groups.filter { DirectoryOus.isInGroupOu(it.distinguishedName) }.sortedBy { it.name }
