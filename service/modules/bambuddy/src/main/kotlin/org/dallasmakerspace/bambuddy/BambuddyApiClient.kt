package org.dallasmakerspace.bambuddy

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.DmsHttpEngine
import org.dallasmakerspace.core.LoggerFactory

// Bambuddy's own token is documented as valid 60 minutes; refresh a bit early so a request never
// races an about-to-expire token.
private const val STREAM_TOKEN_TTL_MS = 55 * 60 * 1000L

// A live stream has no overall deadline; a camera that stops sending frames for this long is dropped.
private const val STREAM_IDLE_TIMEOUT_MS = 30_000L

@Singleton
class BambuddyApiClient
@Inject
constructor(private val appConfig: AppConfig, loggerFactory: LoggerFactory) : IBambuddyApiClient {
  private val log = loggerFactory.create(javaClass)

  private val json = Json { ignoreUnknownKeys = true }

  private val baseUrl: String by lazy { appConfig.requireStringProperty("app.bambuddy.base-url") }
  private val apiKey: String by lazy { appConfig.requireStringProperty("app.bambuddy.api-key") }

  private data class CachedStreamToken(val token: String, val expiresAt: Long)

  // Camera stream/snapshot endpoints don't accept the plain X-API-Key header at all - they require
  // a separate short-lived "stream token" (POST /printers/camera/stream-token, ?token=... on the
  // snapshot URL). Verified against a live instance; not documented in Bambuddy's third-party API
  // reference this integration was originally written against.
  private val streamTokenCache = AtomicReference<CachedStreamToken?>(null)

  private suspend fun getStreamToken(): String {
    val cached = streamTokenCache.get()
    if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
      return cached.token
    }
    val response =
        newClient().use { client ->
          client.post("$baseUrl/api/v1/printers/camera/stream-token") { header("X-API-Key", apiKey) }
        }
    if (response.status != HttpStatusCode.OK) {
      throw BambuddyApiException(
          "Failed to obtain Bambuddy stream token: ${response.status} - ${response.bodyAsText()}")
    }
    val token =
        json.parseToJsonElement(response.bodyAsText()).jsonObject["token"]?.jsonPrimitive?.content
            ?: throw BambuddyApiException("No token in Bambuddy stream-token response")
    streamTokenCache.set(CachedStreamToken(token, System.currentTimeMillis() + STREAM_TOKEN_TTL_MS))
    return token
  }

  private fun newClient() =
      HttpClient(DmsHttpEngine) {
        install(HttpTimeout) {
          requestTimeoutMillis = 10_000
          connectTimeoutMillis = 5_000
          socketTimeoutMillis = 10_000
        }
        install(Logging) {
          logger = Logger.DEFAULT
          level = LogLevel.INFO
        }
      }

  override suspend fun getPrinters(): List<BambuPrinter> {
    try {
      val response =
          newClient().use { client ->
            // Trailing slash required - Bambuddy 404s on /api/v1/printers without it.
            client.get("$baseUrl/api/v1/printers/") { header("X-API-Key", apiKey) }
          }
      if (response.status != HttpStatusCode.OK) {
        throw BambuddyApiException(
            "Failed to fetch Bambuddy printers: ${response.status} - ${response.bodyAsText()}")
      }
      return json.decodeFromString(ListSerializer(BambuPrinter.serializer()), response.bodyAsText())
    } catch (e: BambuddyApiException) {
      throw e
    } catch (e: Exception) {
      throw BambuddyApiException("Failed to fetch Bambuddy printers", e)
    }
  }

  override suspend fun getPrinterStatus(printerId: Int): BambuPrinterStatus? {
    return try {
      val response =
          newClient().use { client ->
            client.get("$baseUrl/api/v1/printers/$printerId/status") { header("X-API-Key", apiKey) }
          }
      if (response.status != HttpStatusCode.OK) {
        log.warn("Bambuddy status for printer $printerId returned ${response.status}")
        return null
      }
      json.decodeFromString(BambuPrinterStatus.serializer(), response.bodyAsText())
    } catch (e: Exception) {
      log.warn("Failed to fetch Bambuddy status for printer $printerId", e)
      null
    }
  }

  override suspend fun getQueue(): List<BambuQueueItem> {
    try {
      val response =
          newClient().use { client ->
            // Trailing slash required - Bambuddy 404s on /api/v1/queue without it.
            client.get("$baseUrl/api/v1/queue/") { header("X-API-Key", apiKey) }
          }
      if (response.status != HttpStatusCode.OK) {
        throw BambuddyApiException(
            "Failed to fetch Bambuddy queue: ${response.status} - ${response.bodyAsText()}")
      }
      return json.decodeFromString(ListSerializer(BambuQueueItem.serializer()), response.bodyAsText())
    } catch (e: BambuddyApiException) {
      throw e
    } catch (e: Exception) {
      throw BambuddyApiException("Failed to fetch Bambuddy queue", e)
    }
  }

  override suspend fun getActiveQueue(): List<BambuQueueItem> =
      json.decodeFromString(
          ListSerializer(BambuQueueItem.serializer()),
          getText("$baseUrl/api/v1/queue/?status=printing,pending", "active queue"))

  override suspend fun getLatestFinished(printerId: Int, status: String): BambuQueueItem? =
      json
          .decodeFromString(
              BambuQueueHistoryPage.serializer(),
              getText(
                  "$baseUrl/api/v1/queue/history?printer_id=$printerId&status=$status&limit=1",
                  "$status history for printer $printerId"))
          .items
          .firstOrNull()

  private suspend fun getText(url: String, description: String): String {
    try {
      val response = newClient().use { client -> client.get(url) { header("X-API-Key", apiKey) } }
      if (response.status != HttpStatusCode.OK) {
        throw BambuddyApiException(
            "Failed to fetch Bambuddy $description: ${response.status} - ${response.bodyAsText()}")
      }
      return response.bodyAsText()
    } catch (e: BambuddyApiException) {
      throw e
    } catch (e: Exception) {
      throw BambuddyApiException("Failed to fetch Bambuddy $description", e)
    }
  }

  override suspend fun getCameraSnapshot(printerId: Int): Pair<ByteArray, String>? {
    return try {
      val token = getStreamToken()
      newClient().use { client ->
        val response = client.get("$baseUrl/api/v1/printers/$printerId/camera/snapshot?token=$token")
        if (response.status != HttpStatusCode.OK) {
          log.warn("Bambuddy camera snapshot for $printerId returned ${response.status}")
          return null
        }
        val contentType = response.contentType()?.toString() ?: ContentType.Image.JPEG.toString()
        response.body<ByteArray>() to contentType
      }
    } catch (e: Exception) {
      log.warn("Failed to fetch Bambuddy camera snapshot for $printerId", e)
      null
    }
  }

  override suspend fun streamCamera(
      printerId: Int,
      onOpen: suspend (contentType: String, body: ByteReadChannel) -> Unit,
  ): Boolean =
      try {
        val token = getStreamToken()
        newStreamClient().use { client ->
          client.prepareGet("$baseUrl/api/v1/printers/$printerId/camera/stream?token=$token").execute {
              response ->
            if (response.status != HttpStatusCode.OK) {
              log.warn("Bambuddy camera stream for $printerId returned ${response.status}")
              false
            } else {
              onOpen(response.contentType()?.toString() ?: "multipart/x-mixed-replace", response.bodyAsChannel())
              true
            }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.warn("Bambuddy camera stream for $printerId ended: ${e.message}")
        false
      }

  private fun newStreamClient() =
      HttpClient(DmsHttpEngine) {
        install(HttpTimeout) {
          requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
          connectTimeoutMillis = 5_000
          socketTimeoutMillis = STREAM_IDLE_TIMEOUT_MS
        }
      }

  override suspend fun getPrinterModelIcon(model: String): Pair<ByteArray, String>? {
    // Bambuddy's own icon filenames are the model name lowercased with spaces/punctuation
    // stripped (e.g. "A1 Mini" -> a1mini.png). No API for this - reverse-engineered from the
    // static file list on a live instance. Unauthenticated static file, no API key needed.
    val slug = model.lowercase().replace(Regex("[^a-z0-9]"), "")
    return fetchIcon(slug) ?: fetchIcon("default")
  }

  private suspend fun fetchIcon(slug: String): Pair<ByteArray, String>? {
    return try {
      newClient().use { client ->
        val response = client.get("$baseUrl/img/printers/$slug.png")
        if (response.status != HttpStatusCode.OK) return null
        val contentType = response.contentType()?.toString() ?: ContentType.Image.PNG.toString()
        response.body<ByteArray>() to contentType
      }
    } catch (e: Exception) {
      log.warn("Failed to fetch Bambuddy printer icon '$slug'", e)
      null
    }
  }
}
