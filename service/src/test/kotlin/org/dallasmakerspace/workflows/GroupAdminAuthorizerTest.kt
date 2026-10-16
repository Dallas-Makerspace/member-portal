package org.dallasmakerspace.workflows

import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.dallasmakerspace.core.api.Groups
import org.dallasmakerspace.workflows.groups.ADMIN_OU
import org.dallasmakerspace.workflows.groups.FakeDirectory
import org.dallasmakerspace.workflows.groups.FakeGroup
import org.dallasmakerspace.workflows.groups.MAIN_OU
import org.junit.Test

private const val GROUP = "Test Group"

class GroupAdminAuthorizerTest {
  private val directory =
      FakeDirectory().apply {
        add(FakeGroup(GROUP))
        add(FakeGroup("Admin Group", dn = "CN=Admin Group,$ADMIN_OU"))
        add(FakeGroup("Shop+Lab Team", dn = "CN=Shop+Lab Team,$MAIN_OU"))
      }

  /** Users who administer a group, and who sits in the Infrastructure group. */
  private val admins = mutableMapOf<String, MutableSet<String>>()
  private val infra = mutableSetOf<String>()
  private val adminChecks = mutableListOf<Pair<String, String>>()
  private val enabledFor = mutableSetOf("asmith", "bsmith", "infrauser", "domainadmin")
  private var flagFails = false
  private var infraFails = false
  private var adminFails = false

  private val authorizer =
      GroupAdminAuthorizer(
          directory,
          { user ->
            if (flagFails) throw IOException("config down")
            user in enabledFor
          },
          { user ->
            if (infraFails) throw IOException("directory down")
            user in infra
          },
          { user, slug ->
            if (adminFails) throw IOException("directory down")
            adminChecks += user to slug
            user in admins[Groups.getNameFromSlug(slug)].orEmpty()
          },
          Dispatchers.IO,
      )

  private fun input(group: String?) =
      JsonObject(if (group == null) emptyMap() else mapOf("groupName" to JsonPrimitive(group)))

  private fun decide(group: String?, actor: String) = runBlocking {
    authorizer.authorize("group.archive", input(group), actor)
  }

  @Test
  fun `a listed administrator of a main OU group is allowed`() {
    admins[GROUP] = mutableSetOf("asmith")

    assertEquals(AuthorizationDecision.Allowed, decide(GROUP, "asmith"))
  }

  @Test
  fun `Domain Admins members count through the administrators check`() {
    admins[GROUP] = mutableSetOf("domainadmin")

    assertEquals(AuthorizationDecision.Allowed, decide(GROUP, "domainadmin"))
  }

  @Test
  fun `an Infrastructure member is allowed without being listed`() {
    infra += "infrauser"

    assertEquals(AuthorizationDecision.Allowed, decide(GROUP, "infrauser"))
    assertTrue(adminChecks.isEmpty(), "the cheaper infra check should settle it")
  }

  @Test
  fun `a random member is denied`() {
    admins[GROUP] = mutableSetOf("asmith")
    infra += "infrauser"

    assertIs<AuthorizationDecision.Denied>(decide(GROUP, "bsmith"))
  }

  @Test
  fun `even an administrator or Infrastructure member is denied for an admin OU group`() {
    admins["Admin Group"] = mutableSetOf("asmith")
    infra += "infrauser"

    assertIs<AuthorizationDecision.Denied>(decide("Admin Group", "asmith"))
    assertIs<AuthorizationDecision.Denied>(decide("Admin Group", "infrauser"))
  }

  @Test
  fun `a missing group is denied`() {
    infra += "infrauser"

    assertIs<AuthorizationDecision.Denied>(decide("Nope", "infrauser"))
  }

