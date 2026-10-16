package org.dallasmakerspace.workflows.groups

import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.activedirectory.ADException
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.GroupNotFoundException
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.models.DirectoryOus
import org.dallasmakerspace.storage.IObjectStore
import org.dallasmakerspace.storage.InMemoryObjectStore
import org.dallasmakerspace.storage.ObjectContent
import org.dallasmakerspace.storage.ObjectKey
import org.dallasmakerspace.storage.ObjectStoreException
import org.dallasmakerspace.storage.ObjectStoreFactory
import org.dallasmakerspace.storage.StorageNamespace
import org.dallasmakerspace.storage.StoredObject
import org.dallasmakerspace.workflows.PermanentStepException
import org.dallasmakerspace.workflows.PublicStep
import org.dallasmakerspace.workflows.Reversibility
import org.dallasmakerspace.workflows.StepCheck
import org.dallasmakerspace.workflows.StepContext
import org.dallasmakerspace.workflows.StepMode
import org.dallasmakerspace.workflows.TransientStepException
import org.dallasmakerspace.workflows.Validation
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import software.amazon.awssdk.services.s3.model.S3Exception

private val NOW = Instant.parse("2030-01-02T03:04:05Z")
private val CLOCK = Clock.fixed(NOW, ZoneOffset.UTC)
private const val NESTED_A = "CN=Nested A,OU=Groups,DC=example,DC=test"
private const val NESTED_B = "CN=Nested B,OU=Groups,DC=example,DC=test"

private fun ctx(
    runId: Long = 7,
    mode: StepMode = StepMode.EXECUTE,
    clock: Clock = CLOCK,
) = StepContext(runId, "jdoe", "TEST", NOW, clock, 1, mode)

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
    nested: List<String> = emptyList(),
    unresolved: Int = 0,
    source: Int? = null,
) =
    ADGroup(
        cn = name,
        description = null,
        distinguishedName = dn,
        objectGuid = "11111111-2222-3333-4444-555555555555",
        members = members,
        membersListIncomplete = incomplete,
        nestedGroups = nested,
        sourceMemberCount = source,
        unresolvedMemberCount = unresolved,
    )

private fun notFound(name: String): Nothing = throw GroupNotFoundException(name)

/** Records the calls made, delegating to [d]. */
private class CountingStore(private val d: IObjectStore) : IObjectStore by d {
  val calls = mutableListOf<String>()

  override suspend fun head(key: ObjectKey): StoredObject? {
    calls += "head"
    return d.head(key)
  }

  override suspend fun get(key: ObjectKey): ObjectContent? {
    calls += "get"
    return d.get(key)
  }

  override suspend fun put(
      key: ObjectKey,
      bytes: ByteArray,
      contentType: String,
      metadata: Map<String, String>
  ): StoredObject {
    calls += "put"
    return d.put(key, bytes, contentType, metadata)
  }
}

private class FailingStore(private val error: () -> ObjectStoreException) :
    IObjectStore by InMemoryObjectStore() {
  override suspend fun head(key: ObjectKey): StoredObject? = throw error()

  override suspend fun put(
      key: ObjectKey,
      bytes: ByteArray,
      contentType: String,
      metadata: Map<String, String>
  ): StoredObject = throw error()
}

class ExportGroupMembersStepTest {
  private val backing = InMemoryObjectStore()
  private val namespaced =
      ObjectStoreFactory(backing).forNamespace(StorageNamespace.WORKFLOW_EXPORTS)
  private val callThreads = mutableListOf<String>()
  private val args = ExportGroupMembersStep.Args("Test Group")

  private fun ad(handler: (String) -> ADGroup): IActiveDirectoryService = mock {
    on { getGroup(any()) } doAnswer { call ->
      callThreads += Thread.currentThread().name
      handler(call.getArgument(0))
    }
  }

  private fun step(
      directory: IActiveDirectoryService = ad { group() },
      store: IObjectStore = backing,
  ) = ExportGroupMembersStep(directory, ObjectStoreFactory(store))

  private fun stepFor(g: ADGroup) = step(ad { g })

