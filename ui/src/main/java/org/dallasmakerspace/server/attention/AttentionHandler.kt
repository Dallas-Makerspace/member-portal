package org.dallasmakerspace.server.attention

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.routes.AuthenticatedHandler

/** `/api/attention`: everything flagged for the signed-in member, read by dms-attention.js. */
class AttentionHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val sources: Set<@JvmSuppressWildcards AttentionSource>,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  @Suppress("TooGenericExceptionCaught")
  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val username = userInfo["preferred_username"] as? String
    if (username.isNullOrBlank()) {
      call.respond(mapOf("items" to emptyList<AttentionItem>()))
      return
    }
    val items = coroutineScope {
      sources
          .map { source ->
            async {
              try {
                source.check(username, session.sessionId)
              } catch (e: Exception) {
                log.warn("Attention source ${source.javaClass.simpleName} failed: ${e.message}")
                null
              }
            }
          }
          .awaitAll()
          .filterNotNull()
          .sortedBy { it.key }
    }
    call.respond(mapOf("items" to items))
  }
}