  @Test
  fun `a missing or malformed group name or a blank actor is denied`() {
    infra += "infrauser"

    assertIs<AuthorizationDecision.Denied>(decide(null, "infrauser"))
    assertIs<AuthorizationDecision.Denied>(decide("  ", "infrauser"))
    assertIs<AuthorizationDecision.Denied>(decide(GROUP, "  "))
    val notText = JsonObject(mapOf("groupName" to JsonArray(listOf(JsonPrimitive(GROUP)))))
    assertIs<AuthorizationDecision.Denied>(
        runBlocking { authorizer.authorize("group.archive", notText, "infrauser") })
    val number = JsonObject(mapOf("groupName" to JsonPrimitive(5)))
    assertIs<AuthorizationDecision.Denied>(
        runBlocking { authorizer.authorize("group.archive", number, "infrauser") })
  }

  @Test
  fun `errors from the directory or the member checks deny`() {
    admins[GROUP] = mutableSetOf("asmith")
    infra += "infrauser"

    adminFails = true
    assertIs<AuthorizationDecision.Denied>(decide(GROUP, "asmith"))
    adminFails = false
    infraFails = true
    assertIs<AuthorizationDecision.Denied>(decide(GROUP, "infrauser"))
    infraFails = false
    directory.onGetGroup = { throw IOException("directory down") }
    assertIs<AuthorizationDecision.Denied>(decide(GROUP, "asmith"))
  }

  @Test
  fun `every denial has the same generic reason, so group existence does not leak`() {
    admins[GROUP] = mutableSetOf("asmith")
    val reasons =
        listOf(
                decide("Nope", "bsmith"),
                decide("Admin Group", "bsmith"),
                decide(GROUP, "bsmith"),
                decide(null, "bsmith"),
            )
            .map { assertIs<AuthorizationDecision.Denied>(it).reason }

    assertEquals(1, reasons.toSet().size)
    assertTrue(reasons.none { it.contains(GROUP) || it.contains("Nope") })
  }

  @Test
  fun `the slug handed to the group admin check decodes back to the group name`() {
    admins["Shop+Lab Team"] = mutableSetOf("asmith")
    admins[GROUP] = mutableSetOf("asmith")

    assertEquals(AuthorizationDecision.Allowed, decide("Shop+Lab Team", "asmith"))
    assertEquals(AuthorizationDecision.Allowed, decide(" Test Group ", "asmith"))

    assertEquals(
        listOf("Shop+Lab Team", GROUP), adminChecks.map { Groups.getNameFromSlug(it.second) })
  }

  @Test
  fun `the archive flag must be enabled for the actor, whatever their rights`() {
    admins[GROUP] = mutableSetOf("asmith")
    infra += "infrauser"
    enabledFor -= "asmith"
    enabledFor -= "infrauser"

    assertIs<AuthorizationDecision.Denied>(decide(GROUP, "asmith"))
    assertIs<AuthorizationDecision.Denied>(decide(GROUP, "infrauser"))
    enabledFor += "asmith"
    assertEquals(AuthorizationDecision.Allowed, decide(GROUP, "asmith"))
  }

  @Test
  fun `a failing flag lookup denies and a disabled flag gives the generic reason`() {
    admins[GROUP] = mutableSetOf("asmith")
    flagFails = true
    val failed = assertIs<AuthorizationDecision.Denied>(decide(GROUP, "asmith"))
    flagFails = false
    enabledFor -= "asmith"
    val off = assertIs<AuthorizationDecision.Denied>(decide(GROUP, "asmith"))

    assertEquals(failed.reason, off.reason)
    assertTrue(adminChecks.isEmpty(), "rights are not even consulted while the flag is off")
  }

  @Test
  fun `a group name that exists twice is denied`() {
    infra += "infrauser"
    directory.twins += FakeGroup(GROUP, dn = "CN=$GROUP,OU=Sub,$MAIN_OU", guid = "bbbbbbbb-0000-0000-0000-000000000002")
    admins[GROUP] = mutableSetOf("asmith")

    assertIs<AuthorizationDecision.Denied>(decide(GROUP, "infrauser"))
    assertIs<AuthorizationDecision.Denied>(decide(GROUP, "asmith"))
  }

  @Test
  fun `the actor name is trimmed`() {
    admins[GROUP] = mutableSetOf("asmith")

    assertEquals(AuthorizationDecision.Allowed, decide(GROUP, " asmith "))
  }
}
