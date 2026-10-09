package org.dallasmakerspace.server.memberservice

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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.dallasmakerspace.models.CommunicationPreference
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.plugins.AuthException
import org.dallasmakerspace.server.routes.AuthenticatedHandler
import org.dallasmakerspace.server.routes.NotificationPreferences
import org.dallasmakerspace.server.routes.NotificationsInboxHandler

class MemberSettingsHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberService: MemberService,
    private val memberServiceClient: MemberServiceClient,
    private val notificationPreferences: NotificationPreferences,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val requestedUsername =
        call.parameters["preferred_username"]
            ?: throw AuthException("No username found in url path")

    val currentUsername = userInfo["preferred_username"] as String?
    val canManageMembers = authz.can(Permission.MANAGE_MEMBERS.name)
    if (requestedUsername != currentUsername && !canManageMembers) {
      call.respond(HttpStatusCode.Forbidden)
      return
    }

    if (call.request.httpMethod == HttpMethod.Post) {
      handlePost(call, requestedUsername, currentUsername, notificationsEnabled(currentUsername))
      return
    }

    val jsonMap = loadModel(requestedUsername, currentUsername)

    if (session.isSettingsUpdateSuccess) {
      log.info("Setting toast for successful settings update")
      call.sessions.set(session.copy(isSettingsUpdateSuccess = false))
      jsonMap["toast_message"] = "Contact preference updated."
    }
    if (call.request.queryParameters["updated"] == NotificationPreferences.FORM_NAME) {
      jsonMap["toast_message"] = "Notification preferences updated."
    }

    call.respond(ThymeleafContent("settings", jsonMap))
  }

  /** The page's backend calls are independent, so they run concurrently. */
  @Suppress("TooGenericExceptionCaught", "SwallowedException")
  private suspend fun loadModel(
      requestedUsername: String,
      currentUsername: String?,
  ): MutableMap<String, Any> = coroutineScope {
    val sessionId = session.sessionId
    val notificationsEnabled = async { notificationsEnabled(currentUsername) }
    val settings = async { memberService.getMemberSettings(requestedUsername, sessionId) }
    val history = async { memberService.getMemberSettingsHistory(requestedUsername, sessionId) }
    val member = async {
      try {
        memberService.getMember(requestedUsername, sessionId)
      } catch (e: Exception) {
        log.warn("Failed to fetch member for settings page: $requestedUsername", e)
        null
      }
    }
    val notifications = async {
      if (notificationsEnabled.await()) notificationPreferences.model(requestedUsername, sessionId)
      else emptyMap()
    }
    val memberValue = member.await()
    mutableMapOf<String, Any>(
            "preferred_username" to requestedUsername,
            "is_self" to (requestedUsername == currentUsername).toString(),
            "authz" to authz,
            "communication_preferences" to
                CommunicationPreference.entries.map { pref ->
                  mapOf("value" to pref.name, "label" to pref.label)
                },
            "communication_preference" to
                (settings.await()["communication_preference"] ?: ""),
            "discourse_linked" to (memberValue?.discourseUsername?.isNotEmpty() == true),
            "discord_linked" to (memberValue?.discordUserId?.isNotEmpty() == true),
            "settings_history" to history.await(),
            "notification_preferences_enabled" to notificationsEnabled.await(),
        )
        .apply { putAll(notifications.await()) }
  }

  private suspend fun notificationsEnabled(currentUsername: String?): Boolean =
      currentUsername?.let {
        memberServiceClient.isFeatureEnabled(
            NotificationsInboxHandler.FEATURE_FLAG_KEY, it, session.sessionId)
      } ?: false

  private suspend fun handlePost(
      call: ApplicationCall,
      requestedUsername: String,
      currentUsername: String?,
      notificationsEnabled: Boolean,
  ) {
    val params = call.receiveParameters()
    if (params["form"] == NotificationPreferences.FORM_NAME) {
      if (notificationsEnabled) {
        notificationPreferences.save(requestedUsername, session.sessionId, params)
        call.respondRedirect(
            "/profile/@$requestedUsername/settings?updated=${NotificationPreferences.FORM_NAME}",
            permanent = false)
      } else {
        call.respond(HttpStatusCode.NotFound)
      }
      return
    }

    memberService.updateMemberSettings(
        requestedUsername, currentUsername, params["communication_preference"], session.sessionId)

    // The profile shows the "updated" toast (see ProfileHandler.setToastMessage).
    call.sessions.set(session.copy(isSettingsUpdateSuccess = true))
    call.respondRedirect("/profile/@$requestedUsername", permanent = false)
  }
}
