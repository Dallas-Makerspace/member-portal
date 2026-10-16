package org.dallasmakerspace.activedirectory

data class ADGroup(
    val cn: String,
    val description: String?,
    val distinguishedName: String,
    val objectGuid: String?,
    val members: List<ADUser>,
    val membersListIncomplete: Boolean,
    val administrators: List<String> = emptyList(),
    val nestedGroups: List<String> = emptyList(),
    /** Raw `member` attribute count AD reported, before truncation; null if not known. */
    val sourceMemberCount: Int? = null,
    /** User-looking member DNs the directory did not resolve to a user. */
    val unresolvedMemberCount: Int = 0,
    /** The `managedBy` DN alone (administrators also lists Domain Admins); null if unset. */
    val managedBy: String? = null,
    /** DNs of the groups this group is itself a member of (its `memberOf`). */
    val parentGroups: List<String> = emptyList(),
    /** The group's own sAMAccountName (may differ from [cn]); null if the directory gave none. */
    val samAccountName: String? = null,
    /** The raw AD `groupType` flags (scope and security bit); null if not known. */
    val groupType: Int? = null,
)