  private suspend fun storedDocument(output: ExportGroupMembersStep.Result): JsonObject {
    val content = assertNotNull(namespaced.get(ObjectKey(output.key)))
    return Json.parseToJsonElement(content.bytes.decodeToString()).jsonObject
  }

  private fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int

  // run: document and output

  @Test
  fun `run writes the sorted member document and returns key counts and checksum`() =
      runBlocking<Unit> {
        val output = step().run(ctx(), args)

        assertEquals("group-exports/7/test-group.json", output.key)
        assertEquals(2, output.memberCount)
        assertTrue(output.complete)
        assertEquals(2, output.sourceMemberCount)
        assertEquals(0, output.nestedGroupCount)
        val doc = storedDocument(output)
        assertEquals(
            setOf(
                "schemaVersion",
                "exportedAt",
                "groupName",
                "groupObjectGuid",
                "groupDn",
                "description",
                "managedBy",
                "parentGroups",
                "samAccountName",
                "groupType",
                "memberCount",
                "members",
                "sourceMemberCount",
                "unresolvedMemberCount",
                "nestedGroups",
                "complete",
            ),
            doc.keys)
        assertEquals(2, doc.int("schemaVersion"))
        assertEquals("2030-01-02T03:04:05Z", doc.getValue("exportedAt").jsonPrimitive.content)
        assertEquals("Test Group", doc.getValue("groupName").jsonPrimitive.content)
        assertEquals(
            "11111111-2222-3333-4444-555555555555",
            doc.getValue("groupObjectGuid").jsonPrimitive.content)
        assertEquals(2, doc.int("memberCount"))
        assertEquals(2, doc.int("sourceMemberCount"))
        assertEquals(0, doc.int("unresolvedMemberCount"))
        assertTrue(doc.getValue("complete").jsonPrimitive.boolean)
        assertTrue(doc.getValue("nestedGroups").jsonArray.isEmpty())
        val members = doc.getValue("members").jsonArray
        assertEquals(
            listOf("asmith", "bsmith"),
            members.map { it.jsonObject.getValue("username").jsonPrimitive.content })
        members.forEach { assertEquals(setOf("username", "displayName"), it.jsonObject.keys) }
        assertEquals(
            "Display asmith", members[0].jsonObject.getValue("displayName").jsonPrimitive.content)
      }

  @Test
  fun `run records the result as object metadata including the exact size`() =
      runBlocking<Unit> {
        val output = step().run(ctx(), args)

        val stored = assertNotNull(namespaced.head(ObjectKey(output.key)))
        assertEquals(output.sha256, stored.metadata["sha256"])
        assertEquals("2", stored.metadata["member-count"])
        assertEquals("2", stored.metadata["source-member-count"])
        assertEquals("0", stored.metadata["nested-group-count"])
        assertEquals("true", stored.metadata["complete"])
        assertEquals(stored.sizeBytes.toString(), stored.metadata["size-bytes"])
        assertEquals("application/json", stored.contentType)
        val bytes = assertNotNull(namespaced.get(ObjectKey(output.key))).bytes
        assertEquals(ExportGroupMembersStep.sha256Hex(bytes), output.sha256)
      }

  @Test
  fun `display name falls back to the names then the username and ignores the text null`() =
      runBlocking<Unit> {
        val members =
            listOf(
                user("u1", displayName = null),
                user("u2", displayName = " ").copy(givenName = null, sn = null),
                user("u3", displayName = "null").copy(givenName = "null", sn = "null"),
                user("u4", displayName = "null").copy(givenName = "Given", sn = "null"),
                user("u5", displayName = "Shown").copy(givenName = "null", sn = "null"),
            )
        val output = step(ad { group(members = members) }).run(ctx(), args)

        val shown =
            storedDocument(output).getValue("members").jsonArray.associate {
              it.jsonObject.getValue("username").jsonPrimitive.content to
                  it.jsonObject.getValue("displayName").jsonPrimitive.content
            }
        assertEquals("Given Family", shown["u1"])
        assertEquals("u2", shown["u2"])
        assertEquals("u3", shown["u3"])
        assertEquals("Given", shown["u4"])
        assertEquals("Shown", shown["u5"])
      }

