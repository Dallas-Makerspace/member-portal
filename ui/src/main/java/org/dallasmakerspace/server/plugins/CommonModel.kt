package org.dallasmakerspace.server.plugins

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import io.ktor.server.thymeleaf.*
import java.util.concurrent.ConcurrentHashMap
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.routes.AuthenticatedHandler
import org.slf4j.LoggerFactory

private const val FLAG_CACHE_TTL_MS = 60_000L

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
    val remoteAccessEnabled =
        getCachedFeatureFlag(memberServiceClient, "remote-access.enabled", userSession?.userId, sessionId)
    cmLog.info("CommonModel: remoteAccessEnabled=$remoteAccessEnabled")
    proceedWith(
        ThymeleafContent(
            body.template,
            body.model +
                mapOf(
                    "isInfraUser" to isInfraUser,
                    "remoteAccessEnabled" to remoteAccessEnabled,
                    "current_username" to currentUsername,
                ),
            body.etag,
            body.contentType,
            body.locale,
            body.fragments,
        ))
  }
}
