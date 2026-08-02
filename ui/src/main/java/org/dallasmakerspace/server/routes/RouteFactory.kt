package org.dallasmakerspace.server.routes

import dagger.Component
import io.ktor.server.application.*
import io.ktor.server.request.*
import javax.inject.Singleton
import org.dallasmakerspace.server.di.DiscordModule
import org.dallasmakerspace.server.di.DiscourseModule
import org.dallasmakerspace.server.di.LinkedInModule
import org.dallasmakerspace.server.di.RoutesModule
import org.dallasmakerspace.server.di.VoterRegistrationModule

object RouteFactory {
  val redirects = mutableMapOf<String, String>()

  fun getHandler(call: ApplicationCall): IRouteHandler? {
    val path = sanitizePathInternal(call.request.path())
    return getHandler(path)
  }

  private fun sanitizePathInternal(path: String): String {
    // Replace "@.*" with "@{preferred_username}"
    return path
        .replace("@[a-zA-Z0-9_-]*".toRegex(), "@{preferred_username}")
        .replace("/groups/[^/]+/members/[^/]+".toRegex(), "/groups/{group_slug}/members/{username}")
        .replace("/groups/[^/]+/members".toRegex(), "/groups/{group_slug}/members")
        .replace("/groups/[^/]+$".toRegex(), "/groups/{group_slug}")
        .replace("/reports.*".toRegex(), "/reports/{path...}")
        .replace("/backend-api/.*".toRegex(), "/backend-api/{path...}")
        .replace("/cameras-api/.*".toRegex(), "/cameras-api/{path...}")
        .replace("/scanner-api/status/[^/]+".toRegex(), "/scanner-api/status/{username}")
        .replace(
            "/profile/@[a-zA-Z0-9_-]*/debug-info".toRegex(),
            "/profile/@{preferred_username}/debug-info")
        .replace(
            "/short-links/admin".toRegex(), "/short-links/admin") // Admin must come before details
        .replace("/short-links/[0-9]+".toRegex(), "/short-links/{id}")
        .replace("/go/.*".toRegex(), "/go/{path...}")
        .replace("/committees/[^/]+".toRegex(), "/committees/{committee_slug}")
        .replace("/remote-access/connect/[^/]+".toRegex(), "/remote-access/connect/{connectionId}")
        .replace(
            "/remote-access/disconnect/[^/]+".toRegex(), "/remote-access/disconnect/{connectionId}")
  }

  fun getHandler(path: String): IRouteHandler? {
    val routesMap = DaggerRoutesComponent.create().getRoutesMap()
    return routesMap[path]
  }

  enum class Paths(val path: String) {
    INDEX("/"),
    LOGIN("/login"),
    PING("/ping"),
    OIDC_CALLBACK("/oidc-callback"),
    PROFILE("/profile/@{preferred_username}"),
    PROFILEME("/profile-me"),
    DISCOURSE_LINK("/link-discourse"),
    DISCOURSE_UNLINK("/unlink-discourse"),
    DISCOURSE_CALLBACK("/discourse-callback"),
    DISCORD_LINK("/link-discord"),
    DISCORD_UNLINK("/unlink-discord"),
    DISCORD_CALLBACK("/discord-callback"),
    LINKEDIN_LINK("/link-linkedin"),
    LINKEDIN_UNLINK("/unlink-linkedin"),
    LINKEDIN_CALLBACK("/linkedin-callback"),
    STATIC("/static"),
    GROUPS("/groups/{group_slug}"),
    GROUP_MEMBERS("/groups/{group_slug}/members"),
    GROUP_MEMBER_DETAIL("/groups/{group_slug}/members/{username}"),
    SEARCH_PRELOAD("/search-preload"),
    PROFILE_DEBUG_INFO("/profile/@{preferred_username}/debug-info"),
    PROFILE_TOTAL_ACTIVE_TIME("/profile/@{preferred_username}/total-active-time"),
    PROFILE_FEATURED_PROJECTS("/profile/@{preferred_username}/featured-projects"),
    REGISTER_VOTING("/profile/@{preferred_username}/register-voting"),
    UNREGISTER_VOTING("/profile/@{preferred_username}/unregister-voting"),
    PROFILE_SETTINGS("/profile/@{preferred_username}/settings"),
    REPORT("/reports/{path...}"),
    BACKEND_API("/backend-api/{path...}"),
    SHORT_LINKS("/short-links"),
    SHORT_LINKS_ADMIN("/short-links/admin"),
    SHORT_LINK_DETAILS("/short-links/{id}"),
    SHORT_LINK_REDIRECT("/go/{path...}"),
    COMMITTEES("/committees"),
    COMMITTEE_DETAIL("/committees/{committee_slug}"),
    INTERLOCK_TOOLS("/interlock-tools"),
    INTERLOCK_CABINETS("/interlock-cabinets"),
    ACTION_TRACK("/api/track"),
    SUGGESTED_EVENTS("/api/suggested-events"),
    SETTINGS_BANNER("/api/settings-banner"),
    ASK_AI("/ask-ai"),
    MANIFEST("/manifest.json"),
    OFFLINE("/offline"),
    CONFIG_ADMIN("/admin/config"),
    REMOTE_ACCESS("/remote-access"),
    REMOTE_ACCESS_CONNECT("/remote-access/connect/{connectionId}"),
    REMOTE_ACCESS_DISCONNECT("/remote-access/disconnect/{connectionId}"),
    SMARTWAIVER_WEBHOOK("/webhook/smartwaiver"),
    CAMERAS("/cameras"),
    CAMERAS_API("/cameras-api/{path...}"),
    PERSONAL_BIN_SCANNER("/personal-bin-scanner"),
    SCANNER_STATUS_API("/scanner-api/status/{username}"),
  }
}

@Singleton
@Component(
    modules =
        [
            RoutesModule::class,
            DiscordModule::class,
            DiscourseModule::class,
            LinkedInModule::class,
            VoterRegistrationModule::class])
interface RoutesComponent {
  fun getRoutesMap(): Map<String, IRouteHandler>
}