  @Test
  fun `persisted output holds only the key counts flag and checksum`() = runBlocking<Unit> {
    val s = step()
    val output = s.run(ctx(), args)

    val json = Json { encodeDefaults = true }.encodeToString(s.outputSerializer, output)
    val parsed = Json.parseToJsonElement(json).jsonObject
    assertEquals(
        setOf(
            "key",
            "memberCount",
            "sha256",
            "complete",
            "sourceMemberCount",
            "nestedGroupCount",
            "membersSha256",
            "unresolvedMemberCount",
            "groupObjectGuid",
            "groupDn",
            "parentGroupCount",
            "parentGroupsOutsideMainCount"),
        parsed.keys)
    listOf("asmith", "bsmith", "Display", "Given", "Family", "example.invalid").forEach {
      assertFalse(json.contains(it), "output leaks '$it'")
    }
    assertFalse(s.summarize(output).contains("smith"))
  }

  @Test
  fun `summary reads like a count and flags an incomplete export`() {
    val s = step()
    fun result(count: Int, complete: Boolean = true) =
        ExportGroupMembersStep.Result("k", count, "x", complete, count, 0)

    assertEquals("2 members exported", s.summarize(result(2)))
    assertEquals("1 member exported", s.summarize(result(1)))
    assertEquals("0 members exported", s.summarize(result(0)))
    assertEquals("2 members exported (incomplete)", s.summarize(result(2, complete = false)))
  }

  // completeness

  @Test
  fun `a group with only nested groups is complete and lists them without expanding`() =
      runBlocking<Unit> {
        val g = group(members = emptyList(), nested = listOf(NESTED_A, NESTED_B), source = 2)

        val output = stepFor(g).run(ctx(), args)

        assertTrue(output.complete)
        assertEquals(0, output.memberCount)
        assertEquals(2, output.sourceMemberCount)
        assertEquals(2, output.nestedGroupCount)
        val doc = storedDocument(output)
        assertEquals(
            listOf(NESTED_A, NESTED_B), doc.getValue("nestedGroups").jsonArray.map {
              it.jsonPrimitive.content
            })
        assertTrue(doc.getValue("members").jsonArray.isEmpty())
        assertEquals("0 members exported", stepFor(g).summarize(output))
      }

  @Test
  fun `a truncated list is exported but marked incomplete`() = runBlocking<Unit> {
    val g = group(incomplete = true, source = 10_005)

    val output = stepFor(g).run(ctx(), args)

    assertFalse(output.complete)
    assertEquals(10_005, output.sourceMemberCount)
    assertEquals(2, output.memberCount)
    val doc = storedDocument(output)
    assertFalse(doc.getValue("complete").jsonPrimitive.boolean)
    assertEquals(10_005, doc.int("sourceMemberCount"))
    assertEquals("2 members exported (incomplete)", step().summarize(output))
    val head = assertNotNull(namespaced.head(ObjectKey(output.key)))
    assertEquals("false", head.metadata["complete"])
  }

  @Test
  fun `unresolved members mark the export incomplete and are counted`() = runBlocking<Unit> {
    val g = group(unresolved = 1, source = 3)

    val output = stepFor(g).run(ctx(), args)

    assertFalse(output.complete)
    assertEquals(3, output.sourceMemberCount)
    assertEquals(1, storedDocument(output).int("unresolvedMemberCount"))
  }

  @Test
  fun `counts that do not add up mark the export incomplete`() = runBlocking<Unit> {
    val g = group(source = 5)

    assertFalse(stepFor(g).run(ctx(), args).complete)
  }

  @Test
  fun `requireComplete fails permanently instead of uploading an incomplete export`() =
      runBlocking<Unit> {
        val strict = ExportGroupMembersStep.Args("Test Group", requireComplete = true)
        listOf(
                group(incomplete = true, source = 10_005),
                group(unresolved = 1, source = 3),
                group(source = 5))
            .forEach { g ->
              assertFailsWith<PermanentStepException> { stepFor(g).run(ctx(), strict) }
            }

        assertTrue(namespaced.list(ObjectKey("group-exports")).isEmpty())
      }

