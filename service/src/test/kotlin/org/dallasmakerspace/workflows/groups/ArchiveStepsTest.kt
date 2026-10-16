package org.dallasmakerspace.workflows.groups

import java.io.IOException
import java.time.Clock
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.dallasmakerspace.activedirectory.ManagedObjectCounts
import org.dallasmakerspace.storage.InMemoryObjectStore
import org.dallasmakerspace.storage.ObjectKey
import org.dallasmakerspace.storage.ObjectStoreFactory
import org.dallasmakerspace.storage.StorageNamespace
import org.dallasmakerspace.workflows.PermanentStepException
import org.dallasmakerspace.workflows.StepCheck
import org.dallasmakerspace.workflows.StepContext
import org.dallasmakerspace.workflows.StepMode
import org.dallasmakerspace.workflows.TransientStepException
import org.dallasmakerspace.workflows.Validation
import org.dallasmakerspace.workflows.groups.references.GroupReference
import org.dallasmakerspace.workflows.groups.references.GroupReferenceChecker
import org.dallasmakerspace.workflows.groups.references.GroupReferenceFinder
import org.dallasmakerspace.workflows.groups.references.GroupReferenceKinds
import org.dallasmakerspace.workflows.groups.references.ManagedObjectsReferenceFinder
import org.junit.Test

private const val GROUP = "Test Group"
private val NOW = Instant.parse("2030-01-02T03:04:05Z")

private fun ctx(mode: StepMode = StepMode.EXECUTE, runId: Long = 7, actor: String = "jdoe") =
    StepContext(runId, actor, "TEST", NOW, Clock.systemUTC(), 1, mode)

private fun adminGroup() =
    FakeGroup(
        "Admin Group", dn = "CN=Admin Group,$ADMIN_OU", guid = "aaaaaaaa-0000-0000-0000-000000000001")

private fun subOuTwin() =
    FakeGroup(GROUP, dn = "CN=$GROUP,OU=Sub,$MAIN_OU", guid = "bbbbbbbb-0000-0000-0000-000000000002")

private class Env(finders: Set<GroupReferenceFinder> = emptySet()) {
  val directory = FakeDirectory().also { it.add(FakeGroup(GROUP)) }
  val backing = InMemoryObjectStore()
  val factory = ObjectStoreFactory(backing)
  val store = factory.forNamespace(StorageNamespace.WORKFLOW_EXPORTS)
  val archive = RecordingArchive()
  val history = RecordingGroupHistory()
  val export = ExportGroupMembersStep(directory, factory)
  val checker = GroupReferenceChecker(finders)
  val delete = DeleteAdGroupStep(directory, factory, archive, checker)
  val mark = MarkGroupDisabledStep(directory, history, archive)
  val finalize = FinalizeGroupArchiveStep(archive)
  val check = CheckGroupArchivableStep(directory, checker, Dispatchers.IO)

  suspend fun snapshot(name: String = GROUP): ExportGroupMembersStep.Result =
      export.run(
          ctx(),
          ExportGroupMembersStep.Args(
              name, true, true, ExportGroupMembersStep.ARCHIVE_KEY_PREFIX))

  fun group() = directory.find(GROUP)!!
}

class ArchiveStepsTest {

  // CheckGroupArchivable

  @Test
  fun `the gate passes a healthy group and never writes`() = runBlocking<Unit> {
    val env = Env()

    val out = env.check.run(ctx(), CheckGroupArchivableStep.Args(GROUP))

    assertEquals("The group can be archived", out)
    assertEquals(out, env.check.summarize(out))
    assertTrue(env.directory.writes().isEmpty())
  }

  @Test
  fun `the gate re-checks when executing and throws a permanent error if blocked`() =
      runBlocking<Unit> {
        val blockers =
            listOf<(Env) -> Unit>(
                { it.group().nested = listOf("CN=Inner,$MAIN_OU") },
                { it.directory.add(FakeGroup(GROUP, dn = "CN=$GROUP,$ADMIN_OU")) },
                { it.directory.twins += subOuTwin() },
            )
        blockers.forEach { block ->
          val env = Env()
          block(env)

          val thrown =
              assertFailsWith<PermanentStepException> {
                env.check.run(ctx(), CheckGroupArchivableStep.Args(GROUP))
              }

          assertTrue(thrown.message!!.isNotBlank())
        }
      }

