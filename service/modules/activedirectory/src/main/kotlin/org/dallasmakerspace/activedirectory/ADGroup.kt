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
)
