package org.dallasmakerspace.workflows.groups

import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.activedirectory.ADException
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.models.DirectoryOus
import org.dallasmakerspace.storage.IObjectStore
import org.dallasmakerspace.storage.InMemoryObjectStore
import org.dallasmakerspace.storage.ObjectKey
import org.dallasmakerspace.storage.ObjectStoreException
import org.dallasmakerspace.storage.ObjectStoreFactory
import org.dallasmakerspace.storage.StorageNamespace
import org.dallasmakerspace.storage.StoredObject
import org.dallasmakerspace.workflows.PermanentStepException
import org.dallasmakerspace.workflows.StepCheck
import org.dallasmakerspace.workflows.StepContext
import org.dallasmakerspace.workflows.StepMode
import org.dallasmakerspace.workflows.TransientStepException
import org.dallasmakerspace.workflows.Validation
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

private val NOW = Instant.parse("2030-01-02T03:04:05Z")
private val CLOCK = Clock.fixed(NOW, ZoneOffset.UTC)

private fun ctx(runId: Long = 7) =
    StepContext(runId, "jdoe", "TEST", NOW, CLOCK, 1, StepMode.EXECUTE)

private fun user(name: String, displayName: String? = "Display $name") =
    ADUser(
        sAMAccountName = name,
        mail = "$name@example.invalid",
        givenName = "Given",
        sn = "Family",
        telephoneNumber = "555-0100",
        employeeID = "1234",
        displayName = displayName,
        objectGuid = "guid-$name",
        whenCreated = null,
        enabled = true,
        groups = emptyList(),
    )

private fun group(
    name: String = "Test Group",
    dn: String = "CN=$name,${DirectoryOus.GROUP_OUS.first()}",
    members: List<ADUser> = listOf(user("bsmith"), user("asmith")),
    incomplete: Boolean = false,
) =
    ADGroup(
        cn = name,
        description = null,
        distinguishedName = dn,
        objectGuid = "11111111-2222-3333-4444-555555555555",
        members = members,
        membersListIncomplete = incomplete,
    )

class ExportGroupMembersStepTest {
  private val backing = InMemoryObjectStore()
  private val namespaced =
      ObjectStoreFactory(backing).forNamespace(StorageNamespace.WORKFLOW_EXPORTS)
  private val callThreads = mutableListOf<String>()

  private fun ad(handler: (String) -> ADGroup): IActiveDirectoryService =
      mock {
        on { getGroup(any()) } doAnswer { call ->
          callThreads += Thread.currentThread().name
          handler(call.getArgument(0))
        }
      }

  private fun step(
      directory: IActiveDirectoryService = ad { group() },
      store: IObjectStore = backing,
  ) = ExportGroupMembersStep(directory, ObjectStoreFactory(store))

  private val args = ExportGroupMembersStep.Args("Test Group")

  private suspend fun storedDocument(output: ExportGroupMembersStep.Result): JsonObject {
    val content = assertNotNull(namespaced.get(ObjectKey(output.key)))
    return Json.parseToJsonElement(content.bytes.decodeToString()).jsonObject
  }