  @Test
  fun `the gate blocks a protected group and a referenced group`() = runBlocking<Unit> {
    val referenced =
        Env(
            setOf(
                StaticFinder(
                    "Moodle",
                    listOf(GroupReference("Moodle", GroupReferenceKinds.COURSE, 3, "3 courses")))))
    val protected = Env()
    protected.directory.add(FakeGroup("Board"))

    val byReference =
        assertFailsWith<PermanentStepException> {
          referenced.check.run(ctx(), CheckGroupArchivableStep.Args(GROUP))
        }
    val byName =
        assertFailsWith<PermanentStepException> {
          protected.check.run(ctx(), CheckGroupArchivableStep.Args("Board"))
        }

    assertEquals("Moodle: 3 courses", byReference.message)
    assertTrue(byName.message!!.startsWith("Protected group:"))
  }

  @Test
  fun `the gate reports a missing group as permanent and a directory failure as transient`() =
      runBlocking<Unit> {
        val env = Env()

        assertFailsWith<PermanentStepException> {
          env.check.run(ctx(), CheckGroupArchivableStep.Args("Nope"))
        }
        assertFailsWith<PermanentStepException> {
          env.check.run(ctx(), CheckGroupArchivableStep.Args("  "))
        }
        env.directory.onGetGroup = { throw IOException("directory down") }
        assertFailsWith<TransientStepException> {
          env.check.run(ctx(), CheckGroupArchivableStep.Args(GROUP))
        }
      }

  @Test
  fun `the gate validation is cheap when executing and full when planning`() = runBlocking<Unit> {
    val env = Env()
    env.group().nested = listOf("CN=Inner,$MAIN_OU")

    val executing = env.check.validate(ctx(), CheckGroupArchivableStep.Args(GROUP))
    assertEquals(Validation.Ok, executing)
    assertTrue(env.directory.calls.isEmpty())

    val planning = env.check.validate(ctx(StepMode.PREFLIGHT), CheckGroupArchivableStep.Args(GROUP))
    assertIs<Validation.Blocked>(planning)
  }

  @Test
  fun `the gate never has anything to undo`() = runBlocking<Unit> {
    val env = Env()
    assertIs<StepCheck.WillRun>(env.check.check(ctx(), CheckGroupArchivableStep.Args(GROUP)))
    env.check.rollback(ctx(), CheckGroupArchivableStep.Args(GROUP), "x")
    assertTrue(env.directory.calls.isEmpty())
  }

  // MarkGroupDisabled

  @Test
  fun `mark ensures the portal row from the directory identity, then disables it`() =
      runBlocking<Unit> {
        val env = Env()

        env.mark.run(ctx(), MarkGroupDisabledStep.Args(GROUP))

        assertEquals(
            Triple(GROUP, "CN=$GROUP,$MAIN_OU", "11111111-2222-3333-4444-555555555555"),
            env.history.resolved.single())
        assertEquals("DISABLED", env.archive.statusOf(GROUP))
        assertEquals("Group marked disabled", env.mark.summarize(Unit))
      }

  @Test
  fun `mark is a no-op when already disabled and can be rolled back`() = runBlocking<Unit> {
    val env = Env()
    assertIs<StepCheck.WillRun>(env.mark.check(ctx(), MarkGroupDisabledStep.Args(GROUP)))
    env.mark.run(ctx(), MarkGroupDisabledStep.Args(GROUP))

    assertIs<StepCheck.NoOp<Unit>>(env.mark.check(ctx(), MarkGroupDisabledStep.Args(GROUP)))
    env.mark.rollback(ctx(), MarkGroupDisabledStep.Args(GROUP), Unit)

    assertEquals("ACTIVE", env.archive.statusOf(GROUP))
    env.mark.rollback(ctx(), MarkGroupDisabledStep.Args(GROUP), Unit)
    assertEquals("ACTIVE", env.archive.statusOf(GROUP))
  }

