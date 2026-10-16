package org.dallasmakerspace.activedirectory

import com.unboundid.ldap.listener.InMemoryDirectoryServer
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig
import com.unboundid.ldap.listener.InMemoryListenerConfig
import com.unboundid.ldap.sdk.Attribute
import com.unboundid.ldap.sdk.Entry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Runs the real [ActiveDirectoryClient] against an in-memory LDAP server (no schema, so the AD
 * attribute names are plain strings) to exercise the filters and result handling of the group
 * writes. What it cannot show is how a real domain controller answers: see the pure
 * `isAlreadyMember` test for the codes AD uses.
 */
class ActiveDirectoryClientLdapTest {
  private val main = "OU=Groups,DC=dms,DC=local"
  private val admin = "OU=Groups,OU=Admin,DC=dms,DC=local"
  private val guidShop = AdGuid.fromBytes("1234567890123456".toByteArray())!!
  private val guidOther = AdGuid.fromBytes("6543210987654321".toByteArray())!!
  private val guidTwin = AdGuid.fromBytes("1111222233334444".toByteArray())!!
  private val guidNone = AdGuid.fromBytes("9999888877776666".toByteArray())!!

  private lateinit var server: InMemoryDirectoryServer
  private lateinit var client: ActiveDirectoryClient

  @BeforeTest
  fun start() {
    val config = InMemoryDirectoryServerConfig("DC=dms,DC=local")
    config.addAdditionalBindCredentials("cn=svc,dc=dms,dc=local", "pw")
    config.setSchema(null)
    config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("test", 0))
    server = InMemoryDirectoryServer(config)
    server.startListening()
    server.add(
        Entry(
            "DC=dms,DC=local",
            Attribute("objectClass", "top", "domain"),
            Attribute("dc", "dms")))
    ou(main)
    ou(admin.substringAfter(","))
    ou(admin)

