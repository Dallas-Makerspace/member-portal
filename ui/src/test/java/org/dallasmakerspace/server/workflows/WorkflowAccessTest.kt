package org.dallasmakerspace.server.workflows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.dallasmakerspace.models.workflows.WorkflowAdminInProgressDto
import org.dallasmakerspace.models.workflows.WorkflowArtifactDto
import org.dallasmakerspace.models.workflows.WorkflowDefinitionDto
import org.dallasmakerspace.models.workflows.WorkflowRunStatus
import org.dallasmakerspace.server.auth.Authz

class WorkflowAccessTest {
  private fun def(permission: String?) =
      WorkflowDefinitionDto(
          id = "demo.flow",
          version = 1,
          displayName = "Demo",
          description = "",
          requiredPermission = permission,
          inputFields = emptyList(),
          steps = emptyList())

  private fun authzFor(vararg groups: String) =
      Authz.fromUserInfo(mapOf("groups" to groups.toList()))

  private val member = authzFor()
  private val officer = authzFor("/President")
  private val board = authzFor("/Board")
  private val infra = authzFor("/Infrastructure")

  @Test
  fun `a workflow with a required permission needs that permission`() {
    assertFalse(canUseWorkflow(member, def("MANAGE_GROUPS")))
    assertTrue(canUseWorkflow(officer, def("MANAGE_GROUPS")))
    assertTrue(canUseWorkflow(infra, def("MANAGE_GROUPS")))
  }

  private fun artifact(infraOnly: Boolean) =
      WorkflowArtifactDto("member-list", "Member list", "application/json", infraOnly)

  @Test
  fun `artifact downloads need the workflow permission`() {
    val d = def("MANAGE_GROUPS")
    assertFalse(canDownloadArtifact(member, d, artifact(false)))
    assertFalse(canDownloadArtifact(board, d, artifact(false)))
    assertTrue(canDownloadArtifact(officer, d, artifact(false)))
    assertFalse(canDownloadArtifact(officer, null, artifact(false)))
  }

  @Test
  fun `infra-only artifacts need DOWNLOAD_ARCHIVES which the Board's MANAGE_CONFIG does not give`() {
    val open = def(null)
    assertFalse(canDownloadArtifact(member, open, artifact(true)))
    assertFalse(canDownloadArtifact(officer, open, artifact(true)))
    assertFalse(canDownloadArtifact(board, open, artifact(true)))
    assertTrue(canDownloadArtifact(board, open, artifact(false)))
    assertTrue(canDownloadArtifact(infra, open, artifact(true)))
    assertTrue(canDownloadArchives(infra))
    assertFalse(canDownloadArchives(board))
  }

  @Test
  fun `artifact download routes are parsed strictly`() {
    val path = "/api/workflows/runs/7/steps/2/artifacts/member-list/download"
    assertEquals(WorkflowRoute.ArtifactDownload(7, 2, "member-list"), parseWorkflowRoute("GET", path))
    assertNull(parseWorkflowRoute("POST", path))
    assertNull(parseWorkflowRoute("GET", path.replace("/2/", "/-1/")))
    assertNull(parseWorkflowRoute("GET", path.replace("/2/", "/x/")))
    assertNull(parseWorkflowRoute("GET", path.replace("member-list", "Member_List")))
    assertNull(parseWorkflowRoute("GET", path.replace("member-list", "..")))
    assertNull(parseWorkflowRoute("GET", path.replace("/download", "/url")))
    assertNull(parseWorkflowRoute("GET", path.replace("/runs/7", "/runs/x")))
  }

  @Test
  fun `a workflow with no required permission is open to any member`() {
    assertTrue(canUseWorkflow(member, def(null)))
  }

  @Test
  fun `a permission name the UI does not know is denied for everyone`() {
    assertFalse(canUseWorkflow(infra, def("RUN_WORKFLOWS")))
    assertFalse(canUseWorkflow(officer, def("")))
  }

  @Test
  fun `admins can view any run but only permitted users can operate`() {
    val d = def("MANAGE_GROUPS")
    assertTrue(canViewWorkflow(board, d))
    assertFalse(canOperateWorkflow(board, d))
    assertTrue(canViewWorkflow(officer, d))
    assertTrue(canOperateWorkflow(officer, d))
    assertFalse(canViewWorkflow(member, d))
  }

  @Test
  fun `a run of a workflow that no longer exists is visible to admins only`() {
    assertTrue(canViewWorkflow(board, null))
    assertFalse(canViewWorkflow(officer, null))
    assertFalse(canOperateWorkflow(infra, null))
  }