  @Test
  fun `mark fails permanently for a missing group and transiently if the row is not updated`() =
      runBlocking<Unit> {
        val env = Env()

        assertFailsWith<PermanentStepException> {
          env.mark.run(ctx(), MarkGroupDisabledStep.Args("Nope"))
        }
        env.archive.markDisabledResult = false
        assertFailsWith<TransientStepException> {
          env.mark.run(ctx(), MarkGroupDisabledStep.Args(GROUP))
        }
      }

  @Test
  fun `mark warns when a group of that name was archived before`() = runBlocking<Unit> {
    val env = Env()
    env.archive.markDisabled("Old Group")
    env.archive.finalizeArchived("Old Group", "asmith", "an earlier archive", 1L)

    assertIs<Validation.Warnings>(env.mark.validate(ctx(), MarkGroupDisabledStep.Args("Old Group")))
    assertEquals(Validation.Ok, env.mark.validate(ctx(), MarkGroupDisabledStep.Args(GROUP)))
    assertIs<Validation.Blocked>(env.mark.validate(ctx(), MarkGroupDisabledStep.Args(" ")))
  }

  // DeleteAdGroup

  private suspend fun Env.validateDelete(
      snapshot: ExportGroupMembersStep.Result?,
      mode: StepMode = StepMode.EXECUTE,
      name: String = GROUP,
  ) = delete.validate(ctx(mode), DeleteAdGroupStep.Args(name, snapshot))

  @Test
  fun `delete accepts an unchanged membership`() = runBlocking<Unit> {
    val env = Env()
    val snap = env.snapshot()

    assertEquals(Validation.Ok, env.validateDelete(snap))
  }

  @Test
  fun `delete blocks when the membership differs from the snapshot in any way`() =
      runBlocking<Unit> {
        val changes =
            listOf<(FakeGroup) -> Unit>(
                { it.members += "dsmith" },
                { it.members -= "csmith" },
                { it.members.remove("csmith"); it.members += "dsmith" },
            )
        changes.forEach { change ->
          val env = Env()
          val snap = env.snapshot()
          change(env.group())

          val result = assertIs<Validation.Blocked>(env.validateDelete(snap))

          assertTrue(result.messages.single().contains("members changed"))
          assertFalse(result.messages.joinToString().contains("smith"))
        }
      }

  @Test
  fun `delete blocks when nested, unresolved or truncated counts differ`() = runBlocking<Unit> {
    val env = Env()
    val snap = env.snapshot()

    suspend fun messages() = assertIs<Validation.Blocked>(env.validateDelete(snap)).messages

    env.group().unresolved = 1
    assertTrue(messages().single().contains("unresolved"))
    env.group().unresolved = 0
    env.group().nested = listOf("CN=Inner,$MAIN_OU")
    assertTrue(messages().single().contains("nested"))
    env.group().nested = emptyList()
    env.group().incomplete = true
    assertTrue(messages().any { it.contains("too large") })
  }

  @Test
  fun `delete blocks a group outside the main OU even with a matching snapshot`() =
      runBlocking<Unit> {
        val env = Env()
        env.directory.add(adminGroup())
        val snap = env.snapshot("Admin Group")

        val result = env.validateDelete(snap, name = "Admin Group")

        assertTrue(assertIs<Validation.Blocked>(result).messages.single().contains("outside"))
      }

  @Test
  fun `delete needs the snapshot when executing but not when planning`() = runBlocking<Unit> {
    val env = Env()

    assertEquals(Validation.Ok, env.validateDelete(null, StepMode.PREFLIGHT))
    assertIs<Validation.Blocked>(env.validateDelete(null, StepMode.EXECUTE))
    assertEquals(Validation.Ok, env.validateDelete(null, StepMode.PREFLIGHT, name = "Nope"))
  }

