package org.dallasmakerspace.server.memberservice

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
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
import org.dallasmakerspace.models.SocialNetwork
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.plugins.AuthException
import org.dallasmakerspace.server.routes.AuthenticatedHandler
import org.dallasmakerspace.server.routes.NotificationPreferences
import org.dallasmakerspace.server.routes.NotificationsInboxHandler
import org.dallasmakerspace.server.routes.SocialLinks

private const val COMMUNICATION_PREFERENCE_KEY = "communication_preference"

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
      handlePost(call, requestedUsername, currentUsername)
      return
    }

    val jsonMap = loadModel(requestedUsername, currentUsername)

    if (session.isSettingsUpdateSuccess) {
      log.info("Setting toast for successful settings update")
      call.sessions.set(session.copy(isSettingsUpdateSuccess = false))
      jsonMap["toast_message"] = "Contact preference updated."
    }
    settingsUpdatedToast(call.request.queryParameters["updated"])?.let {
      jsonMap["toast_message"] = it
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
            "contact_options" to
                contactOptions(
                    talkLinked = memberValue?.let { it.discourseUsername?.isNotEmpty() == true },
                    talkUsername = memberValue?.discourseUsername,
                    discordLinked = memberValue?.let { it.discordUserId?.isNotEmpty() == true },
                    discordUsername = memberValue?.discordUsername),
            "communication_preference" to
                (settings.await()[COMMUNICATION_PREFERENCE_KEY] ?: ""),
            "member_loaded" to (memberValue != null),
            "discourse_linked" to (memberValue?.discourseUsername?.isNotEmpty() == true),
            "discord_linked" to (memberValue?.discordUserId?.isNotEmpty() == true),
            "settings_history" to history.await().map(::settingsHistoryRow),
            "notification_preferences_enabled" to notificationsEnabled.await(),
        )
        .apply {
          memberValue?.discourseUsername?.takeIf { it.isNotEmpty() }?.let {
            put("discourse_username", it)
          }
          memberValue?.discordUsername?.takeIf { it.isNotEmpty() }?.let {
            put("discord_username", it)
          }
          putAll(notifications.await())
        }
  }

  private suspend fun notificationsEnabled(currentUsername: String?): Boolean =
      currentUsername?.let {
        memberServiceClient.isFeatureEnabled(
            NotificationsInboxHandler.FEATURE_FLAG_KEY, it, session.sessionId)
      } ?: false

  /** Each form posts a `form` name; the contact preference form predates it and sends none. */
  private suspend fun handlePost(
      call: ApplicationCall,
      requestedUsername: String,
      currentUsername: String?,
  ) {
    val params = call.receiveParameters()
    when (params["form"]) {
      NotificationPreferences.FORM_NAME ->
          saveNotificationPreferences(call, requestedUsername, currentUsername, params)
      SocialLinks.FORM_NAME -> saveSocialLinks(call, requestedUsername, currentUsername, params)
      null -> saveContactPreference(call, requestedUsername, currentUsername, params)
      else -> call.respond(HttpStatusCode.BadRequest)
    }
  }

  private suspend fun saveNotificationPreferences(
      call: ApplicationCall,
      requestedUsername: String,
      currentUsername: String?,
      params: Parameters,
  ) {
    if (!notificationsEnabled(currentUsername)) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    notificationPreferences.save(requestedUsername, session.sessionId, params)
    call.respondRedirect(
        "/profile/@$requestedUsername/settings?updated=${NotificationPreferences.FORM_NAME}",
        permanent = false)
  }

  private suspend fun saveSocialLinks(
      call: ApplicationCall,
      requestedUsername: String,
      currentUsername: String?,
      params: Parameters,
  ) {
    // The dialog validates as the member types, so an invalid link here bypassed it.
    val updates = SocialLinks.parseForm(params)
    if (updates == null) {
      call.respond(HttpStatusCode.BadRequest)
      return
    }
    memberService.updateMemberSettings(
        requestedUsername, currentUsername, updates, session.sessionId)
    call.respondRedirect(
        "/profile/@$requestedUsername?updated=${SocialLinks.FORM_NAME}", permanent = false)
  }

  private suspend fun saveContactPreference(
      call: ApplicationCall,
      requestedUsername: String,
      currentUsername: String?,
      params: Parameters,
  ) {
    val preference = params[COMMUNICATION_PREFERENCE_KEY]
    if (CommunicationPreference.entries.none { it.name == preference }) {
      call.respond(HttpStatusCode.BadRequest)
      return
    }
    memberService.updateMemberSettings(
        requestedUsername, currentUsername, preference, session.sessionId)

    // The profile shows the "updated" toast (see ProfileHandler.setToastMessage).
    call.sessions.set(session.copy(isSettingsUpdateSuccess = true))
    call.respondRedirect("/profile/@$requestedUsername", permanent = false)
  }

  companion object {
    /** `updated` values the unlink handlers redirect back to this page with. */
    const val TALK_UNLINKED = "talk-unlinked"
    const val DISCORD_UNLINKED = "discord-unlinked"
  }
}

