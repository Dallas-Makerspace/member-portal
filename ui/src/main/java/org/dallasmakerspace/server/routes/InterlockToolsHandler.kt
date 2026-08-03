package org.dallasmakerspace.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.thymeleaf.ThymeleafContent
import javax.inject.Inject
import org.dallasmakerspace.models.CommitteeReports
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.models.ToolReports
import org.dallasmakerspace.models.Tools
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.models.getSlugFromName

class InterlockToolsHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val toolSlug = call.request.queryParameters["tool"]
    val committeeSlug = call.request.queryParameters["committee"]
    val view = call.request.queryParameters["view"]
    val reportSlug = call.request.queryParameters["report"]

    when {
      toolSlug == null && committeeSlug == null -> renderList(call)
      toolSlug != null && reportSlug != null -> renderToolReport(call, toolSlug, reportSlug)
      toolSlug != null && view == "reports" -> renderToolReportIndex(call, toolSlug)
      toolSlug != null -> renderToolDetail(call, toolSlug)
      committeeSlug != null && reportSlug != null ->
          renderCommitteeReport(call, committeeSlug, reportSlug)
      committeeSlug != null && view == "reports" -> renderCommitteeReportIndex(call, committeeSlug)
      else -> call.respond(HttpStatusCode.NotFound)
    }
  }

  private suspend fun renderList(call: ApplicationCall) {
    val toolsByCommittee =
        Tools.byCommittee().map { (committee, tools) ->
          mapOf(
              "committeeName" to committee.name,
              "committeeSlug" to committee.slug,
              "tools" to
                  tools.map { tool ->
                    mapOf(
                        "name" to tool.name,
                        "interlockTag" to tool.interlockTag,
                        "prerequisiteGroup" to tool.prerequisiteGroup,
                        "prerequisiteGroupSlug" to
                            tool.prerequisiteGroup?.let { getSlugFromName(it) },
                        "timeoutSeconds" to tool.timeoutSeconds,
                    )
                  },
          )
        }
    call.respond(ThymeleafContent("interlock-tools", mapOf("toolsByCommittee" to toolsByCommittee)))
  }

  private suspend fun renderToolDetail(call: ApplicationCall, toolSlug: String) {
    val tool = Tools.findBySlug(toolSlug)
    if (tool == null) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    val committee = Committees.findById(tool.committeeId)
    val model =
        mapOf(
            "toolName" to tool.name,
            "toolTag" to tool.interlockTag,
            "committeeName" to (committee?.name ?: ""),
            "committeeSlug" to (committee?.slug ?: ""),
            "prerequisiteGroup" to tool.prerequisiteGroup,
            "prerequisiteGroupSlug" to tool.prerequisiteGroup?.let { getSlugFromName(it) },
            "timeoutSeconds" to tool.timeoutSeconds,
        )
    call.respond(ThymeleafContent("interlock-tool-detail", model))
  }

  private suspend fun renderToolReportIndex(call: ApplicationCall, toolSlug: String) {
    val tool = Tools.findBySlug(toolSlug)
    if (tool == null) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    val committee = Committees.findById(tool.committeeId)
    val model =
        mapOf(
            "toolName" to tool.name,
            "toolTag" to tool.interlockTag,
            "committeeName" to (committee?.name ?: ""),
            "committeeSlug" to (committee?.slug ?: ""),
            "reportSlug" to null,
            "reports" to ToolReports.ALL,
        )
    call.respond(ThymeleafContent("interlock-tool-reports", model))
  }

  private suspend fun renderToolReport(
      call: ApplicationCall,
      toolSlug: String,
      reportSlug: String,
  ) {
    val tool = Tools.findBySlug(toolSlug)
    val report = ToolReports.findBySlug(reportSlug)
    if (tool == null || report == null) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    val committee = Committees.findById(tool.committeeId)
    val model =
        mapOf(
            "toolName" to tool.name,
            "toolTag" to tool.interlockTag,
            "committeeName" to (committee?.name ?: ""),
            "committeeSlug" to (committee?.slug ?: ""),
            "reportSlug" to report.slug,
            "reportName" to report.name,
            "reportDescription" to report.description,
            "dataVizName" to report.dataVizName,
            "defaultDays" to report.defaultDays,
            "renderAs" to report.renderAs,
        )
    call.respond(ThymeleafContent("interlock-tool-reports", model))
  }

  private suspend fun renderCommitteeReportIndex(call: ApplicationCall, committeeSlug: String) {
    val committee = Committees.findBySlug(committeeSlug)
    if (committee == null) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    val model =
        mapOf(
            "committeeName" to committee.name,
            "committeeSlug" to committee.slug,
            "reportSlug" to null,
            "reports" to CommitteeReports.ALL,
        )
    call.respond(ThymeleafContent("interlock-committee-reports", model))
  }

  private suspend fun renderCommitteeReport(
      call: ApplicationCall,
      committeeSlug: String,
      reportSlug: String,
  ) {
    val committee = Committees.findBySlug(committeeSlug)
    val report = CommitteeReports.findBySlug(reportSlug)
    if (committee == null || report == null) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    val model =
        mapOf(
            "committeeName" to committee.name,
            "committeeSlug" to committee.slug,
            "reportSlug" to report.slug,
            "reportName" to report.name,
            "reportDescription" to report.description,
            "dataVizName" to report.dataVizName,
            "defaultDays" to report.defaultDays,
            "renderAs" to report.renderAs,
        )
    call.respond(ThymeleafContent("interlock-committee-reports", model))
  }
}