  @Test
  fun `delete removes the group, is idempotent, and check turns into a no-op`() =
      runBlocking<Unit> {
        val env = Env()
        val snap = env.snapshot()
        val args = DeleteAdGroupStep.Args(GROUP, snap)
        assertIs<StepCheck.WillRun>(env.delete.check(ctx(), args))

        val first = env.delete.run(ctx(), args)

        assertEquals(
            DeleteAdGroupStep.Result(GROUP, "11111111-2222-3333-4444-555555555555", true), first)
        assertNull(env.directory.find(GROUP))
        val noOp = assertIs<StepCheck.NoOp<DeleteAdGroupStep.Result>>(env.delete.check(ctx(), args))
        assertFalse(noOp.output!!.deleted)
        assertEquals(first.objectGuid, noOp.output!!.objectGuid)
        assertEquals("Group deleted from the directory", env.delete.summarize(first))
        assertEquals("Group was already absent", env.delete.summarize(noOp.output!!))
        assertFailsWith<PermanentStepException> { env.delete.run(ctx(), args) }
      }

  @Test
  fun `delete refuses a group outside the main OU and leaves it alone`() = runBlocking<Unit> {
    val env = Env()
    env.directory.add(adminGroup())

    assertFailsWith<PermanentStepException> {
      env.delete.run(ctx(), DeleteAdGroupStep.Args("Admin Group", env.snapshot("Admin Group")))
    }

    assertNotNull(env.directory.find("Admin Group"))
  }

  // DeleteAdGroup rollback

  private suspend fun Env.deleteGroup():
      Pair<ExportGroupMembersStep.Result, DeleteAdGroupStep.Args> {
    val snap = snapshot()
    val args = DeleteAdGroupStep.Args(GROUP, snap)
    archive.markDisabled(GROUP)
    delete.run(ctx(), args)
    return snap to args
  }

  @Test
  fun `rollback recreates the group at its old location with members and attributes`() =
      runBlocking<Unit> {
        val env = Env()
        env.group().parents = listOf("CN=Parent,$MAIN_OU")
        val (_, args) = env.deleteGroup()
        env.directory.calls.clear()

        env.delete.rollback(ctx(), args, DeleteAdGroupStep.Result(GROUP, null, true))

        val group = assertNotNull(env.directory.find(GROUP))
        assertEquals("CN=$GROUP,$MAIN_OU", group.dn)
        assertEquals("A fake group", group.description)
        assertEquals("CN=Fake Owner,OU=Users,DC=example,DC=invalid", group.managedBy)
        assertEquals(setOf("asmith", "bsmith", "csmith"), group.members)
        assertEquals(1, env.archive.guidUpdates.size)
        assertEquals(GROUP to group.guid, env.archive.guidUpdates.single())
        assertEquals(listOf("CN=Parent,$MAIN_OU"), group.parents)
        assertEquals(
            listOf(
                "createGroup:$GROUP",
                "addUsersToGroup:$GROUP:asmith,bsmith,csmith",
                "addGroupToParentGroup:$GROUP:CN=Parent,$MAIN_OU"),
            env.directory.writes())
      }

  @Test
  fun `rollback twice recreates nothing new and adds nobody twice`() = runBlocking<Unit> {
    val env = Env()
    val (_, args) = env.deleteGroup()
    val out = DeleteAdGroupStep.Result(GROUP, null, true)
    env.delete.rollback(ctx(), args, out)
    env.directory.calls.clear()

    env.delete.rollback(ctx(), args, out)

    assertEquals(listOf("createGroup:$GROUP"), env.directory.writes())
    assertEquals(setOf("asmith", "bsmith", "csmith"), env.group().members)
  }

  @Test
  fun `rollback adds only the members the group lacks`() = runBlocking<Unit> {
    val env = Env()
    val (_, args) = env.deleteGroup()
    env.delete.rollback(ctx(), args, DeleteAdGroupStep.Result(GROUP, null, true))
    env.group().members -= "bsmith"
    env.directory.calls.clear()

    env.delete.rollback(ctx(), args, DeleteAdGroupStep.Result(GROUP, null, true))

    assertEquals(
        listOf("createGroup:$GROUP", "addUsersToGroup:$GROUP:bsmith"), env.directory.writes())
  }

