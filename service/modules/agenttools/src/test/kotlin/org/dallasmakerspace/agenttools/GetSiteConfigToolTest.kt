package org.dallasmakerspace.agenttools

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.agent.MemberAccess
import org.dallasmakerspace.agent.ToolContext
import org.dallasmakerspace.config.ConfigCurrentValue
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class GetSiteConfigToolTest {
  private val overrides: ConfigOverrideService = mock()
  private val directory: IActiveDirectoryService = mock()
  private val staff = ToolContext("jdoe", "c1", "it-assistant", MemberAccess.ANY_MEMBER)
  private val self = ToolContext("jdoe", "c1", "ask-ai", MemberAccess.SELF_ONLY)
  private lateinit var tool: GetSiteConfigTool

  private fun entry(
      key: String,
      current: String,
      default: String = current,
      type: String = "StringType",
      overridden: Boolean = false,
      sensitive: Boolean = false,
      infraOnly: Boolean = false,
      category: String = "Test",
      description: String = "A test key",
      changedAt: String? = null,
  ) =
      ConfigCurrentValue(
          key, description, category, current, default, type, overridden, sensitive, true, null,
          changedAt, null, infraOnly)

  private fun flag(
      key: String,
      enabled: Boolean = false,
      users: List<String> = emptyList(),
      groups: List<String> = emptyList(),
      overridden: Boolean = false,
  ): ConfigCurrentValue {
    val json =
        """{"enabled":$enabled,"enabledUsernames":[${users.joinToString(",") { "\"$it\"" }}],"enabledGroupNames":[${groups.joinToString(",") { "\"$it\"" }}]}"""
    return entry(key, json, type = "FeatureFlagType", overridden = overridden, category = "Groups", description = "A flag")
  }

  private fun adUser(vararg groups: String) =
      ADUser(
          sAMAccountName = "asmith", mail = "asmith@example.com", givenName = "Ann", sn = "Smith",
          telephoneNumber = null, employeeID = null, displayName = "Ann Smith", objectGuid = "guid",
          whenCreated = null, enabled = true,
          groups = groups.map { ADGroup(it, null, "CN=$it", null, emptyList(), false) })

  private fun args(vararg pairs: Pair<String, String>): JsonObject =
      buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

  private fun values(vararg entries: ConfigCurrentValue) = runBlocking { whenever(overrides.getCurrentValues()).thenReturn(entries.toList()) }

  @Before
  fun setUp() {
    tool = GetSiteConfigTool(overrides, directory)
  }

  @Test
  fun `without a search it lists keys by category with no values`() = runBlocking<Unit> {
    values(
        entry("voting.elections-url", "https://example.org/secret-path", category = "Voting"),
        entry("groups.cap", "7", type = "IntType", overridden = true, category = "Groups"))

    val result = tool.run(JsonObject(emptyMap()), staff)

    assertFalse(result.isError)
    assertTrue(result.output.contains("Voting: voting.elections-url [text]"), result.output)
    assertTrue(result.output.contains("Groups: groups.cap [number]*"), result.output)
    assertFalse(result.output.contains("secret-path"))
    verify(directory, never()).getMembersByUsernameList(any())
  }

  @Test
  fun `a secret-typed key never shows its value or default`() = runBlocking<Unit> {
    values(
        entry("svc.client-secret", "s3cr3t-live", default = "s3cr3t-default", overridden = true, sensitive = true),
        entry("svc.api-key", "key-value-1234"),
        entry("svc.note", "visible note"),
        entry("svc.endpoint", "internal-endpoint-value", overridden = true, infraOnly = true),
        entry("svc.blob", """{"a":"complex-value"}""", type = "ComplexType"),
        entry("svc.url", "https://user:hunter2@host.example.org/path"),
        entry("svc.opaque", "AbC123dEf456GhI789jKl012mNo"),
        entry("svc.db", "jdbc:mariadb://host.example.org/db"))

    val output = tool.run(args("search" to "svc."), staff).output

    listOf("s3cr3t", "key-value-1234", "internal-endpoint-value", "complex-value", "hunter2", "AbC123dEf456GhI789jKl012mNo", "mariadb")
        .forEach { assertFalse(output.contains(it), "$it leaked:\n$output") }
    assertTrue(output.contains("visible note"))
    assertTrue(output.contains("https://***@host.example.org/path"), output)
    assertEquals(6, Regex("Value: hidden").findAll(output).count(), output)
  }

  @Test
  fun `it distinguishes default from overridden and shows the Central change time`() = runBlocking<Unit> {
    values(
        entry("a.limit", "30", type = "IntType", overridden = true, changedAt = "2026-10-12T09:14:30"),
        entry("a.other", "5", type = "IntType"))

    val output = tool.run(args("search" to "a."), staff).output

    assertTrue(output.contains("a.limit (Test, number): overridden (last changed 2026-10-12 09:14 Central)"), output)
    assertTrue(output.contains("a.other (Test, number): at default"), output)
    assertTrue(output.contains("Value: 30"))
  }

  @Test
  fun `a feature flag shows counts and groups but never the piloted usernames`() = runBlocking<Unit> {
    values(flag("groups.settings-enabled", users = listOf("jdoe", "asmith", "bwhite"), groups = listOf("Pilot Group")))

    val output = tool.run(args("search" to "settings"), staff).output

    assertTrue(output.contains("off for everyone; piloted members: 3; piloted groups: Pilot Group"), output)
    listOf("jdoe", "asmith", "bwhite").forEach { assertFalse(output.contains(it), "$it listed") }
  }

  @Test
  fun `a username allow-list key shows a count only`() = runBlocking<Unit> {
    values(entry("waiver-report.allowed-usernames", "jdoe,asmith, bwhite"))

    val output = tool.run(args("search" to "waiver"), staff).output

    assertTrue(output.contains("3 usernames (not listed)"), output)
    assertFalse(output.contains("asmith"))
  }

  @Test
  fun `with a username each flag says whether it is enabled for that member and why`() = runBlocking<Unit> {
    val onKey = ConfigRegistry.GROUP_SETTINGS_ENABLED
    val offKey = ConfigRegistry.REMOTE_ACCESS_ENABLED
    values(
        flag(onKey.key, groups = listOf("Pilot Group")),
        flag(offKey.key, groups = listOf("Other Group")),
        entry("groups.cap", "7", type = "IntType"))
    whenever(directory.getMembersByUsernameList(listOf("asmith"))) doReturn mapOf("asmith" to adUser("Pilot Group", "Members"))
    whenever(overrides.isFeatureEnabledFor(onKey, "asmith", listOf("Pilot Group", "Members"))) doReturn true
    whenever(overrides.isFeatureEnabledFor(offKey, "asmith", listOf("Pilot Group", "Members"))) doReturn false

    val output = tool.run(args("username" to "asmith"), staff).output

    assertTrue(output.contains("- ${onKey.key}: ENABLED (the member is in piloted group Pilot Group)"), output)
    assertTrue(output.contains("- ${offKey.key}: NOT enabled (the flag is off, the member is not on the pilot list, none of their groups is piloted)"), output)
    assertFalse(output.contains("groups.cap"), "non-flag keys are left out of a per-member check")
    assertFalse(output.contains("mail"))
  }

  @Test
  fun `a failed group lookup is reported rather than guessed`() = runBlocking<Unit> {
    val key = ConfigRegistry.GROUP_SETTINGS_ENABLED
    values(flag(key.key, groups = listOf("Pilot Group")))
    whenever(directory.getMembersByUsernameList(any())) doReturn emptyMap()
    whenever(overrides.isFeatureEnabledFor(key, "ghost", emptyList())) doReturn false

    val output = tool.run(args("search" to "settings", "username" to "ghost"), staff).output

    assertTrue(output.contains("not found in the directory"), output)
    assertTrue(output.contains("For ghost: NOT enabled"), output)
    assertTrue(output.contains("group piloting could not be checked"), output)
  }

  @Test
  fun `a search with no match says so`() = runBlocking<Unit> {
    values(entry("a.b", "1"))

    val result = tool.run(args("search" to "zzz"), staff)

    assertFalse(result.isError)
    assertEquals("No config key matched \"zzz\".", result.output)
  }

  @Test
  fun `search matches the description and category too`() = runBlocking<Unit> {
    values(
        entry("a.one", "1", description = "Controls the laser queue", category = "Fab"),
        entry("b.two", "2", description = "Other", category = "Laser Room"))

    val output = tool.run(args("search" to "LASER"), staff).output

    assertTrue(output.contains("a.one") && output.contains("b.two"), output)
  }

  @Test
  fun `a self-only bot cannot evaluate another member and does not see the username parameter`() = runBlocking<Unit> {
    values(flag("groups.settings-enabled"))

    val refused = tool.run(args("username" to "asmith"), self)
    assertTrue(refused.isError)
    verify(directory, never()).getMembersByUsernameList(any())

    assertTrue(tool.descriptorFor(MemberAccess.ANY_MEMBER).optionalParameters.any { it.name == "username" })
    assertEquals(listOf("search"), tool.descriptorFor(MemberAccess.SELF_ONLY).optionalParameters.map { it.name })
  }

  @Test
  fun `a self-only bot may check the asker`() = runBlocking<Unit> {
    val key = ConfigRegistry.GROUP_SETTINGS_ENABLED
    values(flag(key.key))
    whenever(directory.getMembersByUsernameList(listOf("jdoe"))) doReturn mapOf("jdoe" to adUser())
    whenever(overrides.isFeatureEnabledFor(key, "jdoe", emptyList())) doReturn true

    val result = tool.run(args("username" to "jdoe"), self)

    assertFalse(result.isError)
    assertTrue(result.output.contains("ENABLED"))
  }

  @Test
  fun `descriptor shape and privacy flags`() {
    assertEquals("get_site_config", tool.descriptor.name)
    assertTrue(tool.descriptor.requiredParameters.isEmpty())
    assertFalse(tool.touchesPersonalInfo)
    assertEquals(MemberAccess.SELF_ONLY, tool.requiredMemberAccess)
  }
}