/**
 * The cards on the contact preference form. Talk and Discord carry a `link_state` ("linked" or
 * "unlinked", absent when the member couldn't be loaded) so the card can show whether choosing it
 * will actually reach the member.
 */
internal fun contactOptions(
    talkLinked: Boolean?,
    talkUsername: String?,
    discordLinked: Boolean?,
    discordUsername: String?,
): List<Map<String, Any?>> {
  fun option(
      pref: CommunicationPreference,
      description: String,
      icon: String? = null,
      image: String? = null,
      label: String = pref.label,
      linkable: Boolean = false,
      linked: Boolean? = null,
      username: String? = null,
  ) =
      mapOf(
          "value" to pref.name,
          "label" to label,
          "description" to description,
          "icon" to icon,
          "image" to image,
          "link_state" to
              if (!linkable) null
              else
                  when (linked) {
                    true -> "linked"
                    false -> "unlinked"
                    null -> "unknown"
                  },
          "link_detail" to username?.takeIf { it.isNotEmpty() }?.let { "@$it" })

  return listOf(
      option(
          CommunicationPreference.EMAIL,
          "Members see your email address on your profile.",
          icon = "mail"),
      option(
          CommunicationPreference.DISCOURSE,
          "Members message you on Talk.",
          image = "/static/images/discourse.svg",
          linkable = true,
          linked = talkLinked,
          username = talkUsername),
      option(
          CommunicationPreference.DISCORD,
          "Members message you on Discord.",
          image = "/static/images/discord.svg",
          linkable = true,
          linked = discordLinked,
          username = discordUsername),
      option(
          CommunicationPreference.SMS,
          "Members see your phone number on your profile.",
          icon = "sms"),
      option(
          CommunicationPreference.NONE,
          "Members won't see a way to reach you.",
          icon = "visibility_off",
          label = "Don't list a contact method"),
  )
}

/** Toast for the settings page's `updated` query parameter, if it names a known change. */
internal fun settingsUpdatedToast(updated: String?): String? =
    when (updated) {
      NotificationPreferences.FORM_NAME -> "Notification preferences updated."
      MemberSettingsHandler.TALK_UNLINKED -> "Talk account unlinked."
      MemberSettingsHandler.DISCORD_UNLINKED -> "Discord account unlinked."
      else -> null
    }

/** A settings history entry from the service, plus the label and value the page shows. */
internal fun settingsHistoryRow(entry: Map<String, String?>): Map<String, Any?> {
  val key = entry["setting_key"].orEmpty()
  val value = entry["setting_value"]?.takeIf { it.isNotEmpty() }
  val network = SocialNetwork.fromSettingKey(key)
  val label =
      when {
        key == COMMUNICATION_PREFERENCE_KEY -> "Contact preference"
        network != null -> network.displayName
        else -> key
      }
  val displayValue =
      when {
        key == COMMUNICATION_PREFERENCE_KEY ->
            value?.let { v -> CommunicationPreference.entries.find { it.name == v }?.label ?: v }
                ?: "Not set"
        network != null -> value?.let { network.profileUrl(it) } ?: "Removed"
        else -> value ?: "Cleared"
      }
  return entry +
      mapOf("label" to label, "display_value" to displayValue, "is_cleared" to (value == null))
}