  @Test
  fun `rollback skips vanished members but fails if a real member cannot be re-added`() =
      runBlocking<Unit> {
        val env = Env()
        val (_, args) = env.deleteGroup()
        env.directory.knownUsers -= "csmith"

        env.delete.rollback(ctx(), args, DeleteAdGroupStep.Result(GROUP, null, true))

        assertEquals(setOf("asmith", "bsmith"), env.group().members)

        val stuck = Env()
        val (_, stuckArgs) = stuck.deleteGroup()
        stuck.directory.ignoreAdds = true
        val thrown =
            assertFailsWith<TransientStepException> {
              stuck.delete.rollback(ctx(), stuckArgs, DeleteAdGroupStep.Result(GROUP, null, true))
            }
        assertEquals("3 members could not be re-added", thrown.message)
      }

  @Test
  fun `rollback refuses a snapshot that fails its checksum, is missing or names a wrong place`() =
      runBlocking<Unit> {
        val env = Env()
        val (snap, args) = env.deleteGroup()
        val out = DeleteAdGroupStep.Result(GROUP, null, true)

        assertFailsWith<PermanentStepException> {
          env.delete.rollback(ctx(), DeleteAdGroupStep.Args(GROUP, null), out)
        }

        val stored = assertNotNull(env.store.get(ObjectKey(snap.key)))
        val tampered = stored.bytes.decodeToString().replace("asmith", "xsmith")
        env.store.put(ObjectKey(snap.key), tampered.toByteArray(), "application/json")
        assertFailsWith<PermanentStepException> { env.delete.rollback(ctx(), args, out) }
        assertNull(env.directory.find(GROUP))

        env.store.put(ObjectKey(snap.key), stored.bytes, "application/json")
        val wrongPlace =
            stored.bytes.decodeToString().replace("CN=$GROUP,$MAIN_OU", "CN=$GROUP,$ADMIN_OU")
        env.store.put(ObjectKey(snap.key), wrongPlace.toByteArray(), "application/json")
        assertFailsWith<PermanentStepException> { env.delete.rollback(ctx(), args, out) }
        assertNull(env.directory.find(GROUP))

        env.store.delete(ObjectKey(snap.key))
        assertFailsWith<PermanentStepException> { env.delete.rollback(ctx(), args, out) }
        assertTrue(env.directory.writes().none { it.startsWith("createGroup") })
      }

  @Test
  fun `rollback never writes outside the main OU even if the document is edited`() =
      runBlocking<Unit> {
        val env = Env()
        val (snap, args) = env.deleteGroup()
        val stored = assertNotNull(env.store.get(ObjectKey(snap.key)))
        val doc =
            Json { ignoreUnknownKeys = true }
                .decodeFromString(
                    GroupExportDocument.serializer(), stored.bytes.decodeToString())
        val moved = doc.copy(groupDn = "CN=$GROUP,OU=Elsewhere,DC=example,DC=invalid")
        env.store.put(
            ObjectKey(snap.key),
            Json.encodeToString(GroupExportDocument.serializer(), moved).toByteArray(),
            "application/json")

        assertFailsWith<PermanentStepException> {
          env.delete.rollback(ctx(), args, DeleteAdGroupStep.Result(GROUP, null, true))
        }

        assertTrue(env.directory.writes().none { it.startsWith("createGroup") })
      }

  // FinalizeGroupArchive

  @Test
  fun `finalize records the actor, reason, run and old GUID`() = runBlocking<Unit> {
    val env = Env()
    env.archive.markDisabled(GROUP)

    env.finalize.run(
        ctx(runId = 41, actor = "asmith"),
        FinalizeGroupArchiveStep.Args(GROUP, "  because we said so  ", "guid-1"))

    assertEquals(
        listOf(GROUP, "asmith", "because we said so", 41L, "guid-1"),
        env.archive.finalized.single())
    assertEquals("ARCHIVED", env.archive.statusOf(GROUP))
  }

