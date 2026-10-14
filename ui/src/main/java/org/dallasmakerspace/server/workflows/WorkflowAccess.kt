package org.dallasmakerspace.server.workflows

import org.dallasmakerspace.models.workflows.WorkflowArtifactDto
import org.dallasmakerspace.models.workflows.WorkflowDefinitionDto
import org.dallasmakerspace.server.auth.Authz
import org.dallasmakerspace.server.auth.Permission

const val WORKFLOWS_FEATURE_FLAG = "workflows.enabled"

/** Same gate the header uses for infra-only menu items (MANAGE_CONFIG). */
fun isWorkflowAdmin(authz: Authz): Boolean = authz.can(Permission.MANAGE_CONFIG.name)

/**
 * Whether the user may preflight, start and operate runs of [def]. A permission name the UI does
 * not know (it is a service Permission name) is denied, so a typo or a service-only permission can
 * never open a workflow up.
 */
fun canUseWorkflow(authz: Authz, def: WorkflowDefinitionDto): Boolean {
  val required = def.requiredPermission
  return required == null || authz.can(required)
}

/** Viewing a run (detail, events, stream) is allowed to admins as well as workflow operators. */
fun canViewWorkflow(authz: Authz, def: WorkflowDefinitionDto?): Boolean =
    isWorkflowAdmin(authz) || (def != null && canUseWorkflow(authz, def))

/** Operator actions need the workflow's own permission; admins alone only get to look. */
fun canOperateWorkflow(authz: Authz, def: WorkflowDefinitionDto?): Boolean =
    def != null && canUseWorkflow(authz, def)

/**
 * Downloading a step's file needs the workflow's own permission, plus [Permission.DOWNLOAD_ARCHIVES]
 * (not just MANAGE_CONFIG, which Board members hold) for infra-only artifacts.
 */
fun canDownloadArtifact(authz: Authz, def: WorkflowDefinitionDto?, artifact: WorkflowArtifactDto) =
    def != null && canUseWorkflow(authz, def) && (!artifact.infraOnly || canDownloadArchives(authz))

fun canDownloadArchives(authz: Authz): Boolean = authz.can(Permission.DOWNLOAD_ARCHIVES.name)

sealed interface WorkflowRoute {
  data object Definitions : WorkflowRoute

  data class Preflight(val workflowId: String) : WorkflowRoute

  data class Start(val workflowId: String) : WorkflowRoute

  data object ListRuns : WorkflowRoute

  data class Run(val runId: Long) : WorkflowRoute

  data class RunEvents(val runId: Long) : WorkflowRoute

  data class Operate(val runId: Long, val action: String) : WorkflowRoute

  data object AdminInProgress : WorkflowRoute

  data object AdminSummary : WorkflowRoute

  data object SubjectStream : WorkflowRoute

  data class RunStream(val runId: Long) : WorkflowRoute

  data object AdminStream : WorkflowRoute

  data class ArtifactDownload(val runId: Long, val stepIndex: Int, val name: String) :
      WorkflowRoute
}

private val ARTIFACT_NAME = Regex("[a-z0-9][a-z0-9._-]{0,63}")

val OPERATOR_ACTIONS = setOf("retry", "skip", "rollback", "abandon", "cancel")

/** Maps a browser-facing `/api/workflows/...` request to a route, or null if there is none. */
fun parseWorkflowRoute(method: String, path: String): WorkflowRoute? {
  val rest = path.removePrefix("/api/workflows").trim('/')
  val seg = rest.split('/').filter { it.isNotEmpty() }
  val get = method.equals("GET", ignoreCase = true)
  val post = method.equals("POST", ignoreCase = true)
  return when {
    get && seg == listOf("definitions") -> WorkflowRoute.Definitions
    get && seg == listOf("stream") -> WorkflowRoute.SubjectStream
    get && seg == listOf("runs") -> WorkflowRoute.ListRuns
    get && seg == listOf("admin", "in-progress") -> WorkflowRoute.AdminInProgress
    get && seg == listOf("admin", "summary") -> WorkflowRoute.AdminSummary
    get && seg == listOf("admin", "stream") -> WorkflowRoute.AdminStream
    seg.size >= 2 && seg[0] == "runs" -> parseRunRoute(get, post, seg)
    post && seg.size == 2 && seg[1] == "preflight" -> WorkflowRoute.Preflight(seg[0])
    post && seg.size == 2 && seg[1] == "runs" -> WorkflowRoute.Start(seg[0])
    else -> null
  }
}

private fun parseRunRoute(get: Boolean, post: Boolean, seg: List<String>): WorkflowRoute? {
  val runId = seg[1].toLongOrNull() ?: return null
  return when {
    get && seg.size == 2 -> WorkflowRoute.Run(runId)
    get && seg.size == 3 && seg[2] == "events" -> WorkflowRoute.RunEvents(runId)
    get && seg.size == 3 && seg[2] == "stream" -> WorkflowRoute.RunStream(runId)
    post && seg.size == 3 && seg[2] in OPERATOR_ACTIONS -> WorkflowRoute.Operate(runId, seg[2])
    get && seg.size == 7 && seg[2] == "steps" && seg[4] == "artifacts" && seg[6] == "download" ->
        artifactRoute(runId, seg[3], seg[5])
    else -> null
  }
}

private fun artifactRoute(runId: Long, index: String, name: String): WorkflowRoute? {
  val step = index.toIntOrNull()?.takeIf { it >= 0 } ?: return null
  return if (ARTIFACT_NAME.matches(name)) WorkflowRoute.ArtifactDownload(runId, step, name) else null
}

private val LAST_EVENT_ID = Regex("^[0-9]{1,18}$")

/** Last-Event-ID is forwarded to the service, so only plain numeric ids are accepted. */
fun sanitizeLastEventId(raw: String?): String? = raw?.trim()?.takeIf { LAST_EVENT_ID.matches(it) }

/** `type:key` with both parts non-empty, and no control characters. */
fun isValidSubject(raw: String): Boolean {
  val sep = raw.indexOf(':')
  return sep > 0 && sep < raw.length - 1 && raw.none { it.isISOControl() }
}
