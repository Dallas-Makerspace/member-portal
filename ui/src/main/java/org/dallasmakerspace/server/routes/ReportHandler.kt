package org.dallasmakerspace.server.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.thymeleaf.*
import javax.inject.Inject
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.moodle.MOODLE_FEATURE_FLAG
import org.dallasmakerspace.server.plugins.AuthException
import org.dallasmakerspace.server.reports.ReportGraph
import org.dallasmakerspace.server.reports.ReportNode

class ReportHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    // Extract the full path from the request
    val path = call.request.path()

    // Get the root node of the report graph
    val rootNode = ReportGraph.getRoot()

    // The path format is /report/<parentNode>/<childNode>
    // Split the path to get the components
    val pathComponents = path.split("/").filter { it.isNotEmpty() }

    // Handle root reports page
    if (pathComponents.size == 1 && pathComponents[0] == "reports") {
      renderReportsList(call, rootNode, null, null)
      return
    }

    // Handle parent node page or top-level report
    if (pathComponents.size == 2 && pathComponents[0] == "reports") {
      val nodeSlug = pathComponents[1]
      val node =
          rootNode.children?.find { it.urlSlug == nodeSlug }
              ?: throw AuthException("Node not found: $nodeSlug")

      // If this node has no children, it's a top-level report - render it directly
      if (node.children.isNullOrEmpty()) {
        log.info("Rendering top-level report template for ${node.name}")

        // Extract query parameters to pass to template
        val queryParams =
            call.request.queryParameters.names().associateWith { paramName ->
              call.request.queryParameters[paramName] ?: ""
            }

        val templateData =
            mutableMapOf(
                "authz" to authz,
                "fragmentList" to setOf("fragments/reports/$nodeSlug"),
                "childNode" to node,
                "reportTitle" to "${node.name} - Reports",
                "showChildren" to false,
                "queryParams" to queryParams,
            )

        call.respond(ThymeleafContent("report", templateData))
        return
      }

      // Otherwise, it's a parent node - render its children list
      renderReportsList(call, rootNode, node, null)
      return
    }

    // Handle child node page (specific report)
    if (pathComponents.size < 3) {
      throw AuthException("Invalid report path: $path")
    }

    // pathComponents[0] should be "reports"
    val parentSlug = pathComponents[1]
    val childSlug = pathComponents[2]

    // Find the parent node in the root's children
    val parentNode =
        rootNode.children?.find { it.urlSlug == parentSlug }
            ?: throw AuthException("Parent node not found: $parentSlug")

    // Find the child node in the parent's children
    val childNode =
        parentNode.children?.find { it.urlSlug == childSlug }
            ?: throw AuthException("Child node not found: $childSlug")

    log.info("Rendering report template for ${childNode.name}")

    // Extract query parameters to pass to template
    val queryParams =
        call.request.queryParameters.names().associateWith { paramName ->
          call.request.queryParameters[paramName] ?: ""
        }

    val templateData =
        mutableMapOf(
            "authz" to authz,
            "fragmentList" to setOf("fragments/reports/$parentSlug/$childSlug"),
            "parentNode" to parentNode,
            "childNode" to childNode,
            "reportTitle" to "${childNode.name} - Reports",
            "showChildren" to false,
            "queryParams" to queryParams,
        )

    if (parentSlug == "it" && childSlug == "recent-signed-waivers") {
      val currentUsername = (userInfo["preferred_username"] as? String) ?: ""
      val isWaiverReportAuthorized =
          if (authz.can(Permission.MANAGE_BADGES.name)) {
            true
          } else {
            val allowedRaw =
                memberServiceClient.getConfigValue(
                    "waiver-report.allowed-usernames", session.sessionId) ?: ""
            allowedRaw
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .contains(currentUsername)
          }
      templateData["isWaiverReportAuthorized"] = isWaiverReportAuthorized
    }

    if (parentSlug == "online-courses") addMoodleModel(templateData, queryParams)

    call.respond(ThymeleafContent("report", templateData))
  }

  private suspend fun addMoodleModel(
      templateData: MutableMap<String, Any>,
      queryParams: Map<String, String>,
  ) {
    val currentUsername = (userInfo["preferred_username"] as? String) ?: ""
    templateData["moodleEnabled"] =
        memberServiceClient.isFeatureEnabled(MOODLE_FEATURE_FLAG, currentUsername, session.sessionId)
    queryParams["committee"]
        ?.let { Committees.findBySlug(it) }
        ?.let { templateData["moodleCommittee"] = it }
  }

  private suspend fun renderReportsList(
      call: ApplicationCall,
      rootNode: ReportNode,
      parentNode: ReportNode?,
      childNode: ReportNode?,
  ) {
    val nodesToDisplay =
        when {
          childNode != null && childNode.children != null -> childNode.children
          parentNode != null && parentNode.children != null -> parentNode.children
          else -> rootNode.children
        }

    // Filter out reports that support query params
    val visibleNodes = nodesToDisplay?.filter { it.visibleInNav }

    val currentNodeName = childNode?.name ?: parentNode?.name ?: "Reports"

    val templateData =
        mutableMapOf(
            "authz" to authz,
            "childrenNodes" to visibleNodes,
            "showChildren" to true,
            "reportTitle" to "$currentNodeName - Reports",
        )

    if (parentNode != null) {
      templateData["parentNode"] = parentNode
    }

    if (childNode != null) {
      templateData["childNode"] = childNode
    }

    log.info("Rendering reports list for $currentNodeName")
    call.respond(ThymeleafContent("report", templateData as Map<String, Any>))
  }
}