  @Test
  fun `requireComplete uploads a complete export`() = runBlocking<Unit> {
    val strict = ExportGroupMembersStep.Args("Test Group", requireComplete = true)

    assertTrue(step().run(ctx(), strict).complete)
  }

  // check

  @Test
  fun `check in preflight never touches the object store`() = runBlocking<Unit> {
    val down = FailingStore { ObjectStoreException("down") }

    val check = step(store = down).check(ctx(runId = 0, mode = StepMode.PREFLIGHT), args)

    assertIs<StepCheck.WillRun>(check)
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
  fun `check relies on the stored metadata and does not download or recompute`() =
      runBlocking<Unit> {
        val counting = CountingStore(backing)
        val s = step(store = counting)
        val first = s.run(ctx(clock = Clock.fixed(NOW, ZoneOffset.UTC)), args)
        counting.calls.clear()

        val later = Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC)
        val noOp = s.check(ctx(clock = later), args)

        assertEquals(listOf("head"), counting.calls)
        assertEquals(first, assertIs<StepCheck.NoOp<ExportGroupMembersStep.Result>>(noOp).output)
      }

  @Test
  fun `a later run would produce another checksum yet check still returns the stored one`() =
      runBlocking<Unit> {
        val s = step()
        val first = s.run(ctx(clock = Clock.fixed(NOW, ZoneOffset.UTC)), args)
        val rerun = s.run(ctx(clock = Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC)), args)
        assertNotEquals(first.sha256, rerun.sha256)

