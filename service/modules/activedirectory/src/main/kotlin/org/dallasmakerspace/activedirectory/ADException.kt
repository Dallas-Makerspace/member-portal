package org.dallasmakerspace.activedirectory

import java.io.IOException

open class ADException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** More than one group with this name exists in the group OUs, so the name is not an identity. */
class AmbiguousGroupException(val groupName: String, val count: Int) :
    ADException("$count groups named $groupName exist in the group locations")

/** No group has the expected objectGUID, so the group the caller meant is gone or was replaced. */
class GroupGuidNotFoundException(val groupName: String) :
    ADException("The group $groupName with the expected identity was not found")

/** A group membership change was refused because a group is outside the main group OU. */
class GroupMembershipRefusedException(val groupName: String, reason: String) :
    ADException("Refusing to change membership of group $groupName: $reason")

/** A lookup of a group by name found nothing. */
class GroupNotFoundException(val groupName: String) :
    ADException("Group $groupName not found in AD")