    val appConfig: AppConfig = mock()
    whenever(appConfig.requireStringProperty("app.ldap.user")).thenReturn("cn=svc,dc=dms,dc=local")
    whenever(appConfig.requireStringProperty("app.ldap.password")).thenReturn("pw")
    whenever(appConfig.requireStringProperty("app.ldap.host")).thenReturn("localhost")
    whenever(appConfig.requireIntProperty("app.ldap.port")).thenReturn(server.listenPort)
    val loggerFactory: LoggerFactory = mock()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(mock())
    client = ActiveDirectoryClient(appConfig, loggerFactory)
  }

  @AfterTest
  fun stop() {
    server.shutDown(true)
  }

  private fun ou(dn: String) {
    server.add(
        Entry(
            dn,
            Attribute("objectClass", "top", "organizationalUnit"),
            Attribute("ou", dn.substringBefore(",").substringAfter("="))))
  }

  private fun group(cn: String, parent: String, guid: String, vararg extra: Attribute): String {
    val dn = "CN=${cn.replace(",", "\\,")},$parent"
    val attributes =
        mutableListOf(
            Attribute("objectClass", "top", "group"),
            Attribute("cn", cn),
            Attribute("name", cn),
            Attribute("objectCategory", "group"),
            Attribute("objectGUID", AdGuid.toBytes(guid)))
    attributes.addAll(extra)
    server.add(Entry(dn, attributes))
    return dn
  }

  private fun user(username: String, vararg classes: String, managedBy: String? = null): String {
    val dn = "CN=$username,OU=Members,DC=dms,DC=local"
    if (!server.entryExists("OU=Members,DC=dms,DC=local")) ou("OU=Members,DC=dms,DC=local")
    val attributes =
        mutableListOf(
            Attribute("objectClass", "top", "person", "user", *classes),
            Attribute("objectCategory", "person"),
            Attribute("sAMAccountName", username))
    managedBy?.let { attributes.add(Attribute("managedBy", it)) }
    server.add(Entry(dn, attributes))
    return dn
  }

  private fun exists(dn: String) = server.entryExists(dn)

  // delete by objectGUID

  @Test
  fun `deleteGroup removes exactly the group with the expected binary GUID`() {
    val shop = group("Shop", main, guidShop)
    val other = group("Other", main, guidOther)

    assertTrue(client.deleteGroup("Shop", guidShop))

    assertFalse(exists(shop))
    assertTrue(exists(other))
  }

  @Test
  fun `deleteGroup never falls back to the name when the GUID matches nothing`() {
    val shop = group("Shop", main, guidShop)

    assertFailsWith<GroupGuidNotFoundException> { client.deleteGroup("Shop", guidNone) }
    assertFailsWith<GroupGuidNotFoundException> { client.deleteGroup("Shop", "not-a-guid") }

    assertTrue(exists(shop))
  }

  @Test
  fun `deleteGroup refuses a GUID that belongs to a group with another name`() {
    val other = group("Other", main, guidOther)
    group("Shop", main, guidShop)

    assertFailsWith<GroupDeletionRefusedException> { client.deleteGroup("Shop", guidOther) }

    assertTrue(exists(other))
  }

  @Test
  fun `deleteGroup refuses a name that exists twice in the group OUs`() {
    val shop = group("Shop", main, guidShop)
    ou("OU=Sub,$main")
    val twin = group("Shop", "OU=Sub,$main", guidTwin)
    val adminTwin = group("Shop", admin, guidNone)

    assertFailsWith<AmbiguousGroupException> { client.deleteGroup("Shop", guidShop) }
    assertFailsWith<AmbiguousGroupException> { client.deleteGroup("Shop", guidTwin) }

    assertTrue(exists(shop) && exists(twin) && exists(adminTwin))
  }

  @Test
  fun `deleteGroup refuses the admin OU and protected names`() {
    val adminGroup = group("Shop", admin, guidShop)
    val board = group("Board", main, guidOther)

    assertFailsWith<GroupDeletionRefusedException> { client.deleteGroup("Shop", guidShop) }
    assertFailsWith<GroupDeletionRefusedException> { client.deleteGroup("Board", guidOther) }

    assertTrue(exists(adminGroup) && exists(board))
  }

  @Test
  fun `findGroupByGuid answers with the identity or nothing`() {
    group("Shop", main, guidShop)

    val found = assertNotNull(client.findGroupByGuid(guidShop))

    assertEquals("Shop", found.name)
    assertEquals("CN=Shop,$main", found.distinguishedName)
    assertEquals(guidShop, found.objectGuid)
    assertNull(client.findGroupByGuid(guidNone))
    assertNull(client.findGroupByGuid("nope"))
  }

  // parent memberships

  @Test
  fun `addGroupToParentGroup adds the child once and reports an existing membership as false`() {
    val shop = group("Shop", main, guidShop)
    val parent = group("Parent", main, guidOther)

    assertTrue(client.addGroupToParentGroup("Shop", parent))
    assertFalse(client.addGroupToParentGroup("Shop", parent))

    assertEquals(listOf(shop), server.getEntry(parent)!!.getAttributeValues("member").toList())
  }

  @Test
  fun `addGroupToParentGroup refuses parents outside the main OU and reports a missing one`() {
    group("Shop", main, guidShop)
    val adminParent = group("Parent", admin, guidOther)

    assertFailsWith<GroupMembershipRefusedException> {
      client.addGroupToParentGroup("Shop", adminParent)
    }
    assertFailsWith<GroupNotFoundException> {
      client.addGroupToParentGroup("Shop", "CN=Gone,$main")
    }
    assertNull(server.getEntry(adminParent)!!.getAttributeValues("member"))
  }

  // members by exact DN

  @Test
  fun `addUsersToGroupByDn adds members to the group at exactly that DN and is idempotent`() {
    val shop = group("Shop", main, guidShop)
    ou("OU=Sub,$main")
    val twin = group("Shop", "OU=Sub,$main", guidTwin)
    val asmith = user("asmith")
    user("bsmith")

    client.addUsersToGroupByDn(listOf("asmith", "nobody"), twin)
    client.addUsersToGroupByDn(listOf("asmith"), twin)

    assertEquals(listOf(asmith), server.getEntry(twin)!!.getAttributeValues("member").toList())
    assertNull(server.getEntry(shop)!!.getAttributeValues("member"))
  }

  @Test
  fun `addUsersToGroupByDn refuses groups outside the main OU and missing groups`() {
    val adminGroup = group("Shop", admin, guidShop)
    user("asmith")

    assertFailsWith<GroupMembershipRefusedException> {
      client.addUsersToGroupByDn(listOf("asmith"), adminGroup)
    }
    assertFailsWith<GroupNotFoundException> {
      client.addUsersToGroupByDn(listOf("asmith"), "CN=Gone,$main")
    }
  }

  // managed objects, with a name that needs filter escaping

  @Test
  fun `countManagedObjects counts by kind using an escaped DN filter`() {
    val tricky = "Wood, Shop (A) *"
    val dn = group(tricky, main, guidShop)
    group("Managed Group", main, guidOther, Attribute("managedBy", dn))
    user("asmith", managedBy = dn)
    user("bsmith", managedBy = dn)
    user("pc1", "computer", managedBy = dn)
    user("unrelated", managedBy = "CN=Someone Else,$main")

    val counts = client.countManagedObjects(tricky)

    assertEquals(ManagedObjectCounts(groups = 1, users = 2, computers = 1), counts)
  }

  @Test
  fun `countManagedObjects is zero for an unmanaged group and refuses a duplicate name`() {
    group("Shop", main, guidShop)
    assertEquals(0, client.countManagedObjects("Shop").total)

    ou("OU=Sub,$main")
    group("Shop", "OU=Sub,$main", guidTwin)
    assertFailsWith<AmbiguousGroupException> { client.countManagedObjects("Shop") }
    assertFailsWith<GroupNotFoundException> { client.countManagedObjects("Nope") }
  }

  @Test
  fun `groupExists sees any group of that name`() {
    group("Shop", admin, guidShop)

    assertTrue(client.groupExists("Shop"))
    assertFalse(client.groupExists("Nope"))
  }
}
