package org.dallasmakerspace.server.plugins

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import io.ktor.server.thymeleaf.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.routes.AuthenticatedHandler
import org.dallasmakerspace.server.routes.NotificationsInboxHandler
import org.dallasmakerspace.server.routes.ProfileFollowsHandler
import org.slf4j.LoggerFactory

private const val FLAG_CACHE_TTL_MS = 60_000L
private const val ACCOUNT_MENU_FLAG = "nav.account-menu.enabled"
private const val REMOTE_ACCESS_FLAG = "remote-access.enabled"

private data class CachedFlag(val value: Boolean, val expiresAt: Long)

private val flagCache = ConcurrentHashMap<String, CachedFlag>()

private val cmLog = LoggerFactory.getLogger("CommonModel")

private suspend fun getCachedFeatureFlag(
    client: MemberServiceClient,
    key: String,
    username: String?,
    sessionId: String?,
): Boolean {
  if (username == null) return false
  val cacheKey = "$key:$username"
  val cached = flagCache[cacheKey]
  if (cached != null && System.currentTimeMillis() < cached.expiresAt) return cached.value
  val value = client.isFeatureEnabled(key, username, sessionId)
  cmLog.info("Feature flag $key for $username resolved to $value")
  flagCache[cacheKey] = CachedFlag(value, System.currentTimeMillis() + FLAG_CACHE_TTL_MS)
  return value
}

fun Application.configureCommonModel(memberServiceClient: MemberServiceClient) {
  sendPipeline.intercept(ApplicationSendPipeline.Before) {
    val body = subject
    if (body !is ThymeleafContent) return@intercept
    val isInfraUser = call.attributes.getOrNull(AuthenticatedHandler.IS_INFRA_USER_KEY) ?: false
    val userSession = call.sessions.get<UserSession>()
    val sessionId = userSession?.sessionId
    val currentUsername = userSession?.userId ?: ""
    cmLog.info(
        "CommonModel: template=${body.template} isInfraUser=$isInfraUser sessionId=$sessionId")
    val userId = userSession?.userId
    val flags = coroutineScope {
      listOf(
              REMOTE_ACCESS_FLAG,
              ACCOUNT_MENU_FLAG,
              ProfileFollowsHandler.FEATURE_FLAG_KEY,
              NotificationsInboxHandler.FEATURE_FLAG_KEY)
          .distinct()
          .associateWith { key ->
            async { getCachedFeatureFlag(memberServiceClient, key, userId, sessionId) }
          }
          .mapValues { it.value.await() }
    }
    val remoteAccessEnabled = flags.getValue(REMOTE_ACCESS_FLAG)
    cmLog.info("CommonModel: remoteAccessEnabled=$remoteAccessEnabled")
    val accountMenuEnabled = flags.getValue(ACCOUNT_MENU_FLAG)
    val followsMenuEnabled =
        accountMenuEnabled && flags.getValue(ProfileFollowsHandler.FEATURE_FLAG_KEY)
    val notificationsMenuEnabled =
        accountMenuEnabled && flags.getValue(NotificationsInboxHandler.FEATURE_FLAG_KEY)
    val displayName =
        call.attributes.getOrNull(AuthenticatedHandler.DISPLAY_NAME_KEY) ?: currentUsername
    val firstName =
        call.attributes.getOrNull(AuthenticatedHandler.FIRST_NAME_KEY) ?: currentUsername
    proceedWith(
        ThymeleafContent(
            body.template,
            body.model +
                mapOf(
                    "isInfraUser" to isInfraUser,
                    "remoteAccessEnabled" to remoteAccessEnabled,
                    "current_username" to currentUsername,
                    "current_display_name" to displayName,
                    "current_first_name" to firstName,
                    "accountMenuEnabled" to accountMenuEnabled,
                    "followsMenuEnabled" to followsMenuEnabled,
                    "notificationsMenuEnabled" to notificationsMenuEnabled,
                ),
            body.etag,
            body.contentType,
            body.locale,
            body.fragments,
        ))
  }
}