  @Test
  fun `finalize is a no-op once archived, fails permanently without a row, and has no rollback`() =
      runBlocking<Unit> {
        val env = Env()
        val args = FinalizeGroupArchiveStep.Args(GROUP, "a reason here", null)
        assertIs<StepCheck.WillRun>(env.finalize.check(ctx(), args))
        assertIs<StepCheck.WillRun>(env.finalize.check(ctx(StepMode.PREFLIGHT), args))
        env.archive.markDisabled(GROUP)
        env.finalize.run(ctx(), args)
        assertIs<StepCheck.NoOp<Unit>>(env.finalize.check(ctx(), args))

        env.finalize.rollback(ctx(), args, Unit)

        assertEquals("ARCHIVED", env.archive.statusOf(GROUP))

        env.archive.finalizeSucceeds = false
        assertFailsWith<PermanentStepException> {
          env.finalize.run(ctx(), FinalizeGroupArchiveStep.Args("Other", "a reason here", null))
        }
      }

  // identity: the snapshotted group, not just a name

  @Test
  fun `delete blocks a group deleted and recreated with identical members`() = runBlocking<Unit> {
    val env = Env()
    val snap = env.snapshot()
    env.directory.groups.remove(GROUP.lowercase())
    env.directory.add(FakeGroup(GROUP, guid = "cccccccc-0000-0000-0000-000000000003"))

    val result = assertIs<Validation.Blocked>(env.validateDelete(snap))

    assertTrue(result.messages.single().contains("not the group that was snapshotted"))
    assertTrue(env.directory.writes().isEmpty())
  }

  @Test
  fun `delete blocks a group whose GUID matches but whose location moved`() = runBlocking<Unit> {
    val env = Env()
    val snap = env.snapshot()
    env.group().dn = "CN=$GROUP,OU=Sub,$MAIN_OU"

    val result = assertIs<Validation.Blocked>(env.validateDelete(snap))

    assertTrue(result.messages.single().contains("not the group that was snapshotted"))
  }

  @Test
  fun `delete blocks when the snapshot carries no group identity`() = runBlocking<Unit> {
    val env = Env()
    val snap = env.snapshot().copy(groupObjectGuid = null, groupDn = null)

    assertIs<Validation.Blocked>(env.validateDelete(snap))
    assertFailsWith<PermanentStepException> {
      env.delete.run(ctx(), DeleteAdGroupStep.Args(GROUP, snap))
    }
    assertTrue(env.directory.writes().isEmpty())
  }

  @Test
  fun `the GUID comparison ignores case`() = runBlocking<Unit> {
    val env = Env()
    val snap = env.snapshot()

    val upper = snap.copy(groupObjectGuid = snap.groupObjectGuid!!.uppercase())

    assertEquals(Validation.Ok, env.validateDelete(upper))
  }

  @Test
  fun `a duplicate name across a sub OU of the main OU and the main OU blocks everything`() =
      runBlocking<Unit> {
        val env = Env()
        val snap = env.snapshot()
        env.directory.twins += subOuTwin()

        val blocked = assertIs<Validation.Blocked>(env.validateDelete(snap))
        val gate =
            assertFailsWith<PermanentStepException> {
              env.check.run(ctx(), CheckGroupArchivableStep.Args(GROUP))
            }
        val plan = env.check.validate(ctx(StepMode.PREFLIGHT), CheckGroupArchivableStep.Args(GROUP))

        assertTrue(blocked.messages.single().contains("2 groups named"))
        assertTrue(gate.message!!.contains("2 groups named"))
        assertTrue(assertIs<Validation.Blocked>(plan).messages.single().contains("2 groups named"))
        assertIs<StepCheck.WillRun>(env.delete.check(ctx(), DeleteAdGroupStep.Args(GROUP, snap)))
      }

  @Test
  fun `the directory refuses a delete when a twin appears after the validation`() =
      runBlocking<Unit> {
        val env = Env()
        val snap = env.snapshot()
        env.directory.twins +=
            FakeGroup(
                GROUP,
                dn = "CN=$GROUP,$ADMIN_OU",
                guid = "dddddddd-0000-0000-0000-000000000004")

        assertFailsWith<PermanentStepException> {
          env.delete.run(ctx(), DeleteAdGroupStep.Args(GROUP, snap))
        }

        assertNotNull(env.directory.find(GROUP))
        assertEquals(listOf(snap.groupObjectGuid), env.directory.deleteGuids.toList())
      }

