package org.dallasmakerspace.server.routes

import dagger.Component
import io.ktor.server.application.*
import io.ktor.server.request.*
import javax.inject.Singleton
import org.dallasmakerspace.server.attention.di.AttentionModule
import org.dallasmakerspace.server.bambustatus.di.BambuStatusModule
import org.dallasmakerspace.server.di.DiscordModule
import org.dallasmakerspace.server.di.DiscourseModule
import org.dallasmakerspace.server.di.RoutesModule
import org.dallasmakerspace.server.di.VoterRegistrationModule
import org.dallasmakerspace.server.moodle.di.MoodleModule

object RouteFactory {
  val redirects = mutableMapOf<String, String>()

  // getHandler() runs on every single incoming request. DaggerRoutesComponent.create() builds an
  // entirely independent object graph each time it's called — @Singleton scope only guarantees one
  // instance *within* a component instance, not across separate .create() calls, and this is a
  // wholly separate component from AppComponent/AppComponentHolder. Calling .create() fresh per
  // request meant every handler-level dependency (UserInfoProvider, and anything handlers hold like
  // MemberServiceClient/DMSHttpClient) was rebuilt from scratch on every request — silently
  // reintroducing the "new HttpClient per request" cost that AppComponentHolder was supposed to
  // have already fixed elsewhere, and the reason the userinfo cache never actually cached anything.
  private val routesComponent: RoutesComponent by lazy { DaggerRoutesComponent.create() }

  fun getHandler(call: ApplicationCall): IRouteHandler? {
    val path = sanitizePathInternal(call.request.path())
    return getHandler(path)
  }

  internal fun sanitizePathInternal(path: String): String {
    // Replace "@.*" with "@{preferred_username}"
    return path
        .replace("@[a-zA-Z0-9_-]*".toRegex(), "@{preferred_username}")
        .replace("/groups/[^/]+/members/[^/]+".toRegex(), "/groups/{group_slug}/members/{username}")
        .replace("/groups/[^/]+/members".toRegex(), "/groups/{group_slug}/members")
        .replace("/groups/[^/]+/settings".toRegex(), "/groups/{group_slug}/settings")
        .replace("/groups/[^/]+/self$".toRegex(), "/groups/{group_slug}/self")
        .replace("/groups/[^/]+$".toRegex(), "/groups/{group_slug}")
        .replace("/reports.*".toRegex(), "/reports/{path...}")
        .replace("/backend-api/.*".toRegex(), "/backend-api/{path...}")
        .replace("/cameras-api/.*".toRegex(), "/cameras-api/{path...}")
        .replace("/scanner-api/status/[^/]+".toRegex(), "/scanner-api/status/{username}")
        .replace(
            "/profile/@[a-zA-Z0-9_-]*/debug-info".toRegex(),
            "/profile/@{preferred_username}/debug-info")
        .replace(
            "/profile/@[a-zA-Z0-9_-]*/kiosk-waiver-photos".toRegex(),
            "/profile/@{preferred_username}/kiosk-waiver-photos")
        .replace(
            "/short-links/admin".toRegex(), "/short-links/admin") // Admin must come before details
        .replace("/short-links/[0-9]+".toRegex(), "/short-links/{id}")
        .replace("/go/.*".toRegex(), "/go/{path...}")
        .replace("/bambu-api/printers/[^/]+/camera".toRegex(), "/bambu-api/printers/{id}/camera")
        .replace("/bambu-api/models/[^/]+/icon".toRegex(), "/bambu-api/models/{model}/icon")
        .replace("/bambu-api/watches/[^/]+$".toRegex(), "/bambu-api/watches/{id}")
        // Must come before the generic /committees/{committee_slug} rule below, or that rule's
        // [^/]+ would swallow "3d-fab" and leave a stray "/printers" behind.
        .replace("/committees/(?!3d-fab/printers$)[^/]+".toRegex(), "/committees/{committee_slug}")
        .replace("/remote-access/connect/[^/]+".toRegex(), "/remote-access/connect/{connectionId}")
        .replace(
            "/remote-access/disconnect/[^/]+".toRegex(), "/remote-access/disconnect/{connectionId}")
  }

  fun getHandler(path: String): IRouteHandler? {
    val routesMap = routesComponent.getRoutesMap()
    return routesMap[path]
  }

