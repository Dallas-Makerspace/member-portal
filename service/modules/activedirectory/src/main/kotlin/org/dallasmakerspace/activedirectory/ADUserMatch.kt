package org.dallasmakerspace.activedirectory

/** The few fields a name search returns; use [IActiveDirectoryService.getMemberByUsername] for the rest. */
data class ADUserMatch(val sAMAccountName: String, val displayName: String?, val enabled: Boolean)
