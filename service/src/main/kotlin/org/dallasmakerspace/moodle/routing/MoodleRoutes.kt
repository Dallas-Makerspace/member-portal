package org.dallasmakerspace.moodle.routing

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.dallasmakerspace.core.api.ApiResponse
import org.dallasmakerspace.core.api.Status
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.moodle.MoodleCatalogService
import org.dallasmakerspace.moodle.MoodleMemberCoursesService

fun Route.moodleRoutes(
    catalogService: MoodleCatalogService,
    memberCoursesService: MoodleMemberCoursesService,
) {
  get("/moodle/catalog") {
    val slug = call.request.queryParameters["committee"]
    val committee = slug?.let { Committees.findBySlug(it) }
    if (slug != null && committee == null) {
      call.respond(
          HttpStatusCode.NotFound, ApiResponse(Status.ERROR, "Unknown committee: $slug", null))
      return@get
    }
    val catalog = catalogService.getCatalog(committee)
    call.respond(ApiResponse(Status.SUCCESS, "DMS Learn courses: ${catalog.courses.size}", catalog))
  }

  get("/moodle/members/{username}/courses") {
    val username =
        call.parameters["username"]
            ?: run {
              call.respond(HttpStatusCode.BadRequest)
              return@get
            }
    val courses = memberCoursesService.getMemberCourses(username)
    call.respond(ApiResponse(Status.SUCCESS, "DMS Learn courses for $username", courses))
  }
}