  @Test
  fun `run writes the sorted member document and returns key count and checksum`() =
      runBlocking<Unit> {
        val output = step().run(ctx(), args)

        assertEquals("group-exports/7/test-group.json", output.key)
        assertEquals(2, output.memberCount)
        val doc = storedDocument(output)
        assertEquals(
            setOf(
                "schemaVersion",
                "exportedAt",
                "groupName",
                "groupObjectGuid",
                "memberCount",
                "members",
            ),
            doc.keys)
        assertEquals(1, doc.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("2030-01-02T03:04:05Z", doc.getValue("exportedAt").jsonPrimitive.content)
        assertEquals("Test Group", doc.getValue("groupName").jsonPrimitive.content)
        assertEquals(
            "11111111-2222-3333-4444-555555555555",
            doc.getValue("groupObjectGuid").jsonPrimitive.content)
        assertEquals(2, doc.getValue("memberCount").jsonPrimitive.content.toInt())
        val members = doc.getValue("members").jsonArray
        assertEquals(
            listOf("asmith", "bsmith"),
            members.map { it.jsonObject.getValue("username").jsonPrimitive.content })
        members.forEach { assertEquals(setOf("username", "displayName"), it.jsonObject.keys) }
        assertEquals(
            "Display asmith", members[0].jsonObject.getValue("displayName").jsonPrimitive.content)
      }

  @Test
  fun `run stores the checksum of the uploaded bytes as metadata`() = runBlocking<Unit> {
    val output = step().run(ctx(), args)

    val stored = assertNotNull(namespaced.head(ObjectKey(output.key)))
    assertEquals(output.sha256, stored.metadata["sha256"])
    assertEquals("2", stored.metadata["member-count"])
    assertEquals("application/json", stored.contentType)
    val bytes = assertNotNull(namespaced.get(ObjectKey(output.key))).bytes
    assertEquals(ExportGroupMembersStep.sha256Hex(bytes), output.sha256)
  }

  @Test
  fun `display name falls back to the given and family names then to the username`() =
      runBlocking<Unit> {
        val members =
            listOf(
                user("u1", displayName = null),
                user("u2", displayName = " ").copy(givenName = null, sn = null))
        val output = step(ad { group(members = members) }).run(ctx(), args)

        val shown =
            storedDocument(output).getValue("members").jsonArray.associate {
              it.jsonObject.getValue("username").jsonPrimitive.content to
                  it.jsonObject.getValue("displayName").jsonPrimitive.content
            }
        assertEquals("Given Family", shown["u1"])
        assertEquals("u2", shown["u2"])
      }

  @Test
  fun `persisted output contains only the key count and checksum`() = runBlocking<Unit> {
    val s = step()
    val output = s.run(ctx(), args)

    val json = Json.encodeToString(s.outputSerializer, output)
    val parsed = Json.parseToJsonElement(json).jsonObject
    assertEquals(setOf("key", "memberCount", "sha256"), parsed.keys)
    listOf("asmith", "bsmith", "Display", "Given", "Family", "example.invalid").forEach {
      assertFalse(json.contains(it), "output leaks '$it'")
    }
    assertFalse(s.summarize(output)!!.contains("smith"))
  }

  @Test
  fun `summary reads like a count`() {
    val s = step()
    assertEquals("2 members exported", s.summarize(ExportGroupMembersStep.Result("k", 2, "x")))
    assertEquals("1 member exported", s.summarize(ExportGroupMembersStep.Result("k", 1, "x")))
    assertEquals("0 members exported", s.summarize(ExportGroupMembersStep.Result("k", 0, "x")))
  }

  @Test
  fun `check is WillRun before the export and NoOp with the same output after it`() =
      runBlocking<Unit> {
        val s = step()
        assertIs<StepCheck.WillRun>(s.check(ctx(), args))

        val output = s.run(ctx(), args)
        val check = s.check(ctx(), args)

        val noOp = assertIs<StepCheck.NoOp<ExportGroupMembersStep.Result>>(check)
        assertEquals(output, noOp.output)
      }

  @Test
  fun `check ignores an export from another run`() = runBlocking<Unit> {
    val s = step()
    s.run(ctx(runId = 7), args)

    assertIs<StepCheck.WillRun>(s.check(ctx(runId = 8), args))
  }

  @Test
  fun `check does not trust an object whose bytes do not match the recorded checksum`() =
      runBlocking<Unit> {
        val s = step()
        val output = s.run(ctx(), args)
        val key = ObjectKey(output.key)
        namespaced.put(
            key,
            "tampered".toByteArray(),
            "application/json",
            mapOf("sha256" to output.sha256, "member-count" to "2"))

        assertIs<StepCheck.WillRun>(s.check(ctx(), args))
      }

  @Test
  fun `check does not trust an object without checksum metadata`() = runBlocking<Unit> {
    val key = ObjectKey("group-exports/7/test-group.json")
    namespaced.put(key, "{}".toByteArray(), "application/json")

    assertIs<StepCheck.WillRun>(step().check(ctx(), args))
  }

  @Test
  fun `running twice is idempotent and leaves one object with the same output`() =
      runBlocking<Unit> {
        val s = step()
        val first = s.run(ctx(), args)
        val second = s.run(ctx(), args)

        assertEquals(first, second)
        val keys = namespaced.list(ObjectKey("group-exports")).map { it.key.value }
        assertEquals(listOf("group-exports/7/test-group.json"), keys)
      }

  @Test
  fun `a re-run after the group changed replaces the object for the same run`() =
      runBlocking<Unit> {
        var members = listOf(user("asmith"))
        val s = step(ad { group(members = members) })
        s.run(ctx(), args)
        members = listOf(user("asmith"), user("bsmith"))
        val second = s.run(ctx(), args)

        assertEquals(2, second.memberCount)
        assertEquals(1, namespaced.list(ObjectKey("group-exports")).size)
        val noOp = assertIs<StepCheck.NoOp<ExportGroupMembersStep.Result>>(s.check(ctx(), args))
        assertEquals(second, noOp.output)
      }

  @Test
  fun `rollback is a no-op that keeps the snapshot`() = runBlocking<Unit> {
    val s = step()
    val output = s.run(ctx(), args)

    s.rollback(ctx(), args, output)
    s.rollback(ctx(), args, output)

    assertNotNull(namespaced.head(ObjectKey(output.key)))
  }

  @Test
  fun `step is reversible and public`() {
    val s = step()
    assertEquals(org.dallasmakerspace.workflows.Reversibility.REVERSIBLE, s.reversibility)
    assertIs<org.dallasmakerspace.workflows.PublicStep<*, *>>(s)
  }

  @Test
  fun `validate is Ok for a group in a managed OU`() = runBlocking<Unit> {
    assertEquals(Validation.Ok, step().validate(ctx(), args))
  }

  @Test
  fun `validate accepts a group in the second managed OU`() = runBlocking<Unit> {
    val dn = "CN=Test Group,${DirectoryOus.GROUP_OUS.last()}"

    assertEquals(Validation.Ok, step(ad { group(dn = dn) }).validate(ctx(), args))
  }

  @Test
  fun `validate blocks a group outside the group OUs`() = runBlocking<Unit> {
    val outside = "CN=Test Group,OU=Elsewhere,DC=example,DC=invalid"

    val result = step(ad { group(dn = outside) }).validate(ctx(), args)

    val blocked = assertIs<Validation.Blocked>(result)
    assertTrue(blocked.messages.single().contains("outside"))
  }

  @Test
  fun `validate blocks a missing group`() = runBlocking<Unit> {
    val result = step(ad { throw ADException("Group $it not found in AD") }).validate(ctx(), args)

    val blocked = assertIs<Validation.Blocked>(result)
    assertTrue(blocked.messages.single().contains("not found"))
  }

  @Test
  fun `validate blocks a blank group name without asking the directory`() = runBlocking<Unit> {
    val directory = ad { group() }

    val result = step(directory).validate(ctx(), ExportGroupMembersStep.Args("  "))

    assertIs<Validation.Blocked>(result)
    assertTrue(callThreads.isEmpty())
  }

  @Test
  fun `validate warns when the member list is incomplete`() = runBlocking<Unit> {
    val result = step(ad { group(incomplete = true) }).validate(ctx(), args)

    assertIs<Validation.Warnings>(result)
  }

  @Test
  fun `a directory failure other than not found is transient`() = runBlocking<Unit> {
    val s = step(ad { throw IOException("connection reset") })

    assertFailsWith<TransientStepException> { s.validate(ctx(), args) }
    assertFailsWith<TransientStepException> { s.run(ctx(), args) }
  }

  @Test
  fun `a non-not-found ADException is transient`() = runBlocking<Unit> {
    val s = step(ad { throw ADException("Group DN missing") })

    assertFailsWith<TransientStepException> { s.run(ctx(), args) }
  }

  @Test
  fun `run fails permanently for a missing group and writes nothing`() = runBlocking<Unit> {
    val s = step(ad { throw ADException("Group $it not found in AD") })

    assertFailsWith<PermanentStepException> { s.run(ctx(), args) }
    assertTrue(namespaced.list(ObjectKey("group-exports")).isEmpty())
  }

  @Test
  fun `run fails permanently for a group outside the group OUs and writes nothing`() =
      runBlocking<Unit> {
        val s = step(ad { group(dn = "CN=Test Group,OU=Elsewhere,DC=example,DC=invalid") })

        assertFailsWith<PermanentStepException> { s.run(ctx(), args) }
        assertTrue(namespaced.list(ObjectKey("group-exports")).isEmpty())
      }

  @Test
  fun `an object store failure is transient`() = runBlocking<Unit> {
    val failing =
        object : IObjectStore by backing {
          override suspend fun put(
              key: ObjectKey,
              bytes: ByteArray,
              contentType: String,
              metadata: Map<String, String>
          ): StoredObject = throw ObjectStoreException("boom", isRetryable = true)

          override suspend fun head(key: ObjectKey): StoredObject? =
              throw ObjectStoreException("boom")
        }
    val s = step(store = failing)

    assertFailsWith<TransientStepException> { s.run(ctx(), args) }
    assertFailsWith<TransientStepException> { s.check(ctx(), args) }
  }

  @Test
  fun `the directory is read off the caller thread`() = runBlocking<Unit> {
    step().run(ctx(), args)

    assertTrue(callThreads.isNotEmpty())
    assertTrue(
        callThreads.all { it.startsWith("DefaultDispatcher-worker") }, callThreads.toString())
  }

  @Test
  fun `the directory is queried with the trimmed group name`() = runBlocking<Unit> {
    val asked = mutableListOf<String>()
    step(ad { asked += it; group() }).run(ctx(), ExportGroupMembersStep.Args("  Test Group "))

    assertEquals(listOf("Test Group"), asked)
  }

  @Test
  fun `object keys are derived from the run id and sanitized`() {
    assertEquals(
        ObjectKey("group-exports/42/test-group.json"),
        ExportGroupMembersStep.objectKey(42, "Test Group"))
    assertEquals(
        ExportGroupMembersStep.objectKey(5, "Test Group"),
        ExportGroupMembersStep.objectKey(5, "Test Group"))

    assertEquals("x", ExportGroupMembersStep.slug("../../x/.."))
  }

  @Test
  fun `slug keeps only lowercase letters digits and single dashes`() {
    assertEquals("test-group", ExportGroupMembersStep.slug("Test Group"))
    assertEquals("a-b-c", ExportGroupMembersStep.slug("a / b \\ c"))
    assertEquals("x-y", ExportGroupMembersStep.slug("--x..y--"))
    assertEquals("group", ExportGroupMembersStep.slug("???"))
    assertEquals("group", ExportGroupMembersStep.slug(""))
    assertEquals("group", ExportGroupMembersStep.slug("üñï"))
    assertEquals("a1-b2", ExportGroupMembersStep.slug("A1 B2"))
    assertTrue(ExportGroupMembersStep.slug("x".repeat(500)).length <= 60)
    assertFalse(ExportGroupMembersStep.slug("a".repeat(59) + " b").endsWith("-"))
  }

  @Test
  fun `a hostile group name never reaches the key`() {
    val key =
        ExportGroupMembersStep.objectKey(1, "../../etc/passwd\u0000\n/ ‮ Test").value

    assertEquals("group-exports/1/etc-passwd-test.json", key)
    assertFalse(key.contains(".."))
    assertNull(Regex("[^a-z0-9/.-]").find(key))
  }
}
