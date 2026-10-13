package org.dallasmakerspace.activedirectory

import java.io.IOException

open class ADException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A lookup of a group by name found nothing. */
class GroupNotFoundException(val groupName: String) :
    ADException("Group $groupName not found in AD")
