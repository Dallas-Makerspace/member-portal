package org.dallasmakerspace.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.thymeleaf.ThymeleafContent
import javax.inject.Inject
import org.dallasmakerspace.models.CabinetCommitteeReports
import org.dallasmakerspace.models.CabinetReports
import org.dallasmakerspace.models.Cabinets
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.models.getSlugFromName

class CabinetsHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val cabinetSlug = call.request.queryParameters["cabinet"]
    val committeeSlug = call.request.queryParameters["committee"]
    val view = call.request.queryParameters["view"]
    val reportSlug = call.request.queryParameters["report"]

    when {
      cabinetSlug == null && committeeSlug == null -> renderList(call)
      cabinetSlug != null && reportSlug != null -> renderCabinetReport(call, cabinetSlug, reportSlug)
      cabinetSlug != null && view == "reports" -> renderCabinetReportIndex(call, cabinetSlug)
      cabinetSlug != null -> renderCabinetDetail(call, cabinetSlug)
      committeeSlug != null && reportSlug != null ->
          renderCommitteeReport(call, committeeSlug, reportSlug)
      committeeSlug != null && view == "reports" -> renderCommitteeReportIndex(call, committeeSlug)
      else -> call.respond(HttpStatusCode.NotFound)
    }
  }

  private suspend fun renderList(call: ApplicationCall) {
    val cabinetsByCommittee =
        Cabinets.byCommittee().map { (committee, cabinets) ->
          mapOf(
              "committeeName" to committee.name,
              "committeeSlug" to committee.slug,
              "cabinets" to
                  cabinets.map { cabinet ->
                    mapOf(
                        "name" to cabinet.name,
                        "interlockTag" to cabinet.interlockTag,
                        "prerequisiteGroup" to cabinet.prerequisiteGroup,
                        "prerequisiteGroupSlug" to
                            cabinet.prerequisiteGroup?.let { getSlugFromName(it) },
                    )
                  },
          )
        }
    call.respond(
        ThymeleafContent("interlock-cabinets", mapOf("cabinetsByCommittee" to cabinetsByCommittee)))
  }

  private suspend fun renderCabinetDetail(call: ApplicationCall, cabinetSlug: String) {
    val cabinet = Cabinets.findBySlug(cabinetSlug)
    if (cabinet == null) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    val committee = Committees.findById(cabinet.committeeId)
    val model =
        mapOf(
            "cabinetName" to cabinet.name,
            "cabinetTag" to cabinet.interlockTag,
            "committeeName" to (committee?.name ?: ""),
            "committeeSlug" to (committee?.slug ?: ""),
            "prerequisiteGroup" to cabinet.prerequisiteGroup,
            "prerequisiteGroupSlug" to cabinet.prerequisiteGroup?.let { getSlugFromName(it) },
        )
    call.respond(ThymeleafContent("interlock-cabinet-detail", model))
  }

  private suspend fun renderCabinetReportIndex(call: ApplicationCall, cabinetSlug: String) {
    val cabinet = Cabinets.findBySlug(cabinetSlug)
    if (cabinet == null) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    val committee = Committees.findById(cabinet.committeeId)
    val model =
        mapOf(
            "cabinetName" to cabinet.name,
            "cabinetTag" to cabinet.interlockTag,
            "committeeName" to (committee?.name ?: ""),
            "committeeSlug" to (committee?.slug ?: ""),
            "reportSlug" to null,
            "reports" to CabinetReports.ALL,
        )
    call.respond(ThymeleafContent("interlock-cabinet-reports", model))
  }

  private suspend fun renderCabinetReport(
      call: ApplicationCall,
      cabinetSlug: String,
      reportSlug: String,
  ) {
    val cabinet = Cabinets.findBySlug(cabinetSlug)
    val report = CabinetReports.findBySlug(reportSlug)
    if (cabinet == null || report == null) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    val committee = Committees.findById(cabinet.committeeId)
    val model =
        mapOf(
            "cabinetName" to cabinet.name,
            "cabinetTag" to cabinet.interlockTag,
            "committeeName" to (committee?.name ?: ""),
            "committeeSlug" to (committee?.slug ?: ""),
            "reportSlug" to report.slug,
            "reportName" to report.name,
            "reportDescription" to report.description,
            "dataVizName" to report.dataVizName,
            "defaultDays" to report.defaultDays,
            "renderAs" to report.renderAs,
        )
    call.respond(ThymeleafContent("interlock-cabinet-reports", model))
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
            "reports" to CabinetCommitteeReports.ALL,
        )
    call.respond(ThymeleafContent("interlock-cabinet-committee-reports", model))
  }

  private suspend fun renderCommitteeReport(
      call: ApplicationCall,
      committeeSlug: String,
      reportSlug: String,
  ) {
    val committee = Committees.findBySlug(committeeSlug)
    val report = CabinetCommitteeReports.findBySlug(reportSlug)
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
    call.respond(ThymeleafContent("interlock-cabinet-committee-reports", model))
  }
}
