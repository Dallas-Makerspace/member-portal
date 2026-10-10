package org.dallasmakerspace.server.routes

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.thymeleaf.ThymeleafContent
import javax.inject.Inject
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.models.getNameFromSlug
import org.dallasmakerspace.server.plugins.AuthException

/**
 * Renders and updates group-level settings. See GroupSettingsRegistry on the service side for
 * where each setting is enforced.
 *
 * The two known settings below are duplicated from the service module's GroupSettingsRegistry
 * (the UI module cannot depend on the service module). Adding a third setting requires updating
 * both GroupSettingsRegistry (service) and SETTINGS_META (here).
 */
class GroupSettingsHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberService: MemberService,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  companion object {
    const val FEATURE_FLAG_KEY = "groups.settings-enabled"

    val SETTINGS_META =
        listOf(
            mapOf(
                "key" to "allow_self_add_remove",
                "label" to "Allow members to add/remove themselves",
                "description" to
                    "Shows a Join/Leave button on this group's page so members can add or " +
                        "remove themselves without admin action. Leaving only removes direct " +
                        "membership, not membership inherited through a nested group.",
            ),
            mapOf(
                "key" to "allow_follow",
                "label" to "Allow members to follow this group",
                "description" to
                    "Whether members are allowed to \"Follow\" this group for updates. Not yet " +
                        "enforced anywhere.",
            ),
        )
  }

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    if (!call.require(Permission.MANAGE_GROUPS)) return

    val actorUsername = userInfo["preferred_username"] as? String
    // groups.settings-enabled is a FeatureFlagType (can be piloted per admin/AD group); without a
    // resolvable actor there's no one to check pilot membership against, so treat it as disabled.
    val featureEnabled =
        actorUsername?.let {
          memberServiceClient.isFeatureEnabled(FEATURE_FLAG_KEY, it, session.sessionId)
        } ?: false
    if (!featureEnabled) {
      call.respond(HttpStatusCode.NotFound)
      return
    }

    val groupSlug =
        call.parameters["group_slug"] ?: throw AuthException("No group slug found in url path")
    val groupName = getNameFromSlug(groupSlug)

    if (call.request.httpMethod == HttpMethod.Post) {
      val params = call.receiveParameters()
      // Unchecked checkboxes are omitted from the submitted form entirely, so every known key
      // must be defaulted to "false" rather than left out of the update.
      val updates =
          SETTINGS_META.associate { meta ->
            val key = meta.getValue("key")
            key to (if (params[key] != null) "true" else "false")
          }

      memberService.updateGroupSettings(groupSlug, actorUsername, updates, session.sessionId)

      call.sessions.set(session.copy(isGroupSettingsUpdateSuccess = true))
      call.respondRedirect("/groups/$groupSlug/settings", permanent = false)
    } else {
      val settings = memberService.getGroupSettings(groupSlug, session.sessionId)
      val history = memberService.getGroupSettingsHistory(groupSlug, session.sessionId)

      val jsonMap =
          mutableMapOf<String, Any>(
              "name" to groupName,
              "slug" to groupSlug,
              "authz" to authz,
              "settings_meta" to
                  SETTINGS_META.map { meta ->
                    meta + ("enabled" to (settings[meta.getValue("key")] == "true"))
                  },
              "settings_history" to history,
          )

      if (session.isGroupSettingsUpdateSuccess) {
        call.sessions.set(session.copy(isGroupSettingsUpdateSuccess = false))
        jsonMap["toast_message"] = "Group settings updated."
      }

      call.respond(ThymeleafContent("group-settings", jsonMap))
    }
  }
}