  @Test
  fun `delete passes the snapshotted GUID and never a bare name to the directory`() =
      runBlocking<Unit> {
        val env = Env()
        val snap = env.snapshot()

        env.delete.run(ctx(), DeleteAdGroupStep.Args(GROUP, snap))

        assertEquals(
            listOf("11111111-2222-3333-4444-555555555555"), env.directory.deleteGuids.toList())
      }

  @Test
  fun `a GUID that no longer exists fails permanently instead of deleting by name`() =
      runBlocking<Unit> {
        val env = Env()
        val snap = env.snapshot()
        env.directory.groups.remove(GROUP.lowercase())
        env.directory.add(FakeGroup(GROUP, guid = "cccccccc-0000-0000-0000-000000000003"))

        assertFailsWith<PermanentStepException> {
          env.delete.run(ctx(), DeleteAdGroupStep.Args(GROUP, snap))
        }

        assertNotNull(env.directory.find(GROUP))
      }

  // the gate holds on a late retry

  @Test
  fun `delete re-checks references when executing, not when planning`() = runBlocking<Unit> {
    val hit = GroupReference("Moodle", GroupReferenceKinds.COURSE, 1, "1 course")
    val env = Env(setOf(StaticFinder("Moodle", listOf(hit))))
    val snap = env.snapshot()

    val executing = assertIs<Validation.Blocked>(env.validateDelete(snap))
    val planning = env.validateDelete(snap, StepMode.PREFLIGHT)

    assertEquals(listOf("Moodle: 1 course"), executing.messages)
    assertEquals(Validation.Ok, planning)
  }

  @Test
  fun `delete blocks a protected group by name in both modes`() = runBlocking<Unit> {
    val env = Env()
    env.directory.add(FakeGroup("Board", guid = "eeeeeeee-0000-0000-0000-000000000005"))
    val snap = env.snapshot("Board")

    val executing = assertIs<Validation.Blocked>(env.validateDelete(snap, name = "Board"))
    val planning =
        assertIs<Validation.Blocked>(env.validateDelete(null, StepMode.PREFLIGHT, name = "Board"))

    assertTrue(executing.messages.single().startsWith("Protected group:"))
    assertTrue(planning.messages.single().startsWith("Protected group:"))
    assertFailsWith<PermanentStepException> {
      env.delete.run(ctx(), DeleteAdGroupStep.Args("Board", snap))
    }
    assertNotNull(env.directory.find("Board"))
  }

  // rollback: parents, attributes

  @Test
  fun `rollback restores memberships in main OU parents and counts the rest`() = runBlocking<Unit> {
    val env = Env()
    val inMain = "CN=Parent A,$MAIN_OU"
    val inSub = "CN=Parent B,OU=Sub,$MAIN_OU"
    val inAdmin = "CN=Parent C,$ADMIN_OU"
    val gone = "CN=Parent D,$MAIN_OU"
    env.group().parents = listOf(inMain, inSub, inAdmin, gone)
    env.directory.missingParents += gone
    val (snap, args) = env.deleteGroup()

    env.delete.rollback(ctx(), args, DeleteAdGroupStep.Result(GROUP, null, true))

    assertEquals(listOf(inMain, inSub), env.group().parents)
    assertEquals(
        listOf(inMain, inSub, gone).map { "addGroupToParentGroup:$GROUP:$it" },
        env.directory.calls.filter { it.startsWith("addGroupToParent") })
    assertEquals(4, snap.parentGroupCount)
    assertEquals(1, snap.parentGroupsOutsideMainCount)
  }

