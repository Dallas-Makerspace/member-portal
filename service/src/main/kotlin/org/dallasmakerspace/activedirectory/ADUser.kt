package org.dallasmakerspace.activedirectory

data class ADUser(
    val sAMAccountName: String,
    val mail: String,
    val givenName: String?,
    val sn: String?,
    val telephoneNumber: String?,
    val employeeID: String?,
    val displayName: String?,
    val objectGuid: String,
    val whenCreated: String?,
    val enabled: Boolean,
    val groups: List<ADGroup>,
    // Defaults to "" rather than being required, so the many pre-existing test fixtures that
    // construct ADUser directly (unrelated to group-admin checks) don't need updating. Real
    // callers (ActiveDirectoryService) always populate it from AD.
    val distinguishedName: String = "",
)
