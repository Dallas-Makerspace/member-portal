package org.dallasmakerspace.workflows.groups

import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.storage.InMemoryObjectStore
import org.dallasmakerspace.storage.ObjectKey
import org.dallasmakerspace.storage.ObjectStoreFactory
import org.dallasmakerspace.storage.StorageNamespace
import org.dallasmakerspace.workflows.StepCheck
import org.dallasmakerspace.workflows.StepContext
import org.dallasmakerspace.workflows.StepMode
import org.junit.Test

private val NOW = Instant.parse("2030-01-02T03:04:05Z")

private fun ctx(runId: Long = 7) =
    StepContext(runId, "jdoe", "TEST", NOW, Clock.systemUTC(), 1, StepMode.EXECUTE)

private fun sha(text: String) =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
      "%02x".format(it.toInt() and 0xff)
    }

class ExportGroupMembersStepArchiveTest {
  private val directory = FakeDirectory()
  private val backing = InMemoryObjectStore()
  private val factory = ObjectStoreFactory(backing)
  private val store = factory.forNamespace(StorageNamespace.WORKFLOW_EXPORTS)
  private val step = ExportGroupMembersStep(directory, factory)
  private val archiveArgs =
      ExportGroupMembersStep.Args(
          "Test Group",
          requireComplete = true,
          downloadInfraOnly = true,
          keyPrefix = ExportGroupMembersStep.ARCHIVE_KEY_PREFIX)

  private fun group(
      members: Set<String> = setOf("Bsmith", "asmith", "csmith"),
      parents: List<String> = listOf("CN=Parent,$MAIN_OU"),
  ) =
      directory.add(
          FakeGroup(
              "Test Group", members = members.toMutableSet(), parents = parents))

  @Test
  fun `the digest is over sorted lowercase distinct usernames and ignores order and case`() {
    val expected = sha("asmith\nbsmith\ncsmith")

    assertEquals(
        expected, ExportGroupMembersStep.membersDigest(listOf("csmith", "Asmith", "bsmith")))
    assertEquals(
        expected,
        ExportGroupMembersStep.membersDigest(listOf("bsmith", "asmith", "ASMITH", "csmith ")))
    assertNotEquals(expected, ExportGroupMembersStep.membersDigest(listOf("asmith", "bsmith")))
    assertEquals(sha(""), ExportGroupMembersStep.membersDigest(emptyList()))
  }

  @Test
  fun `the result carries the digest, unresolved count and GUID but no usernames`() =
      runBlocking<Unit> {
        group()

        val result = step.run(ctx(), archiveArgs)

        assertEquals(sha("asmith\nbsmith\ncsmith"), result.membersSha256)
        assertEquals(0, result.unresolvedMemberCount)
        assertEquals("11111111-2222-3333-4444-555555555555", result.groupObjectGuid)
        val json = Json { encodeDefaults = true }.encodeToString(step.outputSerializer, result)
        assertFalse(json.contains("smith"))
      }

  @Test
  fun `the document records what a rollback needs`() = runBlocking<Unit> {
    group()

    val result = step.run(ctx(), archiveArgs)

    val bytes = assertNotNull(store.get(ObjectKey(result.key))).bytes
    val doc = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    assertEquals(2, doc.getValue("schemaVersion").jsonPrimitive.content.toInt())
    assertEquals("CN=Test Group,$MAIN_OU", doc.getValue("groupDn").jsonPrimitive.content)
    assertEquals("A fake group", doc.getValue("description").jsonPrimitive.content)
    assertEquals(
        "CN=Fake Owner,OU=Users,DC=example,DC=invalid",
        doc.getValue("managedBy").jsonPrimitive.content)
    assertEquals(
        listOf("CN=Parent,$MAIN_OU"),
        doc.getValue("parentGroups").jsonArray.map { it.jsonPrimitive.content })
    assertEquals(
        "11111111-2222-3333-4444-555555555555",
        doc.getValue("groupObjectGuid").jsonPrimitive.content)
  }