  enum class Paths(val path: String) {
    INDEX("/"),
    LOGIN("/login"),
    PING("/ping"),
    OIDC_CALLBACK("/oidc-callback"),
    PROFILE("/profile/@{preferred_username}"),
    AVATAR("/avatar/@{preferred_username}"),
    PROFILEME("/profile-me"),
    DISCOURSE_LINK("/link-discourse"),
    DISCOURSE_UNLINK("/unlink-discourse"),
    DISCOURSE_CALLBACK("/discourse-callback"),
    DISCORD_LINK("/link-discord"),
    DISCORD_UNLINK("/unlink-discord"),
    DISCORD_CALLBACK("/discord-callback"),
    STATIC("/static"),
    GROUPS("/groups/{group_slug}"),
    GROUP_MEMBERS("/groups/{group_slug}/members"),
    GROUP_MEMBER_DETAIL("/groups/{group_slug}/members/{username}"),
    GROUP_SETTINGS("/groups/{group_slug}/settings"),
    GROUP_SELF_MEMBERSHIP("/groups/{group_slug}/self"),
    SEARCH_PRELOAD("/search-preload"),
    PROFILE_DEBUG_INFO("/profile/@{preferred_username}/debug-info"),
    PROFILE_KIOSK_WAIVER_PHOTOS("/profile/@{preferred_username}/kiosk-waiver-photos"),
    PROFILE_TOTAL_ACTIVE_TIME("/profile/@{preferred_username}/total-active-time"),
    PROFILE_FEATURED_PROJECTS("/profile/@{preferred_username}/featured-projects"),
    REGISTER_VOTING("/profile/@{preferred_username}/register-voting"),
    UNREGISTER_VOTING("/profile/@{preferred_username}/unregister-voting"),
    PROFILE_SETTINGS("/profile/@{preferred_username}/settings"),
    PROFILE_FOLLOWS("/profile/@{preferred_username}/follows"),
    PROFILE_PHOTO("/profile/@{preferred_username}/photo"),
    REPORT("/reports/{path...}"),
    BACKEND_API("/backend-api/{path...}"),
    SHORT_LINKS("/short-links"),
    SHORT_LINKS_ADMIN("/short-links/admin"),
    SHORT_LINK_DETAILS("/short-links/{id}"),
    SHORT_LINK_REDIRECT("/go/{path...}"),
    COMMITTEES("/committees"),
    COMMITTEE_DETAIL("/committees/{committee_slug}"),
    COMMITTEE_FOLLOWERS("/committees/{committee_slug}/followers"),
    COMMITTEE_MESSAGE("/committees/{committee_slug}/message"),
    BAMBU_PRINTER_STATUS("/committees/3d-fab/printers"),
    BAMBU_CAMERA_API("/bambu-api/printers/{id}/camera"),
    BAMBU_MODEL_ICON_API("/bambu-api/models/{model}/icon"),
    BAMBU_WATCHES_API("/bambu-api/watches"),
    BAMBU_WATCH_API("/bambu-api/watches/{id}"),
    INTERLOCK_TOOLS("/interlock-tools"),
    INTERLOCK_CABINETS("/interlock-cabinets"),
    ACTION_TRACK("/api/track"),
    SUGGESTED_EVENTS("/api/suggested-events"),
    SUGGESTED_COURSES("/api/suggested-courses"),
    MEMBERS_TO_FOLLOW("/api/members-to-follow"),
    COMMITTEE_COURSES("/api/committee-courses"),
    SETTINGS_BANNER("/api/settings-banner"),
    ATTENTION("/api/attention"),
    SIGN_OUT("/sign-out"),
    ASK_AI("/ask-ai"),
    MANIFEST("/manifest.json"),
    OFFLINE("/offline"),
    CONFIG_ADMIN("/admin/config"),
    REMOTE_ACCESS("/remote-access"),
    REMOTE_ACCESS_REFRESH("/remote-access/refresh"),
    REMOTE_ACCESS_CONNECT("/remote-access/connect/{connectionId}"),
    REMOTE_ACCESS_DISCONNECT("/remote-access/disconnect/{connectionId}"),
    SMARTWAIVER_WEBHOOK("/webhook/smartwaiver"),
    SPARKPOST_WEBHOOK("/webhook/sparkpost"),
    DISCOURSE_WEBHOOK("/webhook/discourse"),
    MOODLE_WEBHOOK("/webhook/moodle"),
    CAMERAS("/cameras"),
    CAMERAS_API("/cameras-api/{path...}"),
    PERSONAL_BIN_SCANNER("/personal-bin-scanner"),
    SCANNER_STATUS_API("/scanner-api/status/{username}"),
    NOTIFICATIONS("/profile/@{preferred_username}/notifications"),
    WORKFLOWS_API("/api/workflows/{path...}"),
    WORKFLOWS_ADMIN("/admin/workflows"),
    WORKFLOW_RUN("/workflows/runs/{runId}"),
    WORKFLOW_DEMO("/workflows/demo"),
  }
}

@Singleton
@Component(
    modules =
        [
            RoutesModule::class,
            DiscordModule::class,
            DiscourseModule::class,
            VoterRegistrationModule::class,
            BambuStatusModule::class,
            MoodleModule::class,
            AttentionModule::class])
interface RoutesComponent {
  fun getRoutesMap(): Map<String, IRouteHandler>
}
