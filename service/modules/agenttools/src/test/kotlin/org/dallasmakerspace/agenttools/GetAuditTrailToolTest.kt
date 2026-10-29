package org.dallasmakerspace.agenttools

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.dallasmakerspace.agent.MemberAccess
import org.dallasmakerspace.agent.ToolContext
import org.dallasmakerspace.models.workflows.WorkflowRunDto
import org.dallasmakerspace.models.workflows.WorkflowRunStatus
import org.dallasmakerspace.models.workflows.WorkflowSubject
import org.dallasmakerspace.workflows.RunFilter
import org.dallasmakerspace.workflows.WorkflowService
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class GetAuditTrailToolTest {
  private val queries: AuditTrailQueries = mock()
  private val workflows: WorkflowService = mock()
  private val now = Instant.parse("2026-10-29T12:00:00Z")
  private val staff = ToolContext("jdoe", "c1", "it-assistant", MemberAccess.ANY_MEMBER)
  private lateinit var tool: GetAuditTrailTool

  @Before
  fun setUp() {
    tool = GetAuditTrailTool(queries, workflows, Clock.fixed(now, ZoneOffset.UTC))
    runBlocking {
      whenever(queries.groupChanges(anyOrNull(), anyOrNull(), any(), any())) doReturn emptyList()
      whenever(queries.activity(any(), any(), any())) doReturn emptyList()
      whenever(queries.hasProfile(any())) doReturn true
      whenever(workflows.listRuns(any())) doReturn emptyList()
    }
  }

  private fun args(vararg pairs: Pair<String, String>): JsonObject =
      buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

  private fun utc(text: String) = LocalDateTime.parse(text)

  private fun change(at: String, actor: String = "bwhite", member: String = "asmith", group: String = "Laser Cutters", added: Boolean = true) =
      GroupChangeRow(actor, member, group, added, utc(at))

  private fun activity(at: String?, event: Int, attributes: String? = null, actor: String? = "bwhite", source: Int = 1) =
      ActivityRow(actor, event, source, attributes, at?.let { utc(it) })

  private fun run(id: Long, created: String, status: WorkflowRunStatus = WorkflowRunStatus.SUCCEEDED, error: String? = null, finished: String? = null) =
      WorkflowRunDto(
          id = id, workflowId = "group.archive", workflowVersion = 1, displayName = "Archive", status = status,
          input = JsonObject(emptyMap()), subjects = listOf(WorkflowSubject("group", "Old Group")), actor = "asmith",
          triggeredBy = "asmith", currentStepIndex = 2, stepCount = 5, createdAt = created, finishedAt = finished,
          errorSummary = error)

  @Test
  fun `entries from all sources are merged newest first with Central times`() = runBlocking<Unit> {
    whenever(queries.groupChanges(eq("asmith"), eq(null), any(), any())) doReturn
        listOf(change("2026-10-20T15:00:00"), change("2026-01-15T15:00:00", added = false))
    whenever(queries.activity(eq("asmith"), any(), any())) doReturn listOf(activity("2026-10-25T20:30:00", 13))
    whenever(workflows.listRuns(any())) doReturn listOf(run(7, "2026-10-27T15:00:00Z"))

    val output = tool.run(args("username" to "asmith", "days" to "365"), staff).output

    val lines = output.lines().filter { it.startsWith("- ") }
    assertEquals(4, lines.size, output)
    assertTrue(lines[0].contains("[workflow] 2026-10-27 10:00 CDT run #7 group.archive SUCCEEDED (started by asmith, step 3 of 5)"), lines[0])
    assertTrue(lines[1].contains("[activity] 2026-10-25 15:30 CDT GROUP_MEMBER_ADDED (by bwhite, PROFILE)"), lines[1])
    assertTrue(lines[2].contains("[group] 2026-10-20 10:00 CDT ADDED to \"Laser Cutters\" (by bwhite)"), lines[2])
    assertTrue(lines[3].contains("[group] 2026-01-15 09:00 CST REMOVED from \"Laser Cutters\" (by bwhite)"), lines[3])
  }

  @Test
  fun `the cap trims to the newest entries and says how many exist`() = runBlocking<Unit> {
    whenever(queries.groupChanges(any(), anyOrNull(), any(), any())) doReturn
        (1..5).map { change("2026-10-2${it}T10:00:00") }

    val output = tool.run(args("username" to "asmith", "limit" to "2"), staff).output

    assertTrue(output.contains("showing 2 of 5"), output)
    assertEquals(2, output.lines().count { it.startsWith("- ") })
    assertTrue(output.contains("2026-10-25"))
    assertFalse(output.contains("2026-10-21"))
  }

  @Test
  fun `the default is 90 days and 30 entries, and days is clamped to a year`() = runBlocking<Unit> {
    tool.run(args("username" to "asmith"), staff)
    tool.run(args("username" to "asmith", "days" to "9999", "limit" to "9999"), staff)

    val since = argumentCaptor<LocalDateTime>()
    val limit = argumentCaptor<Int>()
    verify(queries, org.mockito.kotlin.times(2)).activity(eq("asmith"), since.capture(), limit.capture())
    assertEquals(utc("2026-07-31T12:00:00"), since.firstValue)
    assertEquals(30, limit.firstValue)
    assertEquals(utc("2025-10-29T12:00:00"), since.secondValue)
    assertEquals(100, limit.secondValue)
  }

  @Test
  fun `workflow runs older than the window are left out`() = runBlocking<Unit> {
    whenever(workflows.listRuns(any())) doReturn listOf(run(1, "2026-10-27T15:00:00Z"), run(2, "2026-06-01T15:00:00Z"))

    val output = tool.run(args("username" to "asmith", "days" to "30"), staff).output

    assertTrue(output.contains("run #1"))
    assertFalse(output.contains("run #2"))
  }

  @Test
  fun `a failed run shows its status, step and a cleaned error`() = runBlocking<Unit> {
    whenever(workflows.listRuns(any())) doReturn
        listOf(run(3, "2026-10-27T15:00:00Z", WorkflowRunStatus.ROLLBACK_FAILED, "Step failed for asmith@example.com\nsecond line", "2026-10-27T15:05:00Z"))

    val output = tool.run(args("username" to "asmith"), staff).output

    assertTrue(output.contains("ROLLBACK_FAILED"), output)
    assertTrue(output.contains("finished 2026-10-27 10:05 CDT"), output)
    assertTrue(output.contains("error: Step failed for [email] second line"), output)
    assertFalse(output.contains("example.com"))
    val filter = argumentCaptor<RunFilter>()
    verify(workflows).listRuns(filter.capture())
    assertEquals("asmith", filter.firstValue.actor)
  }

  @Test
  fun `badge numbers keep four digits and contact details are dropped from activity attributes`() = runBlocking<Unit> {
    whenever(queries.activity(eq("asmith"), any(), any())) doReturn
        listOf(
            activity(
                "2026-10-25T20:30:00", 12,
                """{"oldBadge":"123456","newBadge":"0000123456","email":"asmith@example.com","phone":"214-555-0100","note":"mail a.b@example.org or call (214) 555-0100","nested":{"x":1}}"""),
            activity("2026-10-24T20:30:00", 99, "not json, badge 0000123456 asmith@example.com"))

    val output = tool.run(args("username" to "asmith"), staff).output

    assertTrue(output.contains("oldBadge=****3456, newBadge=****3456"), output)
    assertTrue(output.contains("email=[hidden]") && output.contains("phone=[hidden]"), output)
    assertTrue(output.contains("note=mail [email] or call [phone]"), output)
    assertTrue(output.contains("nested=[nested]"))
    assertTrue(output.contains("EVENT_99"))
    assertTrue(output.contains("badge ****3456 [email]"), output)
    listOf("123456", "0000", "@example", "555-0100", "214").forEach { assertFalse(output.contains(it), "$it leaked:\n$output") }
  }

  @Test
  fun `a group name returns that group's history without hitting the activity log`() = runBlocking<Unit> {
    whenever(queries.groupChanges(eq(null), eq("Laser Cutters"), any(), any())) doReturn
        listOf(change("2026-10-20T15:00:00"), change("2026-10-19T15:00:00", actor = "asmith", member = "asmith", added = false))

    val output = tool.run(args("group" to "Laser Cutters"), staff).output

    assertTrue(output.startsWith("Audit trail for group \"Laser Cutters\""), output)
    assertTrue(output.contains("asmith ADDED (by bwhite)"), output)
    assertTrue(output.contains("asmith REMOVED (self-service)"), output)
    verify(queries, never()).activity(any(), any(), any())
    val filter = argumentCaptor<RunFilter>()
    verify(workflows).listRuns(filter.capture())
    assertEquals(WorkflowSubject("group", "Laser Cutters"), filter.firstValue.subject)
  }

  @Test
  fun `kind limits the sources that are read`() = runBlocking<Unit> {
    tool.run(args("username" to "asmith", "kind" to "groups"), staff)

    verify(queries).groupChanges(eq("asmith"), eq(null), any(), any())
    verify(queries, never()).activity(any(), any(), any())
    verify(workflows, never()).listRuns(any())
  }

  @Test
  fun `a missing username and group, a bad kind or a bad name is an error`() = runBlocking<Unit> {
    listOf(args(), args("username" to "asmith", "kind" to "everything"), args("username" to "a b;c"), args("group" to "x\u0000y")).forEach {
      assertTrue(tool.run(it, staff).isError, it.toString())
    }
    verify(queries, never()).activity(any(), any(), any())
  }

  @Test
  fun `the asker's own name is not assumed when no username is given`() = runBlocking<Unit> {
    val result = tool.run(args("kind" to "groups"), staff)

    assertTrue(result.isError)
    verify(queries, never()).groupChanges(anyOrNull(), anyOrNull(), any(), any())
  }

  @Test
  fun `a self-only context is refused`() = runBlocking<Unit> {
    val result = tool.run(args("username" to "jdoe"), ToolContext("jdoe", "c1", "ask-ai", MemberAccess.SELF_ONLY))

    assertTrue(result.isError)
    verify(queries, never()).activity(any(), any(), any())
  }

  @Test
  fun `one failing source is reported and the others still answer`() = runBlocking<Unit> {
    whenever(queries.activity(any(), any(), any())) doThrow RuntimeException("db down")
    whenever(queries.groupChanges(any(), anyOrNull(), any(), any())) doReturn listOf(change("2026-10-20T15:00:00"))

    val output = tool.run(args("username" to "asmith"), staff).output

    assertTrue(output.contains("[group]"))
    assertTrue(output.contains("Note: activity log could not be read, so this trail is incomplete."), output)
    assertFalse(output.contains("db down"))
  }

  @Test
  fun `an empty trail for an unknown profile says so`() = runBlocking<Unit> {
    whenever(queries.hasProfile("ghost")) doReturn false

    val output = tool.run(args("username" to "ghost"), staff).output

    assertTrue(output.contains("No portal profile exists"), output)
  }

  @Test
  fun `descriptor shape and privacy flags`() {
    assertEquals("get_audit_trail", tool.descriptor.name)
    assertTrue(tool.descriptor.requiredParameters.isEmpty())
    assertEquals(listOf("username", "group", "kind", "days", "limit"), tool.descriptor.optionalParameters.map { it.name })
    assertEquals(MemberAccess.ANY_MEMBER, tool.requiredMemberAccess)
    assertTrue(tool.touchesPersonalInfo)
  }
}
