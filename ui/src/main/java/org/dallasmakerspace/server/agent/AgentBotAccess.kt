package org.dallasmakerspace.server.agent

import io.ktor.http.HttpMethod
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Whether a member may use a bot right now, as the service decides (flag and audience). */
@Singleton
class AgentBotAccess @Inject constructor(private val client: AgentServiceClient) {
  private data class Cached(val bots: Set<String>, val expiresAt: Long)

  private val cache = ConcurrentHashMap<String, Cached>()

  suspend fun canUse(botKey: String, username: String?, sessionId: String?): Boolean =
      botKey in botKeys(username, sessionId)

  /**
   * Every bot [username] may use, from one cached service call. A slow or failing service means
   * "none" for a short while, so a hung service can't stall every page render.
   */
  @Suppress("TooGenericExceptionCaught")
  suspend fun botKeys(username: String?, sessionId: String?): Set<String> {
    if (username == null) return emptySet()
    val cached = cache[username]
    if (cached != null && System.currentTimeMillis() < cached.expiresAt) return cached.bots
    var ok = false
    val bots =
        try {
          val response =
              withTimeoutOrNull(CALL_TIMEOUT_MS) {
                client.call(HttpMethod.Get, "/agent/bots", username, sessionId)
              }
          if (response != null && response.isSuccess) {
            ok = true
            parseBotKeys(response.body)
          } else emptySet()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          emptySet()
        }
    val ttl = if (ok) TTL_MS else FAILURE_TTL_MS
    cache[username] = Cached(bots, System.currentTimeMillis() + ttl)
    return bots
  }

  companion object {
    private const val TTL_MS = 60_000L
    private const val FAILURE_TTL_MS = 15_000L
    private const val CALL_TIMEOUT_MS = 2_000L

    fun parseBotKeys(body: String): Set<String> =
        try {
          val data = Json.parseToJsonElement(body).jsonObject["data"] as? JsonArray
          data.orEmpty()
              .mapNotNull { (it as? JsonObject)?.get("key")?.jsonPrimitive?.contentOrNull }
              .toSet()
        } catch (e: IllegalArgumentException) {
          emptySet()
        }
  }
}
