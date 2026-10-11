package org.dallasmakerspace.server.moodle

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.thymeleaf.ThymeleafContent
import javax.inject.Inject
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.routes.AuthenticatedHandler

/** `GET /api/committee-courses?committee=<slug>` — the committee page's Online Courses card as HTML. */
class CommitteeCoursesHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val loader: MoodleCommitteeCoursesLoader,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val committee = call.request.queryParameters["committee"]?.let { Committees.findBySlug(it) }
    val username = userInfo["preferred_username"] as? String
    val model =
        if (committee == null || username == null) null
        else
            loader.load(
                committee,
                session.sessionId,
                username,
                canAdminGroups = authz.can(Permission.MANAGE_GROUPS.name),
                isInfraUser = authz.can(Permission.MANAGE_CONFIG.name))
    if (model == null) {
      call.respond(HttpStatusCode.NoContent)
      return
    }
    call.respond(
        ThymeleafContent(
            "fragments/moodle-courses-section",
            mapOf("sectionModel" to model),
            fragments = setOf("content"),
        ))
  }
}