  @Test
  fun `rolling back twice does not duplicate parent memberships`() = runBlocking<Unit> {
    val env = Env()
    env.group().parents = listOf("CN=Parent A,$MAIN_OU")
    val (_, args) = env.deleteGroup()
    val out = DeleteAdGroupStep.Result(GROUP, null, true)

    env.delete.rollback(ctx(), args, out)
    env.delete.rollback(ctx(), args, out)

    assertEquals(listOf("CN=Parent A,$MAIN_OU"), env.group().parents)
  }

  @Test
  fun `the summary says what a rollback cannot restore, with counts only`() = runBlocking<Unit> {
    val env = Env()
    env.group().parents = listOf("CN=Parent A,$MAIN_OU", "CN=Parent C,$ADMIN_OU")
    val snap = env.snapshot()

    val result = env.delete.run(ctx(), DeleteAdGroupStep.Args(GROUP, snap))

    assertEquals(2, result.parentGroupCount)
    assertEquals(1, result.parentGroupsOutsideMainCount)
    assertEquals(
        "Group deleted from the directory; it was a member of 2 other group(s), 1 of which a " +
            "rollback cannot restore",
        env.delete.summarize(result))
    assertFalse(env.delete.summarize(result).contains("Parent"))
    assertEquals(
        "Group deleted from the directory",
        env.delete.summarize(DeleteAdGroupStep.Result(GROUP, null, true)))
  }

  @Test
  fun `rollback recreates the group with its original sAMAccountName and type`() =
      runBlocking<Unit> {
        val env = Env()
        env.group().sam = "test-group-sam"
        env.group().groupType = -2147483640
        val (_, args) = env.deleteGroup()

        env.delete.rollback(ctx(), args, DeleteAdGroupStep.Result(GROUP, null, true))

        assertEquals("test-group-sam", env.group().sam)
        assertEquals(-2147483640, env.group().groupType)
      }

  @Test
  fun `a snapshot from before the sAMAccountName was recorded still rolls back`() =
      runBlocking<Unit> {
        val env = Env()
        val (_, args) = env.deleteGroup()

        env.delete.rollback(ctx(), args, DeleteAdGroupStep.Result(GROUP, null, true))

        assertNull(env.group().sam)
        assertNull(env.group().groupType)
      }

  // managed objects reference finder

  @Test
  fun `managed objects block the archive with counts only`() = runBlocking<Unit> {
    val env = Env()
    env.directory.managed[GROUP.lowercase()] =
        ManagedObjectCounts(groups = 1, users = 2, computers = 3)
    val finder = ManagedObjectsReferenceFinder(env.directory, Dispatchers.IO)

    val found = finder.find(GROUP).single()

    assertEquals(GroupReferenceKinds.MANAGED_OBJECTS, found.kind)
    assertEquals(6, found.count)
    assertEquals("1 group(s), 2 user(s), 3 computer(s) managed by this group", found.detail)
    assertTrue(finder.find("Nobody").isEmpty())
    env.directory.managed.clear()
    assertTrue(finder.find(GROUP).isEmpty())
  }

  @Test
  fun `a duplicated name makes the managed-objects lookup fail so the checker blocks`() =
      runBlocking<Unit> {
        val env = Env()
        env.directory.twins += subOuTwin()
        val finder = ManagedObjectsReferenceFinder(env.directory, Dispatchers.IO)

        val refs = GroupReferenceChecker(setOf(finder)).check(GROUP)

        assertTrue(refs.isBlocked)
        assertEquals(GroupReferenceKinds.UNKNOWN, refs.blocking.single().kind)
      }

  @Test
  fun `the gate reports objects managed by the group`() = runBlocking<Unit> {
    val env = Env()
    env.directory.managed[GROUP.lowercase()] = ManagedObjectCounts(users = 1)
    val finder = ManagedObjectsReferenceFinder(env.directory, Dispatchers.IO)
    val gated =
        CheckGroupArchivableStep(env.directory, GroupReferenceChecker(setOf(finder)), Dispatchers.IO)

    val thrown =
        assertFailsWith<PermanentStepException> {
          gated.run(ctx(), CheckGroupArchivableStep.Args(GROUP))
        }

    assertEquals("Directory: 1 user(s) managed by this group", thrown.message)
  }
}
