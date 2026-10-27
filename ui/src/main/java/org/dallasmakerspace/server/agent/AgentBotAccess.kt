package org.dallasmakerspace.server.agent

import io.ktor.http.HttpMethod
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
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

  @Suppress("TooGenericExceptionCaught")
  suspend fun canUse(botKey: String, username: String?, sessionId: String?): Boolean {
    if (username == null) return false
    val cached = cache[username]
    if (cached != null && System.currentTimeMillis() < cached.expiresAt) return botKey in cached.bots
    val bots =
        try {
          val response = client.call(HttpMethod.Get, "/agent/bots", username, sessionId)
          if (response.isSuccess) parseBotKeys(response.body) else emptySet()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          emptySet()
        }
    cache[username] = Cached(bots, System.currentTimeMillis() + TTL_MS)
    return botKey in bots
  }

  companion object {
    private const val TTL_MS = 60_000L

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
