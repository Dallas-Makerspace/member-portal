package org.dallasmakerspace.activedirectory

import com.unboundid.asn1.ASN1OctetString
import com.unboundid.ldap.sdk.FailoverServerSet
import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldap.sdk.LDAPConnectionPool
import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldap.sdk.ModificationType
import com.unboundid.ldap.sdk.SearchRequest
import com.unboundid.ldap.sdk.SearchRequest.ALL_USER_ATTRIBUTES
import com.unboundid.ldap.sdk.SearchScope
import com.unboundid.ldap.sdk.SimpleBindRequest
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.DirectoryOus

private const val INITIAL_LDAP_CONNECTIONS = 3

private const val MAX_LDAP_CONNECTIONS = 5

@Singleton
class ActiveDirectoryClient
@Inject
constructor(appConfig: AppConfig, loggerFactory: LoggerFactory) : IActiveDirectoryClient {
  private val log = loggerFactory.create(javaClass)

  private val ldapUser = appConfig.requireStringProperty("app.ldap.user")
  private val ldapPass = appConfig.requireStringProperty("app.ldap.password")

  // app.ldap.host may be a single host or a comma-separated list of hosts in failover-preference
  // order (e.g. "dc1.dms.local,dc2.dms.local,dc3.dms.local"). FailoverServerSet always attempts
  // hosts in the order given, so the first entry is preferred and later entries are only used if
  // earlier ones are unreachable.
  private val bindHosts =
      appConfig.requireStringProperty("app.ldap.host").split(",").map { it.trim() }.toTypedArray()
  private val bindPort = appConfig.requireIntProperty("app.ldap.port")
  private val serverSet =
      FailoverServerSet(bindHosts, IntArray(bindHosts.size) { bindPort })
  private val ldapPool =
      LDAPConnectionPool(
          serverSet,
          SimpleBindRequest(ldapUser, ldapPass),
          INITIAL_LDAP_CONNECTIONS,
          MAX_LDAP_CONNECTIONS,
      )

  init {
    ldapPool.setMaxWaitTimeMillis(10_000L)
    log.info(
        "$TAG/init Configured LDAP servers in failover-preference order: " +
            bindHosts.joinToString(" -> ") { "$it:$bindPort" })
    log.info(
        "$TAG/init LDAP connection pool initialized with $INITIAL_LDAP_CONNECTIONS initial connections, max $MAX_LDAP_CONNECTIONS")
  }

  /**
   * Gets the actual connected server information from an LDAP connection. Returns a string with the
   * resolved hostname/IP and port, or DNS-resolved IPs if the actual socket can't be determined.
   */
  private fun getConnectedServerInfo(connection: com.unboundid.ldap.sdk.LDAPConnection): String {
    val connectedAddress = connection.connectedAddress
    val connectedPort = connection.connectedPort

    return try {
      // Try multiple approaches to get the underlying socket
      var socket: java.net.Socket? = null

      // Approach 1: Try direct socket field
      try {
        val socketField = connection.javaClass.getDeclaredField("socket")
        socketField.isAccessible = true
        socket = socketField.get(connection) as? java.net.Socket
      } catch (e: NoSuchFieldException) {
        // Field doesn't exist, try next approach
      }

      // Approach 2: Try connectionInternals -> socket
      if (socket == null) {
        try {
          val internalsField = connection.javaClass.getDeclaredField("connectionInternals")
          internalsField.isAccessible = true
          val internals = internalsField.get(connection)
          if (internals != null) {
            val socketField = internals.javaClass.getDeclaredField("socket")
            socketField.isAccessible = true
            socket = socketField.get(internals) as? java.net.Socket
          }
        } catch (e: NoSuchFieldException) {
          // Field doesn't exist, try next approach
        }
      }

      // Approach 3: Try connectionReader -> inputStream
      if (socket == null) {
        try {
          val readerField = connection.javaClass.getDeclaredField("connectionReader")
          readerField.isAccessible = true
          val reader = readerField.get(connection)
          if (reader != null) {
            val inputStreamField = reader.javaClass.getDeclaredField("inputStream")
            inputStreamField.isAccessible = true
            val inputStream = inputStreamField.get(reader)
            if (inputStream != null) {
              try {
                val socketField = inputStream.javaClass.getDeclaredField("socket")
                socketField.isAccessible = true
                socket = socketField.get(inputStream) as? java.net.Socket
              } catch (e: NoSuchFieldException) {
                // No socket field in input stream
              }
            }
          }
        } catch (e: Exception) {
          // Failed to get socket through reader
        }
      }

      if (socket != null && socket.isConnected) {
        val remoteAddress = socket.inetAddress
        val hostname = remoteAddress.hostName
        val ip = remoteAddress.hostAddress
        // If hostname is different from IP, show both
        if (hostname != ip) {
          "$hostname ($ip):$connectedPort"
        } else {
          "$ip:$connectedPort"
        }
      } else {
        // Fallback: manually resolve DNS to show all possible IPs
        try {
          val addresses = java.net.InetAddress.getAllByName(connectedAddress)
          val ips = addresses.map { it.hostAddress }.joinToString(", ")
          "$connectedAddress:$connectedPort (DNS: $ips)"
        } catch (e: Exception) {
          "$connectedAddress:$connectedPort"
        }
      }
    } catch (e: Exception) {
      // Fallback: manually resolve DNS to show all possible IPs
      try {
        val addresses = java.net.InetAddress.getAllByName(connectedAddress)
        val ips = addresses.map { it.hostAddress }.joinToString(", ")
        "$connectedAddress:$connectedPort (DNS: $ips)"
      } catch (e2: Exception) {
        "$connectedAddress:$connectedPort"
      }
    }
  }

  override fun getUsersByUsernameList(usernames: List<String>): Map<String, Map<String, Any?>> {
    log.info("$TAG/getUsersByUsernameList Fetching ${usernames.size} users")

    val filter =
        Filter.createANDFilter(
            listOf(
                Filter.createEqualityFilter("objectCategory", "person"),
                Filter.createORFilter(
                    listOf(
                        Filter.createEqualityFilter("objectClass", "member"),
                        Filter.createEqualityFilter("objectClass", "user"),
                    )),
                Filter.createORFilter(
                    usernames.map {
                      Filter.createEqualityFilter(
                          "sAMAccountName",
                          it,
                      )
                    }),
            ))
    val searchResult =
        ldapPool.search(
            "DC=dms, DC=local",
            SearchScope.SUB,
            filter,
            "sAMAccountName",
            "givenName",
            "sn",
            "displayName",
            "mail",
            "memberOf",
            "employeeID",
            "telephoneNumber",
            "objectGUID",
            "userAccountControl",
            "whenCreated",
            "distinguishedName",
        )
    return searchResult.searchEntries.associate { entry ->
      val username = entry.getAttributeValue("sAMAccountName")

      username to
          entry.attributes.associate {
            it.name to if (it.name == "memberOf") it.values else it.values?.firstOrNull()
          }
    }
  }

  // objectGUID is binary; the string view of an LDAP attribute value is lossy for it.
  private fun Map<String, Any?>.withCanonicalGuid(
      entry: com.unboundid.ldap.sdk.SearchResultEntry
  ): Map<String, Any?> =
      if (entry.hasAttribute("objectGUID")) {
        this + ("objectGUID" to AdGuid.fromBytes(entry.getAttributeValueBytes("objectGUID")))
      } else {
        this
      }

  override fun getGroup(groupname: String): Map<String, Any?> {
    val startTime = System.currentTimeMillis()
    var connectedServer = "unknown"

    try {
      // Get and log the connected server at the start
      val connection = ldapPool.connection
      try {
        connectedServer = getConnectedServerInfo(connection)
        log.info(
            "$TAG/getGroup Starting fetch for group '$groupname' using AD server: $connectedServer")
      } finally {
        ldapPool.releaseConnection(connection)
      }

      val groupFilter =
          Filter.createANDFilter(
              listOf(
                  Filter.createEqualityFilter("name", groupname),
                  Filter.createEqualityFilter("objectCategory", "group"),
              ))

      val searchResult =
          ldapPool.search(
              "DC=dms,DC=local",
              SearchScope.SUB,
              groupFilter,
              "cn",
              "distinguishedName",
              "description",
              "member",
              "objectGUID",
              "managedBy",
              "nTSecurityDescriptor",
          )

      val groupEntry =
          preferredByGroupOu(searchResult.searchEntries)
              ?: throw ADException("Group $groupname not found in AD")

      val group =
          groupEntry.attributes
              .associate {
                it.name to if (it.name == "member") it.values else it.values.firstOrNull()
              }
              .withCanonicalGuid(groupEntry)

      // Escape special characters like '(' and ')' in the group DN
      val groupDN =
          (group["distinguishedName"] as String?)?.replace("(", "\\28")?.replace(")", "\\29")
              ?: throw ADException("Group DN not found")

      // Get all members with paging to handle large groups
      val allMembers = getAllGroupMembers(groupDN)

      // Get group administrators - entities that can modify the group
      val groupAdmins = getGroupAdministrators(groupEntry)

      // Update the group information
      val updatedGroup = group.toMutableMap()
      updatedGroup["member"] = allMembers.toTypedArray()
      updatedGroup["administrators"] = groupAdmins.toTypedArray()

      val duration = System.currentTimeMillis() - startTime
      log.info(
          "$TAG/getGroup Successfully fetched group '$groupname' from AD server $connectedServer in ${duration}ms")

      return updatedGroup
    } catch (e: Exception) {
      val duration = System.currentTimeMillis() - startTime
      log.error(
          "$TAG/getGroup Failed to fetch group '$groupname' from AD server $connectedServer after ${duration}ms",
          e)
      throw e
    }
  }

  override fun getMultipleGroups(groupnames: List<String>): Map<String, Map<String, Any?>> {
    val startTime = System.currentTimeMillis()
    var connectedServer = "unknown"

    try {
      // Get and log the connected server at the start
      val connection = ldapPool.connection
      try {
        connectedServer =
            try {
              val socketField = connection.javaClass.getDeclaredField("socket")
              socketField.isAccessible = true
              val socket = socketField.get(connection) as? java.net.Socket
              if (socket != null && socket.isConnected) {
                val remoteAddress = socket.inetAddress
                val hostname = remoteAddress.hostName
                val ip = remoteAddress.hostAddress
                val port = connection.connectedPort
                if (hostname != ip) {
                  "$hostname ($ip):$port"
                } else {
                  "$ip:$port"
                }
              } else {
                "${connection.connectedAddress}:${connection.connectedPort}"
              }
            } catch (e: Exception) {
              "${connection.connectedAddress}:${connection.connectedPort}"
            }

        log.info(
            "$TAG/getMultipleGroups Starting batch fetch for ${groupnames.size} groups using AD server: $connectedServer")
      } finally {
        ldapPool.releaseConnection(connection)
      }

      // Create OR filter for all group names
      val nameFilters = groupnames.map { Filter.createEqualityFilter("name", it) }
      val groupFilter =
          Filter.createANDFilter(
              listOf(
                  Filter.createORFilter(nameFilters),
                  Filter.createEqualityFilter("objectCategory", "group"),
              ))

      val searchResult =
          ldapPool.search(
              "DC=dms,DC=local",
              SearchScope.SUB,
              groupFilter,
              "cn",
              "distinguishedName",
              "description",
              "member",
              "objectGUID",
              "managedBy",
              "nTSecurityDescriptor",
          )

      val groups = mutableMapOf<String, Map<String, Any?>>()

      for (groupEntry in preferredPerCn(searchResult.searchEntries)) {
        val groupName = groupEntry.getAttributeValue("cn")
        val group =
            groupEntry.attributes
                .associate {
                  it.name to if (it.name == "member") it.values else it.values.firstOrNull()
                }
                .withCanonicalGuid(groupEntry)

        // Escape special characters in the group DN
        val groupDN =
            (group["distinguishedName"] as String?)?.replace("(", "\\28")?.replace(")", "\\29")
                ?: continue

        // Get all members with paging to handle large groups
        val allMembers = getAllGroupMembers(groupDN)

        // Get group administrators
        val groupAdmins = getGroupAdministrators(groupEntry)

        // Update the group information
        val updatedGroup = group.toMutableMap()
        updatedGroup["member"] = allMembers.toTypedArray()
        updatedGroup["administrators"] = groupAdmins.toTypedArray()

        groups[groupName] = updatedGroup
      }

      val duration = System.currentTimeMillis() - startTime
      log.info(
          "$TAG/getMultipleGroups Successfully fetched ${groups.size} groups from AD server $connectedServer in ${duration}ms")

      return groups
    } catch (e: Exception) {
      val duration = System.currentTimeMillis() - startTime
      log.error(
          "$TAG/getMultipleGroups Failed to fetch groups from AD server $connectedServer after ${duration}ms",
          e)
      throw e
    }
  }

  /**
   * Retrieves all members of a group, handling pagination for large groups.
   *
   * @param groupDN The distinguished name of the group
   * @return List of distinguished names of all members
   */
  private fun getAllGroupMembers(groupDN: String): List<String> {
    log.info("$TAG/getAllGroupMembers Fetching members for group: $groupDN")

    val allMembers = mutableListOf<String>()

    // Query the group directly using its DN to get all members
    // The correct way to retrieve large groups is to query the group's member attribute with range
    // retrieval
    val groupFilter = Filter.createEqualityFilter("distinguishedName", groupDN)

    // Use a range to retrieve all members in batches
    var rangeStart = 0
    val rangeSize = 1000
    var hasMoreMembers = true

    while (hasMoreMembers) {
      val rangeEnd = if (rangeStart == 0) rangeSize - 1 else "*"
      val attributeName = "member;range=$rangeStart-$rangeEnd"

      log.info("$TAG/getAllGroupMembers Fetching range $rangeStart-$rangeEnd for group: $groupDN")

      val searchRequest =
          SearchRequest("DC=dms,DC=local", SearchScope.SUB, groupFilter, attributeName)

      val searchResult = ldapPool.search(searchRequest)

      if (searchResult.searchEntries.isEmpty()) {
        break
      }

      val entry = searchResult.searchEntries.first()
      val rangeAttribute = entry.attributes.find { it.name.startsWith("member;range=") }

      if (rangeAttribute != null) {
        val rangeValues = rangeAttribute.values
        if (rangeValues != null && rangeValues.isNotEmpty()) {
          allMembers.addAll(rangeValues)
        }

        // Check if we need to continue retrieving more members
        if (rangeAttribute.name.endsWith("-*")) {
          hasMoreMembers = false
        } else {
          rangeStart += rangeSize
        }
      } else {
        // If no range attribute is found but there's a standard member attribute, use that
        val memberAttribute = entry.getAttribute("member")
        if (memberAttribute != null && memberAttribute.values != null) {
          allMembers.addAll(memberAttribute.values)
        }
        hasMoreMembers = false
      }
    }

    return allMembers
  }

  /**
   * Retrieves the list of users/groups that have permissions to modify the group.
   *
   * @param groupEntry The LDAP entry for the group
   * @return List of distinguished names of administrators
   */
  private fun getGroupAdministrators(groupEntry: com.unboundid.ldap.sdk.Entry): List<String> {
    val administrators = mutableListOf<String>()

    // First check managedBy attribute
    val managedBy = groupEntry.getAttributeValue("managedBy")
    if (!managedBy.isNullOrEmpty()) {
      administrators.add(managedBy)
    }

    // Check nTSecurityDescriptor to find who has permissions to modify the group
    // This is a simplified approach since full security descriptor parsing is complex

    // Add domain admins (they always have permission)
    val domainAdminsFilter =
        Filter.createANDFilter(
            listOf(
                Filter.createEqualityFilter("name", "Domain Admins"),
                Filter.createEqualityFilter("objectCategory", "group"),
            ))

    val domainAdminsResult =
        ldapPool.search("DC=dms,DC=local", SearchScope.SUB, domainAdminsFilter, "distinguishedName")

    val domainAdminsDN = domainAdminsResult.searchEntries.firstOrNull()?.dn?.toString()
    if (!domainAdminsDN.isNullOrEmpty()) {
      administrators.add(domainAdminsDN)
    }

    return administrators
  }

  /** {@inheritDoc} */
  override fun getUsersByDnList(dnList: List<String>): Map<String, Map<String, Any?>> {
    log.info("$TAG/getUsersByDnList Fetching ${dnList.size} users by DN")

    val filter =
        Filter.createANDFilter(
            listOf(
                Filter.createEqualityFilter("objectCategory", "person"),
                Filter.createORFilter(
                    listOf(
                        Filter.createEqualityFilter("objectClass", "member"),
                        Filter.createEqualityFilter("objectClass", "user"),
                    )),
                Filter.createORFilter(
                    dnList.map {
                      Filter.createEqualityFilter(
                          "distinguishedName",
                          it,
                      )
                    }),
            ))
    val searchResult =
        ldapPool.search(
            "DC=dms, DC=local",
            SearchScope.SUB,
            filter,
            "sAMAccountName",
            "givenName",
            "sn",
            "displayName",
            "mail",
            "memberOf",
            "employeeID",
            "telephoneNumber",
            "objectGUID",
            "userAccountControl",
            "whenCreated",
            "distinguishedName",
        )
    return searchResult.searchEntries.associate { entry ->
      val dn = entry.dn.toString()

      dn to
          entry.attributes.associate {
            it.name to if (it.name == "memberOf") it.values else it.values?.firstOrNull()
          }
    }
  }

  override fun getUsersByBadgeNumberList(
      badgeNumberList: List<String>
  ): Map<String, Map<String, Any?>> {
    val filter =
        Filter.createANDFilter(
            listOf(
                Filter.createEqualityFilter("objectCategory", "person"),
                Filter.createORFilter(
                    listOf(
                        Filter.createEqualityFilter("objectClass", "member"),
                        Filter.createEqualityFilter("objectClass", "user"),
                    )),
                Filter.createORFilter(
                    badgeNumberList.map {
                      Filter.createEqualityFilter(
                          "employeeID",
                          it.trim(),
                      )
                    }),
            ))
    val searchResult =
        ldapPool.search(
            "DC=dms, DC=local",
            SearchScope.SUB,
            filter,
            "sAMAccountName",
            "givenName",
            "sn",
            "displayName",
            "mail",
            "memberOf",
            "employeeID",
            "telephoneNumber",
            "objectGUID",
            "userAccountControl",
            "whenCreated",
            "distinguishedName",
        )
    return searchResult.searchEntries.associate { entry ->
      val badgeNumber = entry.getAttributeValue("employeeID")?.trim() ?: ""

      badgeNumber to
          entry.attributes.associate {
            it.name to if (it.name == "memberOf") it.values else it.values?.firstOrNull()
          }
    }
  }

  /** {@inheritDoc} */
  @Suppress("MagicNumber")
  override fun getUsersByLogonDays(days: Int): Map<String, Map<String, Any?>> {
    // Calculate the date N days ago
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.DAY_OF_MONTH, -days)
    val dateNDaysAgo = calendar.time

    // Convert the date to the number format used in LDAP the number for 100-nanosecond intervals
    // since 1601-01-01
    // For e.g. 133688140853991409 will represent 8/22/2024 10:28:05 AM CDT
    val dateNDaysAgoInLdapFormat = (dateNDaysAgo.time + 11644473600000) * 10000

    // Construct the LDAP filter to search for users who logged in the last N days
    val filter =
        Filter.createANDFilter(
            listOf(
                Filter.createEqualityFilter("objectCategory", "person"),
                Filter.createEqualityFilter("objectClass", "person"),
                Filter.createGreaterOrEqualFilter(
                    "lastLogonTimestamp",
                    dateNDaysAgoInLdapFormat.toString(),
                ),
            ))

    // Perform the search
    val searchResult =
        ldapPool.search(
            "ou=Members,dc=dms,dc=local",
            SearchScope.SUB,
            filter,
            "sAMAccountName",
            "givenName",
            "sn",
            "mail",
            "employeeID",
            "telephoneNumber",
            "displayName",
            "userAccountControl",
            "whenCreated",
            "distinguishedName",
        )

    // Process the search results
    return searchResult.searchEntries.associate { entry ->
      val preferredUsername = entry.getAttributeValue("sAMAccountName")

      preferredUsername to
          entry.attributes.associate {
            it.name to if (it.name == "memberOf") it.values else it.values?.firstOrNull()
          }
    }
  }

  /** {@inheritDoc} */
  @Suppress("LongMethod")
  override fun removeUsersFromGroup(dmsUsernames: List<String>, group: String) {
    val startTime = System.currentTimeMillis()
    var connectedServer = "unknown"

    try {
      val connection = ldapPool.connection
      try {
        connectedServer = getConnectedServerInfo(connection)
        log.info(
            "$TAG/removeUsersFromGroup Removing users $dmsUsernames from group '$group' using AD server: $connectedServer")
      } finally {
        ldapPool.releaseConnection(connection)
      }

      val groupFilter =
          Filter.createANDFilter(
              listOf(
                  Filter.createEqualityFilter("name", group),
                  Filter.createEqualityFilter("objectCategory", "group"),
              ))

      val searchResult =
          ldapPool.search(
              "DC=dms,DC=local",
              SearchScope.SUB,
              groupFilter,
              "cn",
              "distinguishedName",
              "description",
              "member",
              "objectGUID",
          )

      val groupEntry =
          searchResult.searchEntries.firstOrNull()
              ?: throw ADException("Group $group not found in AD")

      val groupDN = groupEntry.dn.toString()

      val userFilter =
          Filter.createANDFilter(
              listOf(
                  Filter.createEqualityFilter("objectCategory", "person"),
                  Filter.createEqualityFilter("objectClass", "user"),
                  Filter.createEqualityFilter("memberOf", groupDN),
                  Filter.createORFilter(
                      dmsUsernames.map { Filter.createEqualityFilter("sAMAccountName", it) }),
              ))

      val searchRequest =
          SearchRequest(
              "DC=dms,DC=local",
              SearchScope.SUB,
              userFilter,
              ALL_USER_ATTRIBUTES,
          )

      val userSearchResult = ldapPool.search(searchRequest)

      val usersToRemove =
          userSearchResult.searchEntries.filter {
            it.getAttributeValue("sAMAccountName") in dmsUsernames
          }

      usersToRemove.forEach { user ->
        val userDN = user.dn.toString()
        val mod = Modification(ModificationType.DELETE, "member", userDN)
        ldapPool.modify(groupDN, mod)
      }

      val duration = System.currentTimeMillis() - startTime
      log.info(
          "$TAG/removeUsersFromGroup Successfully removed ${usersToRemove.size} users from group '$group' using AD server $connectedServer in ${duration}ms")
    } catch (e: Exception) {
      val duration = System.currentTimeMillis() - startTime
      log.error(
          "$TAG/removeUsersFromGroup Failed to remove users from group '$group' using AD server $connectedServer after ${duration}ms",
          e)
      throw e
    }
  }

  /** {@inheritDoc} */
  @Suppress("LongMethod")
  override fun addUsersToGroup(dmsUsernames: List<String>, group: String) {
    val startTime = System.currentTimeMillis()
    var connectedServer = "unknown"

    try {
      val connection = ldapPool.connection
      try {
        connectedServer = getConnectedServerInfo(connection)
        log.info(
            "$TAG/addUsersToGroup Adding users $dmsUsernames to group '$group' using AD server: $connectedServer")
      } finally {
        ldapPool.releaseConnection(connection)
      }

      val groupFilter =
          Filter.createANDFilter(
              listOf(
                  Filter.createEqualityFilter("name", group),
                  Filter.createEqualityFilter("objectCategory", "group"),
              ))

      val searchResult =
          ldapPool.search(
              "DC=dms,DC=local",
              SearchScope.SUB,
              groupFilter,
              "cn",
              "distinguishedName",
              "description",
              "member",
              "objectGUID",
          )

      val groupEntry =
          searchResult.searchEntries.firstOrNull()
              ?: throw ADException("Group $group not found in AD")

      val groupDN = groupEntry.dn.toString()

      val userFilter =
          Filter.createANDFilter(
              listOf(
                  Filter.createEqualityFilter("objectCategory", "person"),
                  Filter.createEqualityFilter("objectClass", "user"),
                  Filter.createORFilter(
                      dmsUsernames.map { Filter.createEqualityFilter("sAMAccountName", it) }),
              ))

      val searchRequest =
          SearchRequest(
              "DC=dms,DC=local",
              SearchScope.SUB,
              userFilter,
              ALL_USER_ATTRIBUTES,
          )

      val userSearchResult = ldapPool.search(searchRequest)

      val usersToAdd =
          userSearchResult.searchEntries.filter {
            it.getAttributeValue("sAMAccountName") in dmsUsernames
          }

      if (usersToAdd.size != dmsUsernames.size) {
        val missingUsers =
            dmsUsernames.filter { username ->
              usersToAdd.none { it.getAttributeValue("sAMAccountName") == username }
            }
        log.error("$TAG/addUsersToGroup/ Users not found in AD: $missingUsers")
      }

      usersToAdd.forEach { user ->
        val userDN = user.dn.toString()
        val mod = Modification(ModificationType.ADD, "member", userDN)
        ldapPool.modify(groupDN, mod)
      }

      val duration = System.currentTimeMillis() - startTime
      log.info(
          "$TAG/addUsersToGroup Successfully added ${usersToAdd.size} users to group '$group' using AD server $connectedServer in ${duration}ms")
    } catch (e: Exception) {
      val duration = System.currentTimeMillis() - startTime
      log.error(
          "$TAG/addUsersToGroup Failed to add users to group '$group' using AD server $connectedServer after ${duration}ms",
          e)
      throw e
    }
  }

  /** {@inheritDoc} */
  override fun getAllGroups(): Map<String, Map<String, Any?>> {
    val slowThresholdMs = 1_000L
    val methodStart = System.nanoTime()

    val groupFilter = Filter.createEqualityFilter("objectCategory", "group")

    val searchStart = System.nanoTime()
    val groupEntries =
        firstWinsByCn(
            DirectoryOus.GROUP_OUS.map { base ->
              ldapPool
                  .search(
                      base,
                      SearchScope.SUB,
                      groupFilter,
                      "cn",
                      "distinguishedName",
                      "description",
                      "objectGUID",
                      "member",
                  )
                  .searchEntries
            },
            onDuplicate = duplicateNameWarning::log,
        )
    val searchDurationMs = (System.nanoTime() - searchStart) / 1_000_000
    val entryCount = groupEntries.size
    if (searchDurationMs > slowThresholdMs) {
      log.warn("$TAG/getAllGroups LDAP search took ${searchDurationMs}ms for ${entryCount} entries")
    } else {
      log.debug(
          "$TAG/getAllGroups LDAP search took ${searchDurationMs}ms for ${entryCount} entries")
    }

    val mapStart = System.nanoTime()
    val result =
        groupEntries.associate { groupEntry ->
          val groupName = groupEntry.getAttributeValue("cn")
          val group =
              groupEntry.attributes
                  .associate {
                    it.name to
                        if (it.name == "member") {
                          // For member count, just return the count without loading all members
                          it.values?.size ?: 0
                        } else {
                          it.values.firstOrNull()
                        }
                  }
                  .withCanonicalGuid(groupEntry)
          groupName to group
        }
    val mapDurationMs = (System.nanoTime() - mapStart) / 1_000_000

    val totalDurationMs = (System.nanoTime() - methodStart) / 1_000_000
    if (mapDurationMs > slowThresholdMs) {
      log.warn(
          "$TAG/getAllGroups mapping entries took ${mapDurationMs}ms for ${entryCount} entries")
    } else {
      log.debug(
          "$TAG/getAllGroups mapping entries took ${mapDurationMs}ms for ${entryCount} entries")
    }
    if (totalDurationMs > slowThresholdMs) {
      log.warn("$TAG/getAllGroups total duration ${totalDurationMs}ms")
    } else {
      log.debug("$TAG/getAllGroups total duration ${totalDurationMs}ms")
    }

    return result
  }

  /** {@inheritDoc} */
  @Suppress("MagicNumber")
  override fun getAllActiveUsersWithBadges(): Map<String, Map<String, Any?>> {
    val filter =
        Filter.createANDFilter(
            listOf(
                Filter.createEqualityFilter("objectCategory", "person"),
                Filter.createORFilter(
                    listOf(
                        Filter.createEqualityFilter("objectClass", "member"),
                        Filter.createEqualityFilter("objectClass", "user"),
                    )),
                Filter.createPresenceFilter("employeeID"), // Only users with badge numbers
            ))

    val pageSize = 1000 // Fetch 1000 entries at a time
    val allEntries = mutableListOf<com.unboundid.ldap.sdk.SearchResultEntry>()
    var cookie: ASN1OctetString? = null

    // AD paged-results cookies are bound to the specific TCP connection/session.
    // Using ldapPool.search() can hand each page to a different connection, causing AD to
    // reject the cookie with: LdapErr: DSID-0C090DAE, comment: Error processing control (0x57).
    // Hold a single dedicated connection for the entire paginated loop.
    val connection = ldapPool.connection
    try {
      do {
        val searchRequest =
            SearchRequest(
                "ou=Members,dc=dms,dc=local",
                SearchScope.SUB,
                filter,
                "sAMAccountName",
                "displayName",
                "givenName",
                "sn",
                "employeeID",
                "userAccountControl",
            )

        if (cookie == null) {
          searchRequest.addControl(SimplePagedResultsControl(pageSize, false))
        } else {
          searchRequest.addControl(SimplePagedResultsControl(pageSize, cookie, false))
        }

        val searchResult = connection.search(searchRequest)
        allEntries.addAll(searchResult.searchEntries)

        val responseControl = SimplePagedResultsControl.get(searchResult)
        cookie = responseControl?.cookie
      } while (cookie != null && cookie.valueLength > 0)
    } finally {
      ldapPool.releaseConnection(connection)
    }

    log.info("$TAG/getAllActiveUsersWithBadges Retrieved ${allEntries.size} users with badges")

    return allEntries.associate { entry ->
      val username = entry.getAttributeValue("sAMAccountName")

      username to
          entry.attributes.associate {
            it.name to if (it.name == "memberOf") it.values else it.values?.firstOrNull()
          }
    }
  }

  /** {@inheritDoc} */
  override fun updateBadgeNumber(username: String, newBadgeNumber: String) {
    val filter = Filter.createEqualityFilter("sAMAccountName", username)

    val searchResult =
        ldapPool.search(
            "ou=Members,dc=dms,dc=local",
            SearchScope.SUB,
            filter,
            "distinguishedName",
        )

    if (searchResult.searchEntries.isEmpty()) {
      throw Exception("User $username not found in Active Directory")
    }

    val userDN = searchResult.searchEntries.first().getAttributeValue("distinguishedName")
    val mod = Modification(ModificationType.REPLACE, "employeeID", newBadgeNumber)

    ldapPool.modify(userDN, mod)
    log.info("$TAG/updateBadgeNumber Updated badge number for $username to $newBadgeNumber")
  }

  private val duplicateNameWarning =
      OncePerName { log.warn("$TAG/getAllGroups duplicate name in two OUs: $it") }

  companion object {
    private const val TAG = "ActiveDirectoryClient"
  }
}

