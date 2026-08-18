package org.dallasmakerspace.members

/** Thrown when linking a Discourse/Discord account already linked to a different active member. */
class ExternalAccountAlreadyLinkedException(message: String) : Exception(message)