  @Test
  fun `admin gate is the config permission`() {
    assertTrue(isWorkflowAdmin(board))
    assertTrue(isWorkflowAdmin(infra))
    assertFalse(isWorkflowAdmin(officer))
    assertFalse(isWorkflowAdmin(member))
  }

  @Test
  fun `routes parse by method and path`() {
    assertEquals(WorkflowRoute.Definitions, parseWorkflowRoute("GET", "/api/workflows/definitions"))
    assertEquals(
        WorkflowRoute.Preflight("group.archive"),
        parseWorkflowRoute("POST", "/api/workflows/group.archive/preflight"))
    assertEquals(
        WorkflowRoute.Start("group.archive"),
        parseWorkflowRoute("POST", "/api/workflows/group.archive/runs"))
    assertEquals(WorkflowRoute.ListRuns, parseWorkflowRoute("GET", "/api/workflows/runs"))
    assertEquals(WorkflowRoute.Run(12), parseWorkflowRoute("GET", "/api/workflows/runs/12"))
    assertEquals(
        WorkflowRoute.RunEvents(12), parseWorkflowRoute("GET", "/api/workflows/runs/12/events"))
    assertEquals(
        WorkflowRoute.RunStream(12), parseWorkflowRoute("GET", "/api/workflows/runs/12/stream"))
    assertEquals(
        WorkflowRoute.Operate(12, "retry"), parseWorkflowRoute("POST", "/api/workflows/runs/12/retry"))
    assertEquals(WorkflowRoute.SubjectStream, parseWorkflowRoute("GET", "/api/workflows/stream"))
    assertEquals(WorkflowRoute.AdminStream, parseWorkflowRoute("GET", "/api/workflows/admin/stream"))
    assertEquals(
        WorkflowRoute.AdminInProgress, parseWorkflowRoute("GET", "/api/workflows/admin/in-progress"))
  }

  @Test
  fun `unknown paths, methods and actions do not route`() {
    assertNull(parseWorkflowRoute("DELETE", "/api/workflows/runs/12"))
    assertNull(parseWorkflowRoute("GET", "/api/workflows/runs/abc"))
    assertNull(parseWorkflowRoute("POST", "/api/workflows/runs/12/explode"))
    assertNull(parseWorkflowRoute("GET", "/api/workflows/runs/12/retry"))
    assertNull(parseWorkflowRoute("POST", "/api/workflows/definitions"))
    assertNull(parseWorkflowRoute("GET", "/api/workflows/"))
  }

  @Test
  fun `last event id must be numeric before it is forwarded`() {
    assertEquals("42", sanitizeLastEventId(" 42 "))
    assertNull(sanitizeLastEventId("42\r\nX-Evil: 1"))
    assertNull(sanitizeLastEventId("abc"))
    assertNull(sanitizeLastEventId(null))
  }

  @Test
  fun `subjects need a type and a key`() {
    assertTrue(isValidSubject("group:example-group"))
    assertFalse(isValidSubject("group:"))
    assertFalse(isValidSubject(":x"))
    assertFalse(isValidSubject("nocolon"))
    assertFalse(isValidSubject("group:a\nb"))
  }

  @Test
  fun `admin summary counts runs needing attention`() {
    fun run(id: Long, status: WorkflowRunStatus) =
        org.dallasmakerspace.models.workflows.WorkflowRunDto(
            id = id,
            workflowId = "demo.flow",
            workflowVersion = 1,
            displayName = "Demo",
            status = status,
            input = kotlinx.serialization.json.JsonObject(emptyMap()),
            subjects = emptyList(),
            actor = "jdoe",
            triggeredBy = "UI",
            currentStepIndex = 0,
            stepCount = 3,
            createdAt = "2026-01-02T03:04:05Z")
    val summary =
        WorkflowApiHandler.adminSummary(
            WorkflowAdminInProgressDto(
                safeToDeploy = false,
                runs =
                    listOf(
                        run(1, WorkflowRunStatus.RUNNING),
                        run(2, WorkflowRunStatus.PAUSED),
                        run(3, WorkflowRunStatus.ROLLBACK_FAILED),
                        run(4, WorkflowRunStatus.WAITING_RETRY)),
                generatedAt = "2026-01-02T03:04:05Z"))
    assertEquals("4", summary["inProgress"].toString())
    assertEquals("2", summary["needsAttention"].toString())
    assertEquals("false", summary["safeToDeploy"].toString())
  }
}
