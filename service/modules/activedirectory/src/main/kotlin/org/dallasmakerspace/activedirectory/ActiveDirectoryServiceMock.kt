package org.dallasmakerspace.activedirectory

import dagger.Reusable
import javax.inject.Inject

@Reusable
class ActiveDirectoryServiceMock @Inject constructor() : IActiveDirectoryService {

  /** {@inheritDoc} */
  override fun getMemberByUsername(username: String): ADUser =
      sampleMembersMap[username] ?: throw ADException("User not found")

  /** {@inheritDoc} */
  override fun getMembersByUsernameList(usernameList: List<String>): Map<String, ADUser> =
      sampleMembersMap

  /** {@inheritDoc} */
  override fun getMemberByBadgeNumber(badgeNumber: String): ADUser = sampleMembersMap[badgeNumber]!!

  /** {@inheritDoc} */
  override fun getMembersByBadgeNumberList(badgeNumberList: List<String>) = sampleMembersMap

  /** {@inheritDoc} */
  override fun getGroup(groupname: String): ADGroup {
    val groupMembers =
        sampleMembersMap.values.filter { user ->
          user.groups.any { it.cn.equals(groupname, ignoreCase = true) }
        }
    return ADGroup(
        cn = groupname,
        description = "Mock group description",
        distinguishedName = "CN=$groupname,OU=Groups,DC=dms,DC=local",
        objectGuid = "mock-guid",
        members = groupMembers,
        membersListIncomplete = false,
        administrators = emptyList())
  }

  /** {@inheritDoc} */
  override fun getMultipleGroups(groupnames: List<String>): List<ADGroup> {
    return groupnames.map { groupname -> getGroup(groupname) }
  }

  /** {@inheritDoc} */
  override fun getAllGroups(): List<ADGroup> {
    return listOf(
        ADGroup(
            cn = "Mock Group 1",
            description = "Mock group 1 description",
            distinguishedName = "CN=Mock Group 1,OU=Groups,DC=dms,DC=local",
            objectGuid = "mock-guid-1",
            members = emptyList(),
            membersListIncomplete = false,
            administrators = emptyList()),
        ADGroup(
            cn = "Mock Group 2",
            description = "Mock group 2 description",
            distinguishedName = "CN=Mock Group 2,OU=Groups,DC=dms,DC=local",
            objectGuid = "mock-guid-2",
            members = emptyList(),
            membersListIncomplete = false,
            administrators = emptyList()))
  }

  /** {@inheritDoc} */
  override fun getMembersByDnList(dnList: List<String>) = listOf<ADUser>()

  /** {@inheritDoc} */
  override fun getMembersByLoggedInDays(days: Int): List<ADUser> = listOf()

  companion object {
    private val sampleMembersMap =
        mapOf(
            "user1" to
                ADUser(
                    sAMAccountName = "user1",
                    givenName = "User",
                    sn = "One",
                    displayName = "User One",
                    mail = "user1@example.com",
                    objectGuid = "12345678-1234-1234-1234-123456789012",
                    // Raw AD LDAP generalized-time format (yyyyMMddHHmmss.0Z), not ISO-8601 —
                    // MemberService.calculateMemberSince(String?) reassembles separators by fixed
                    // character position and expects this exact shape.
                    whenCreated = "20210101000000.0Z",
                    telephoneNumber = "1234567890",
                    employeeID = "123456",
                    enabled = true,
                    distinguishedName = "CN=User One,OU=Members,DC=dms,DC=local",
                    groups =
                        listOf(
                            ADGroup(
                                cn = "Members",
                                description = "Qualified to use 3D printers",
                                distinguishedName =
                                    "cn=Members,ou=Security,ou=Groups,dc=dms,dc=local",
                                objectGuid = null,
                                members = listOf(),
                                membersListIncomplete = false))),
            "user2" to
                ADUser(
                    sAMAccountName = "user2",
                    givenName = "User",
                    sn = "Two",
                    displayName = "User Two",
                    mail = "user2@example.com",
                    telephoneNumber = "1234567890",
                    employeeID = "123456",
                    objectGuid = "12345678-1234-1234-1234-123456789013",
                    // See user1's whenCreated comment above for the expected format.
                    whenCreated = "20210102000000.0Z",
                    enabled = false,
                    distinguishedName = "CN=User Two,OU=Members,DC=dms,DC=local",
                    groups =
                        listOf(
                            ADGroup(
                                cn = "Members",
                                description = "Qualified to use 3D printers",
                                distinguishedName =
                                    "cn=Members,ou=Security,ou=Groups,dc=dms,dc=local",
                                objectGuid = null,
                                members = listOf(),
                                membersListIncomplete = false))),
        )
  }

  /** {@inheritDoc} */
  override fun removeUsersFromGroup(dmsUsernames: List<String>, group: String) {
    // Do nothing
  }

  /** {@inheritDoc} */
  override fun addUsersToGroup(dmsUsernames: List<String>, group: String) {
    // Do nothing
  }

  /** {@inheritDoc} */
  override fun updateBadgeNumber(username: String, newBadgeNumber: String) {
    // Do nothing
  }

  /** {@inheritDoc} */
  override fun deleteGroup(groupName: String, expectedObjectGuid: String): Boolean = true

  /** {@inheritDoc} */
  override fun getGroupStrict(groupname: String): ADGroup = getGroup(groupname)

  /** {@inheritDoc} */
  override fun findGroupByGuid(objectGuid: String): GroupIdentity? = null

  /** {@inheritDoc} */
  override fun addUsersToGroupByDn(dmsUsernames: List<String>, groupDn: String) {
    // Do nothing
  }

  /** {@inheritDoc} */
  override fun groupExists(groupName: String): Boolean = true

  /** {@inheritDoc} */
  override fun countManagedObjects(groupName: String): ManagedObjectCounts = ManagedObjectCounts()

  /** {@inheritDoc} */
  override fun addGroupToParentGroup(groupName: String, parentGroupDn: String): Boolean = true

  /** {@inheritDoc} */
  override fun createGroup(spec: AdGroupSpec): AdGroupCreateResult {
    val plan = planGroupCreate(spec, emptyList()) as GroupCreatePlan.Create
    return AdGroupCreateResult(plan.dn, "mock-guid-${spec.name}", created = true)
  }
}