private val normalizedGroupOus = DirectoryOus.GROUP_OUS.map(::normalizeDnForOu)

private fun normalizeDnForOu(dn: String) = dn.split(',').joinToString(",") { it.trim().lowercase() }

/** Index of the first GROUP_OUS entry containing [dn]; outside all of them sorts last. */
internal fun groupOuRank(dn: String): Int {
  val normalized = normalizeDnForOu(dn)
  val rank = normalizedGroupOus.indexOfFirst { normalized.endsWith(",$it") }
  return if (rank >= 0) rank else Int.MAX_VALUE
}

/** Picks the entry in the earliest group OU; ties and non-group-OU entries keep list order. */
internal fun preferredByGroupOu(
    entries: List<com.unboundid.ldap.sdk.SearchResultEntry>
): com.unboundid.ldap.sdk.SearchResultEntry? = entries.minByOrNull { groupOuRank(it.dn) }

/** One entry per cn (case-insensitive), chosen by [preferredByGroupOu], in first-seen order. */
internal fun preferredPerCn(
    entries: List<com.unboundid.ldap.sdk.SearchResultEntry>
): List<com.unboundid.ldap.sdk.SearchResultEntry> =
    entries
        .groupBy { it.getAttributeValue("cn")?.lowercase() ?: "dn:${it.dn}" }
        .values
        .mapNotNull { preferredByGroupOu(it) }

/** Flattens per-OU search results; if a cn appears twice the earlier OU wins. */
internal fun firstWinsByCn(
    entriesPerOu: List<List<com.unboundid.ldap.sdk.SearchResultEntry>>,
    onDuplicate: (String) -> Unit = {},
): List<com.unboundid.ldap.sdk.SearchResultEntry> {
  val seen = HashSet<String>()
  return entriesPerOu.flatten().filter { entry ->
    val cn = entry.getAttributeValue("cn")
    if (cn == null || seen.add(cn.lowercase())) {
      true
    } else {
      onDuplicate(cn)
      false
    }
  }
}

/** Runs [emit] at most once per distinct name (case-insensitive) for the life of the instance. */
internal class OncePerName(private val emit: (String) -> Unit) {
  private val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()

  fun log(name: String) {
    if (seen.add(name.lowercase())) emit(name)
  }
}