        val noOp = assertIs<StepCheck.NoOp<ExportGroupMembersStep.Result>>(s.check(ctx(), args))
        assertEquals(rerun, noOp.output)
      }

  @Test
  fun `check ignores an export from another run`() = runBlocking<Unit> {
    val s = step()
    s.run(ctx(runId = 7), args)

    assertIs<StepCheck.WillRun>(s.check(ctx(runId = 8), args))
  }

  @Test
  fun `check does not trust an object whose size differs from the recorded size`() =
      runBlocking<Unit> {
        val s = step()
        val output = s.run(ctx(), args)
        val head = assertNotNull(namespaced.head(ObjectKey(output.key)))
        namespaced.put(
            ObjectKey(output.key), "tampered".toByteArray(), "application/json", head.metadata)

        assertIs<StepCheck.WillRun>(s.check(ctx(), args))
      }

  @Test
  fun `check does not trust an object with missing or unparsable metadata`() = runBlocking<Unit> {
    val key = ObjectKey("group-exports/7/test-group.json")
    namespaced.put(key, "{}".toByteArray(), "application/json")
    assertIs<StepCheck.WillRun>(step().check(ctx(), args))

    namespaced.put(
        key,
        "{}".toByteArray(),
        "application/json",
        mapOf(
            "sha256" to "abc",
            "member-count" to "x",
            "source-member-count" to "1",
            "nested-group-count" to "0",
            "complete" to "true",
            "size-bytes" to "2",
            "members-sha256" to "abc",
            "unresolved-member-count" to "0"))
    assertIs<StepCheck.WillRun>(step().check(ctx(), args))
  }

  @Test
  fun `check re-exports when completeness is required but the stored export is incomplete`() =
      runBlocking<Unit> {
        val s = stepFor(group(incomplete = true, source = 10_005))
        val output = s.run(ctx(), args)

        val lenient = s.check(ctx(), args)
        val strict = s.check(ctx(), ExportGroupMembersStep.Args("Test Group", true))

        assertEquals(
            output, assertIs<StepCheck.NoOp<ExportGroupMembersStep.Result>>(lenient).output)
        assertIs<StepCheck.WillRun>(strict)
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
    assertEquals(Reversibility.REVERSIBLE, s.reversibility)
    assertIs<PublicStep<*, *>>(s)
  }

  // validate

  private fun preflight() = ctx(runId = 0, mode = StepMode.PREFLIGHT)

  @Test
  fun `validate is Ok for a group in a managed OU`() = runBlocking<Unit> {
    assertEquals(Validation.Ok, step().validate(preflight(), args))
  }

  @Test
  fun `validate accepts a group in the second managed OU`() = runBlocking<Unit> {
    val dn = "CN=Test Group,${DirectoryOus.GROUP_OUS.last()}"

    assertEquals(Validation.Ok, step(ad { group(dn = dn) }).validate(preflight(), args))
  }

  @Test
  fun `validate blocks a group outside the group OUs`() = runBlocking<Unit> {
    val outside = "CN=Test Group,OU=Elsewhere,DC=example,DC=invalid"

    val result = step(ad { group(dn = outside) }).validate(preflight(), args)

    assertTrue(assertIs<Validation.Blocked>(result).messages.single().contains("outside"))
  }

  @Test
  fun `validate blocks a missing group`() = runBlocking<Unit> {
    val result = step(ad { notFound(it) }).validate(preflight(), args)

    assertTrue(assertIs<Validation.Blocked>(result).messages.single().contains("not found"))
  }

  @Test
  fun `validate blocks a blank group name without asking the directory`() = runBlocking<Unit> {
    val result = step().validate(preflight(), ExportGroupMembersStep.Args("  "))

    assertIs<Validation.Blocked>(result)
    assertTrue(callThreads.isEmpty())
  }

  @Test
  fun `validate warns about truncated or unresolved members`() = runBlocking<Unit> {
    val truncated = stepFor(group(incomplete = true, source = 10_005)).validate(preflight(), args)
    val unresolved = stepFor(group(unresolved = 2, source = 4)).validate(preflight(), args)

    assertTrue(assertIs<Validation.Warnings>(truncated).messages.single().contains("too large"))
    assertTrue(assertIs<Validation.Warnings>(unresolved).messages.single().contains("2 members"))
  }

  @Test
  fun `validate blocks an incomplete export when completeness is required`() = runBlocking<Unit> {
    val strict = ExportGroupMembersStep.Args("Test Group", requireComplete = true)

    val result = stepFor(group(incomplete = true, source = 10_005)).validate(preflight(), strict)

    assertIs<Validation.Blocked>(result)
    assertEquals(Validation.Ok, step().validate(preflight(), strict))
  }

  @Test
  fun `validate when executing does not read the group from the directory`() =
      runBlocking<Unit> {
        val s = step(ad { notFound(it) })

        assertEquals(Validation.Ok, s.validate(ctx(), args))
        assertIs<Validation.Blocked>(s.validate(ctx(), ExportGroupMembersStep.Args(" ")))
        assertTrue(callThreads.isEmpty())
      }

  // errors

  @Test
  fun `a directory failure is transient`() = runBlocking<Unit> {
    val s = step(ad { throw IOException("connection reset") })

    assertFailsWith<TransientStepException> { s.validate(preflight(), args) }
    assertFailsWith<TransientStepException> { s.run(ctx(), args) }
  }

  @Test
  fun `an ADException that is not a group lookup miss is transient not a missing group`() =
      runBlocking<Unit> {
        val s = step(ad { throw ADException("Group DN not found") })

        assertFailsWith<TransientStepException> { s.run(ctx(), args) }
        assertFailsWith<TransientStepException> { s.validate(preflight(), args) }
      }

  @Test
  fun `run fails permanently for a missing group and writes nothing`() = runBlocking<Unit> {
    val s = step(ad { notFound(it) })

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
  fun `a retryable or unclassified object store failure is transient`() = runBlocking<Unit> {
    listOf(
            ObjectStoreException("boom", isRetryable = true),
            ObjectStoreException("boom"),
            ObjectStoreException("boom", cause = IOException("reset")))
        .forEach { error ->
          val s = step(store = FailingStore { error })
          assertFailsWith<TransientStepException> { s.run(ctx(), args) }
          assertFailsWith<TransientStepException> { s.check(ctx(), args) }
        }
  }

  @Test
  fun `a store rejection that retrying cannot fix is permanent`() = runBlocking<Unit> {
    val denied = S3Exception.builder().statusCode(403).message("denied").build()
    val rejections =
        listOf(
            ObjectStoreException("denied", cause = denied),
            ObjectStoreException("no bucket", isNotFound = true))
    rejections.forEach { error ->
      val s = step(store = FailingStore { error })
      assertFailsWith<PermanentStepException> { s.run(ctx(), args) }
      assertFailsWith<PermanentStepException> { s.check(ctx(), args) }
    }
  }

  @Test
  fun `a throttled or failing store response stays transient`() = runBlocking<Unit> {
    val busy = S3Exception.builder().statusCode(503).message("busy").build()
    val error = ObjectStoreException("busy", cause = busy, isRetryable = true)

    assertFailsWith<TransientStepException> {
      step(store = FailingStore { error }).run(ctx(), args)
    }
  }

  // dispatcher and input handling

  @Test
  fun `the directory is read on the injected directory dispatcher not the caller`() {
    val executor = Executors.newSingleThreadExecutor { Thread(it, "test-directory-io") }
    try {
      val s =
          ExportGroupMembersStep(
              ad { group() }, ObjectStoreFactory(backing), executor.asCoroutineDispatcher())
      val callerThread = Thread.currentThread().name

      runBlocking {
        s.run(ctx(), args)
        s.validate(preflight(), args)
      }

      assertEquals(2, callThreads.size)
      assertTrue(callThreads.all { it.startsWith("test-directory-io") }, callThreads.toString())
      assertNotEquals(callerThread, callThreads.first())
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `the default directory dispatcher is not the caller thread`() = runBlocking<Unit> {
    val callerThread = Thread.currentThread().name

    step().run(ctx(), args)

    assertNotEquals(callerThread, callThreads.single())
  }

  @Test
  fun `the directory is queried with the trimmed group name`() = runBlocking<Unit> {
    val asked = mutableListOf<String>()
    step(ad {
          asked += it
          group()
        })
        .run(ctx(), ExportGroupMembersStep.Args("  Test Group "))

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
  }

  @Test
  fun `declares the member list artifact from the persisted output key`() = runBlocking<Unit> {
    val output = step().run(ctx(), args)

    val artifact = step().artifacts(args, output).single()

    assertEquals("member-list", artifact.name)
    assertEquals("Member list (JSON)", artifact.label)
    assertEquals("application/json", artifact.contentType)
    assertEquals(StorageNamespace.WORKFLOW_EXPORTS.name, artifact.namespace)
    assertEquals(output.key, artifact.key)
    assertFalse(artifact.infraOnly)
  }

  @Test
  fun `the artifact is infra only when the step arg says so and old args stay compatible`() {
    val output = ExportGroupMembersStep.Result("group-exports/7/x.json", 1, "abc", true, 1, 0)

    val locked = ExportGroupMembersStep.Args("Test Group", downloadInfraOnly = true)

    assertTrue(step().artifacts(locked, output).single().infraOnly)
    assertFalse(ExportGroupMembersStep.Args("Test Group").downloadInfraOnly)
    assertFalse(ExportGroupMembersStep.Args("Test Group", requireComplete = true).downloadInfraOnly)
  }

  @Test
  fun `slug keeps only lowercase letters digits and single dashes`() {
    assertEquals("test-group", ExportGroupMembersStep.slug("Test Group"))
    assertEquals("a-b-c", ExportGroupMembersStep.slug("a / b \\ c"))
    assertEquals("x-y", ExportGroupMembersStep.slug("--x..y--"))
    assertEquals("x", ExportGroupMembersStep.slug("../../x/.."))
    assertEquals("group", ExportGroupMembersStep.slug("???"))
    assertEquals("group", ExportGroupMembersStep.slug(""))
    assertEquals("group", ExportGroupMembersStep.slug("üñï"))
    assertEquals("a1-b2", ExportGroupMembersStep.slug("A1 B2"))
    assertTrue(ExportGroupMembersStep.slug("x".repeat(500)).length <= 60)
    assertFalse(ExportGroupMembersStep.slug("a".repeat(59) + " b").endsWith("-"))
  }

  @Test
  fun `a hostile group name never reaches the key`() {
    val key = ExportGroupMembersStep.objectKey(1, "../../etc/passwd\u0000\n/ ‮ Test").value

    assertEquals("group-exports/1/etc-passwd-test.json", key)
    assertFalse(key.contains(".."))
    assertNull(Regex("[^a-z0-9/.-]").find(key))
  }
}
