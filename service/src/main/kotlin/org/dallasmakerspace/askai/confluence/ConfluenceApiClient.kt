package org.dallasmakerspace.askai.confluence

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okio.IOException
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.DmsHttpEngine
import org.dallasmakerspace.core.LoggerFactory

private const val REQUEST_TIMEOUT_MS = 30000L

@Singleton
class ConfluenceApiClient
@Inject
constructor(private val appConfig: AppConfig, loggerFactory: LoggerFactory) : IConfluenceApiClient {
  private val log = loggerFactory.create(javaClass)

  private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
  }

  private val baseUrl: String by lazy { appConfig.requireStringProperty("app.confluence.baseUrl") }

  private val apiToken: String by lazy { appConfig.requireStringProperty("app.confluence.apiKey") }

  // Use Bearer token authentication for Personal Access Tokens (Confluence 7.9+)
  private val authHeader: String by lazy { "Bearer $apiToken" }

  private fun getClient() =
      HttpClient(DmsHttpEngine) {
        install(HttpTimeout) {
          requestTimeoutMillis = REQUEST_TIMEOUT_MS
          connectTimeoutMillis = REQUEST_TIMEOUT_MS
          socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
        install(Logging) {
          logger = Logger.DEFAULT
          level = LogLevel.INFO
        }
      }

  override suspend fun search(
      query: String,
      limit: Int,
      contentType: ContentTypeFilter
  ): ConfluenceSearchResponse {
    // Confluence uses CQL (Confluence Query Language) for search
    // Build base query for text/title matching
    val baseQuery = "(text ~ \"${escapeQuery(query)}\" OR title ~ \"${escapeQuery(query)}\")"

    // Add content type filter if specified
    val cql =
        when (contentType) {
          ContentTypeFilter.ALL -> baseQuery
          ContentTypeFilter.PAGES_ONLY -> "$baseQuery AND type = \"page\""
          ContentTypeFilter.ATTACHMENTS_ONLY -> "$baseQuery AND type = \"attachment\""
        }

    val encodedCql = java.net.URLEncoder.encode(cql, "UTF-8")
    val url = "$baseUrl/rest/api/content/search?cql=$encodedCql&limit=$limit&excerpt=highlight"

    log.debug("[Confluence] Query: '$query', CQL: $cql")

    try {
      val response =
          getClient().use {
            it.request(url) {
              method = HttpMethod.Get
              header("Authorization", authHeader)
              header("Accept", "application/json")
            }
          }

      log.debug("[Confluence] Response status: ${response.status}")

      when (response.status) {
        HttpStatusCode.OK -> {
          val responseBody = response.bodyAsText()
          log.debug("[Confluence] Response body length: ${responseBody.length} chars")
          log.debug("[Confluence] Full response: $responseBody")

          val parsed = json.decodeFromString<ConfluenceSearchResponse>(responseBody)
          log.debug("[Confluence] Parsed ${parsed.results.size} results")

          if (parsed.results.isEmpty()) {
            log.warn("[Confluence] No results returned for query: '$query'")
          }

          return parsed
        }
        HttpStatusCode.Unauthorized -> {
          log.error("[Confluence] Unauthorized (401) - check API token/credentials")
          throw ConfluenceApiException("Unauthorized - check Confluence credentials")
        }
        HttpStatusCode.Forbidden -> {
          log.error("[Confluence] Forbidden (403) - insufficient permissions")
          throw ConfluenceApiException("Forbidden - insufficient permissions for Confluence search")
        }
        else -> {
          val errorBody = response.bodyAsText()
          log.error("[Confluence] Unexpected status ${response.status}: $errorBody")
          throw ConfluenceApiException("Confluence search failed: ${response.status} - $errorBody")
        }
      }
    } catch (e: IOException) {
      log.error("[Confluence] Connection error: ${e.message}", e)
      throw ConfluenceApiException("Failed to connect to Confluence API", e)
    } catch (e: kotlinx.serialization.SerializationException) {
      log.error("[Confluence] JSON parse error: ${e.message}", e)
      throw ConfluenceApiException("Failed to parse Confluence search response", e)
    }
  }

  override suspend fun getContent(contentId: String): ConfluenceContentResponse {
    val url = "$baseUrl/rest/api/content/$contentId?expand=body.view"

    try {
      val response =
          getClient().use {
            it.request(url) {
              method = HttpMethod.Get
              header("Authorization", authHeader)
              header("Accept", "application/json")
            }
          }

      when (response.status) {
        HttpStatusCode.OK -> {
          val responseBody = response.bodyAsText()
          return json.decodeFromString<ConfluenceContentResponse>(responseBody)
        }
        HttpStatusCode.NotFound -> {
          throw ConfluenceApiException("Content not found: $contentId")
        }
        HttpStatusCode.Unauthorized -> {
          throw ConfluenceApiException("Unauthorized - check Confluence credentials")
        }
        else -> {
          throw ConfluenceApiException(
              "Failed to get Confluence content: ${response.status} - ${response.bodyAsText()}")
        }
      }
    } catch (e: IOException) {
      throw ConfluenceApiException("Failed to connect to Confluence API", e)
    } catch (e: kotlinx.serialization.SerializationException) {
      throw ConfluenceApiException("Failed to parse Confluence content response", e)
    }
  }

  private fun escapeQuery(query: String): String {
    // Escape special CQL characters
    return query.replace("\"", "\\\"").replace("'", "\\'")
  }
}

/** Exception thrown when Confluence API calls fail. */
class ConfluenceApiException : IOException {
  constructor(message: String) : super(message)

  constructor(message: String, cause: Throwable) : super(message, cause)
}
