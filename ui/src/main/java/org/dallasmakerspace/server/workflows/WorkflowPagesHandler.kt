package org.dallasmakerspace.server.workflows

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.thymeleaf.ThymeleafContent
import javax.inject.Inject
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.plugins.getCachedFeatureFlag
import org.dallasmakerspace.server.routes.AuthenticatedHandler

/** HTML pages: `/admin/workflows`, `/workflows/runs/{runId}` and the `/workflows/demo` sandbox. */
class WorkflowPagesHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberServiceClient: MemberServiceClient,
    private val client: WorkflowServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val username = userInfo["preferred_username"] as? String
    val enabled =
        username != null &&
            getCachedFeatureFlag(
                memberServiceClient, WORKFLOWS_FEATURE_FLAG, username, session.sessionId)
    if (!enabled) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    val path = call.request.path()
    when {
      path == ADMIN_PATH -> adminPage(call, username!!)
      path == DEMO_PATH -> demoPage(call, username!!)
      path.startsWith(RUN_PREFIX) -> runPage(call, path.removePrefix(RUN_PREFIX), username!!)
      else -> call.respond(HttpStatusCode.NotFound)
    }
  }

  private suspend fun adminPage(call: ApplicationCall, username: String) {
    if (!isWorkflowAdmin(authz)) {
      call.respond(HttpStatusCode.Forbidden)
      return
    }
    call.respond(ThymeleafContent("admin-workflows", mapOf("username" to username)))
  }

  private suspend fun demoPage(call: ApplicationCall, username: String) {
    val defs =
        try {
          client.definitions(session.sessionId).filter { canUseWorkflow(authz, it) }
        } catch (e: Exception) {
          log.warn("Workflow definitions unavailable for demo page", e)
          emptyList()
        }
    call.respond(
        ThymeleafContent(
            "workflow-demo",
            mapOf(
                "username" to username,
                "definitions" to
                    defs.map {
                      mapOf(
                          "id" to it.id,
                          "displayName" to it.displayName,
                          "description" to it.description)
                    })))
  }

  private suspend fun runPage(call: ApplicationCall, idText: String, username: String) {
    val runId = idText.toLongOrNull()
    if (runId == null) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    val run =
        try {
          client.run(runId, username, session.sessionId)
        } catch (e: Exception) {
          log.warn("Workflow run lookup failed for $runId", e)
          call.respond(HttpStatusCode.BadGateway)
          return
        }
    if (run == null) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    val def = client.definition(run.workflowId, session.sessionId)
    if (!canViewWorkflow(authz, def)) {
      call.respond(HttpStatusCode.Forbidden)
      return
    }
    call.respond(
        ThymeleafContent(
            "workflow-run",
            mapOf(
                "username" to username,
                "runId" to runId,
                "runTitle" to run.displayName,
                "canOperate" to canOperateWorkflow(authz, def))))
  }

  companion object {
    const val ADMIN_PATH = "/admin/workflows"
    const val DEMO_PATH = "/workflows/demo"
    const val RUN_PREFIX = "/workflows/runs/"
  }
}