  @Test
  fun `the result and document carry the DN, parent counts, sAMAccountName and group type`() =
      runBlocking<Unit> {
        directory.add(
            FakeGroup(
                "Test Group",
                parents = listOf("CN=P1,$MAIN_OU", "CN=P2,OU=Sub,$MAIN_OU", "CN=P3,$ADMIN_OU"),
                sam = "test-group-sam",
                groupType = -2147483640))

        val result = step.run(ctx(), archiveArgs)

        assertEquals("CN=Test Group,$MAIN_OU", result.groupDn)
        assertEquals(3, result.parentGroupCount)
        assertEquals(1, result.parentGroupsOutsideMainCount)
        val bytes = assertNotNull(store.get(ObjectKey(result.key))).bytes
        val doc = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals("test-group-sam", doc.getValue("samAccountName").jsonPrimitive.content)
        assertEquals(-2147483640, doc.getValue("groupType").jsonPrimitive.content.toInt())
        assertFalse(Json.encodeToString(step.outputSerializer, result).contains("P1"))
      }

  @Test
  fun `check restores the DN and counts from stored metadata despite unusual characters`() =
      runBlocking<Unit> {
        val name = "Café Shop"
        val dn = "CN=Café Shop,$MAIN_OU"
        directory.add(FakeGroup(name, dn = dn, parents = listOf("CN=P1,$MAIN_OU")))
        val args = archiveArgs.copy(groupName = name)
        val result = step.run(ctx(), args)

        val check = step.check(ctx(), args)

        assertEquals(result, assertIs<StepCheck.NoOp<ExportGroupMembersStep.Result>>(check).output)
        assertEquals(dn, result.groupDn)
      }

  @Test
  fun `archive snapshots use their own prefix and exports keep the default`() = runBlocking<Unit> {
    group()

    val archived = step.run(ctx(7), archiveArgs)
    val exported = step.run(ctx(8), ExportGroupMembersStep.Args("Test Group"))

    assertEquals("group-archives/7/test-group.json", archived.key)
    assertEquals("group-exports/8/test-group.json", exported.key)
    assertEquals(
        listOf("group-archives/7/test-group.json"),
        store.list(ObjectKey("group-archives")).map { it.key.value })
    assertEquals(
        listOf("group-exports/8/test-group.json"),
        store.list(ObjectKey("group-exports")).map { it.key.value })
  }

  @Test
  fun `the archive artifact is infra only and points at the archive key`() = runBlocking<Unit> {
    group()
    val result = step.run(ctx(), archiveArgs)

    val artifact = step.artifacts(archiveArgs, result).single()

    assertEquals("member-list", artifact.name)
    assertTrue(artifact.infraOnly)
    assertEquals("WORKFLOW_EXPORTS", artifact.namespace)
    assertEquals(result.key, artifact.key)
  }

  @Test
  fun `check recognises an earlier archive upload and returns the full result`() =
      runBlocking<Unit> {
        group()
        val result = step.run(ctx(), archiveArgs)

        val check = step.check(ctx(), archiveArgs)

        assertEquals(result, assertIs<StepCheck.NoOp<ExportGroupMembersStep.Result>>(check).output)
        val otherPrefix = archiveArgs.copy(keyPrefix = "group-exports")
        assertIs<StepCheck.WillRun>(step.check(ctx(), otherPrefix))
      }

  @Test
  fun `an export from before the digest existed is replaced rather than trusted`() =
      runBlocking<Unit> {
        group()
        val result = step.run(ctx(), archiveArgs)
        val head = assertNotNull(store.head(ObjectKey(result.key)))
        val old = head.metadata - "members-sha256" - "unresolved-member-count"
        val bytes = assertNotNull(store.get(ObjectKey(result.key))).bytes
        store.put(ObjectKey(result.key), bytes, "application/json", old)

        assertIs<StepCheck.WillRun>(step.check(ctx(), archiveArgs))
      }

  @Test
  fun `results saved before the new fields existed still decode`() {
    val old =
        """{"key":"k","memberCount":2,"sha256":"x","complete":true,"sourceMemberCount":2,""" +
            """"nestedGroupCount":0}"""

    val decoded = Json.decodeFromString(ExportGroupMembersStep.Result.serializer(), old)

    assertEquals("", decoded.membersSha256)
    assertEquals(0, decoded.unresolvedMemberCount)
    assertNull(decoded.groupObjectGuid)
  }

  @Test
  fun `a key prefix must be a plain lowercase path`() {
    listOf("", "/x", "x/", "X", "a b", "a//b", "../x", "x\n").forEach {
      assertFailsWith<IllegalArgumentException>(it) {
        ExportGroupMembersStep.Args("g", keyPrefix = it)
      }
    }
    ExportGroupMembersStep.Args("g", keyPrefix = "group-archives")
    ExportGroupMembersStep.Args("g", keyPrefix = "a/b-c")
  }
}
