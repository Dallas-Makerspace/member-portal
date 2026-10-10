package org.dallasmakerspace.smartwaiver

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okio.IOException
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.DmsHttpEngine
import org.dallasmakerspace.core.LoggerFactory

private const val SMARTWAIVER_BASE_URL = "https://api.smartwaiver.com/v4"
private const val END_OF_DAY_HOUR = 23
private const val END_OF_DAY_MINUTE = 59
private const val END_OF_DAY_SECOND = 59
private const val PAGE_LIMIT = 300
private const val REQUEST_TIMEOUT_MS = 120000L // 120 seconds
private const val MAX_PAGES = 20 // Upper bound: fetch max 2000 waivers (20 pages * 100)

@Singleton
class SmartwaiverApiClient
@Inject
constructor(private val appConfig: AppConfig, loggerFactory: LoggerFactory) :
    ISmartwaiverApiClient {
  private val log = loggerFactory.create(javaClass)

  private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
  }

  // Smartwaiver API returns dates in format: "2025-09-19 00:04:02"
  private val smartwaiverDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

  private fun getClient() =
      HttpClient(DmsHttpEngine) {
        engine {
          config {
            // All calls hit the same host - maxRequestsPerHost must rise too, or OkHttp's default
            // of 5 throttles the parallel page-fetch below regardless of maxRequests.
            dispatcher(
                Dispatcher().apply {
                  maxRequests = 100
                  maxRequestsPerHost = 100
                })
          }
        }
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

  override suspend fun getWaivers(fromDate: LocalDate, toDate: LocalDate): List<WaiverSigningData> {
    val apiKey = appConfig.requireStringProperty("app.smartwaiver.apiKey")
    val allWaivers = mutableListOf<WaiverSigningData>()
    val overallStartTime = System.currentTimeMillis()

    try {
      // Step 1: Initiate search to get GUID and metadata
      val searchMetadata = initiateSearch(apiKey, fromDate, toDate)
      val totalPages = searchMetadata.pages ?: 0
      val totalCount = searchMetadata.count ?: 0

      log.info(
          "Smartwaiver Search: GUID=${searchMetadata.guid}, Total=$totalCount waivers, Pages=$totalPages")

      // Apply guardrail
      val pagesToFetch = minOf(totalPages, MAX_PAGES)
      if (totalPages > MAX_PAGES) {
        log.warn(
            "Limiting fetch to $MAX_PAGES pages (${MAX_PAGES * 100} waivers) out of $totalPages total pages")
      }

      // Fetch all pages in parallel for maximum speed
      log.info("Fetching $pagesToFetch pages in parallel...")
      val pageResults = coroutineScope {
        (0 until pagesToFetch)
            .map { pageNum ->
              async {
                log.info("Starting fetch for page ${pageNum + 1} of $pagesToFetch")
                fetchSearchResults(apiKey, searchMetadata.guid, pageNum)
              }
            }
            .awaitAll()
      }

      // Combine all results
      pageResults.forEach { pageResponse ->
        pageResponse.searchResults?.let { waivers -> allWaivers.addAll(parseWaivers(waivers)) }
      }

      val overallTimeTaken = System.currentTimeMillis() - overallStartTime
      log.info(
          "Successfully fetched ${allWaivers.size} waivers from $fromDate to $toDate - Total time: ${overallTimeTaken}ms (parallel fetch)")
      return allWaivers
    } catch (e: IOException) {
      throw SmartwaiverApiException("Failed to fetch waivers", e)
    } catch (e: kotlinx.serialization.SerializationException) {
      throw SmartwaiverApiException("Failed to parse waiver response", e)
    }
  }

  private suspend fun initiateSearch(
      apiKey: String,
      fromDate: LocalDate,
      toDate: LocalDate,
  ): SmartwaiverSearchMetadata {
    val url = "$SMARTWAIVER_BASE_URL/search"
    val startTime = System.currentTimeMillis()

    val response =
        getClient().use {
          it.request(url) {
            method = HttpMethod.Get
            header("sw-api-key", apiKey)
            parameter("fromDts", fromDate.atStartOfDay().format(DateTimeFormatter.ISO_DATE_TIME))
            parameter(
                "toDts",
                toDate
                    .atTime(END_OF_DAY_HOUR, END_OF_DAY_MINUTE, END_OF_DAY_SECOND)
                    .format(DateTimeFormatter.ISO_DATE_TIME),
            )
            parameter("sort", "desc") // Get newest waivers first
          }
        }

    val timeTaken = System.currentTimeMillis() - startTime

    when (response.status) {
      HttpStatusCode.OK -> {
        val responseBody = response.bodyAsText()
        log.info("Smartwaiver Search Response: $responseBody")
        val searchResponse = json.decodeFromString<SmartwaiverSearchResponse>(responseBody)
        val searchMetadata =
            searchResponse.search
                ?: throw SmartwaiverApiException(
                    "Search response missing search metadata: $responseBody")

        log.info(
            "Search initiated - GUID: ${searchMetadata.guid}, Count: ${searchMetadata.count}, Pages: ${searchMetadata.pages}, Time: ${timeTaken}ms")

        return searchMetadata
      }
      HttpStatusCode.Unauthorized -> {
        throw SmartwaiverApiException("Unauthorized: Invalid API key")
      }
      HttpStatusCode.TooManyRequests -> {
        throw SmartwaiverApiException("Rate limit exceeded")
      }
      else -> {
        throw SmartwaiverApiException(
            "Failed to initiate search: ${response.status} - ${response.bodyAsText()}")
      }
    }
  }

  private suspend fun fetchSearchResults(
      apiKey: String,
      guid: String,
      page: Int,
  ): SmartwaiverSearchResultsResponse {
    val url = "$SMARTWAIVER_BASE_URL/search/$guid/results"
    val startTime = System.currentTimeMillis()

    val response =
        getClient().use {
          it.request(url) {
            method = HttpMethod.Get
            header("sw-api-key", apiKey)
            parameter("page", page)
          }
        }

    val timeTaken = System.currentTimeMillis() - startTime

    when (response.status) {
      HttpStatusCode.OK -> {
        val responseBody = response.bodyAsText()
        log.info("Fetched page $page - Time: ${timeTaken}ms, Size: ${responseBody.length} bytes")

        return json.decodeFromString<SmartwaiverSearchResultsResponse>(responseBody)
      }
      HttpStatusCode.Unauthorized -> {
        throw SmartwaiverApiException("Unauthorized: Invalid API key")
      }
      HttpStatusCode.TooManyRequests -> {
        throw SmartwaiverApiException("Rate limit exceeded")
      }
      else -> {
        throw SmartwaiverApiException(
            "Failed to fetch search results: ${response.status} - ${response.bodyAsText()}")
      }
    }
  }

  override suspend fun getWaiverDetails(
      fromDate: LocalDate,
      toDate: LocalDate
  ): List<SmartwaiverSummary> {
    val apiKey = appConfig.requireStringProperty("app.smartwaiver.apiKey")
    try {
      val searchMetadata = initiateSearch(apiKey, fromDate, toDate)
      val totalPages = searchMetadata.pages ?: 0
      val pagesToFetch = minOf(totalPages, MAX_PAGES)
      val pageResults = coroutineScope {
        (0 until pagesToFetch)
            .map { async { fetchSearchResults(apiKey, searchMetadata.guid, it) } }
            .awaitAll()
      }
      return pageResults.flatMap { it.searchResults ?: emptyList() }
    } catch (e: IOException) {
      throw SmartwaiverApiException("Failed to fetch waiver details", e)
    } catch (e: kotlinx.serialization.SerializationException) {
      throw SmartwaiverApiException("Failed to parse waiver details response", e)
    }
  }

  override suspend fun getWaiver(waiverId: String): SmartwaiverFullWaiver {
    val apiKey = appConfig.requireStringProperty("app.smartwaiver.apiKey")
    val url = "$SMARTWAIVER_BASE_URL/waivers/$waiverId"

    val response =
        getClient().use {
          it.request(url) {
            method = HttpMethod.Get
            header("sw-api-key", apiKey)
          }
        }

    return when (response.status) {
      HttpStatusCode.OK -> {
        val responseBody = response.bodyAsText()
        val waiverResponse = json.decodeFromString<SmartwaiverFullWaiverResponse>(responseBody)
        waiverResponse.waiver
            ?: throw SmartwaiverApiException("Waiver missing in response for id: $waiverId")
      }
      HttpStatusCode.NotFound -> throw SmartwaiverApiException("Waiver not found: $waiverId")
      HttpStatusCode.Unauthorized -> throw SmartwaiverApiException("Unauthorized: Invalid API key")
      else ->
          throw SmartwaiverApiException(
              "Failed to fetch waiver $waiverId: ${response.status} - ${response.bodyAsText()}")
    }
  }

  override suspend fun getWaiversByName(firstName: String, lastName: String): List<SmartwaiverSummary> {
    val apiKey = appConfig.requireStringProperty("app.smartwaiver.apiKey")
    try {
      val searchMetadata = initiateSearchByName(apiKey, firstName, lastName)
      if ((searchMetadata.count ?: 0) == 0) return emptyList()
      val firstPage = fetchSearchResults(apiKey, searchMetadata.guid, 0)
      return firstPage.searchResults ?: emptyList()
    } catch (e: IOException) {
      throw SmartwaiverApiException("Failed to fetch waivers by name", e)
    } catch (e: kotlinx.serialization.SerializationException) {
      throw SmartwaiverApiException("Failed to parse waiver response", e)
    }
  }

  private suspend fun initiateSearchByName(
      apiKey: String,
      firstName: String,
      lastName: String,
  ): SmartwaiverSearchMetadata {
    val url = "$SMARTWAIVER_BASE_URL/search"

    val response =
        getClient().use {
          it.request(url) {
            method = HttpMethod.Get
            header("sw-api-key", apiKey)
            parameter("firstName", firstName)
            parameter("lastName", lastName)
            parameter("sort", "desc") // Get newest waivers first
          }
        }

    return when (response.status) {
      HttpStatusCode.OK -> {
        // Not logging the response body here (unlike initiateSearch) — it's a search scoped to one
        // identifiable person rather than a date-range batch.
        val responseBody = response.bodyAsText()
        val searchResponse = json.decodeFromString<SmartwaiverSearchResponse>(responseBody)
        searchResponse.search
            ?: throw SmartwaiverApiException("Name search response missing search metadata")
      }
      HttpStatusCode.Unauthorized -> {
        throw SmartwaiverApiException("Unauthorized: Invalid API key")
      }
      HttpStatusCode.TooManyRequests -> {
        throw SmartwaiverApiException("Rate limit exceeded")
      }
      else -> {
        throw SmartwaiverApiException(
            "Failed to initiate name search: ${response.status} - ${response.bodyAsText()}")
      }
    }
  }

  override suspend fun getWaiverPhotos(waiverId: String): List<SmartwaiverPhotoEntry> {
    val apiKey = appConfig.requireStringProperty("app.smartwaiver.apiKey")
    val url = "$SMARTWAIVER_BASE_URL/waivers/$waiverId/photos"

    val response =
        getClient().use {
          it.request(url) {
            method = HttpMethod.Get
            header("sw-api-key", apiKey)
          }
        }

    return when (response.status) {
      HttpStatusCode.OK -> {
        val responseBody = response.bodyAsText()
        val photosResponse = json.decodeFromString<SmartwaiverPhotosResponse>(responseBody)
        val photos = photosResponse.photos?.photos ?: emptyList()
        if (photos.isEmpty()) {
          val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }
          log.info(
              "No photos in response for waiver $waiverId. Raw response:\n" +
                  prettyJson.encodeToString(
                      kotlinx.serialization.json.JsonElement.serializer(),
                      prettyJson.parseToJsonElement(responseBody)))
        }
        photos
      }
      // Older/non-kiosk waivers may not have this sub-resource — treat as "no photos".
      HttpStatusCode.NotFound -> emptyList()
      HttpStatusCode.Unauthorized -> throw SmartwaiverApiException("Unauthorized: Invalid API key")
      else ->
          throw SmartwaiverApiException(
              "Failed to fetch photos for waiver $waiverId: ${response.status} - ${response.bodyAsText()}")
    }
  }

  private fun parseWaivers(waivers: List<SmartwaiverSummary>): List<WaiverSigningData> {
    return waivers.map { waiver ->
      val createdOn = LocalDateTime.parse(waiver.createdOn, smartwaiverDateFormatter)
      WaiverSigningData(
          date = createdOn,
          dayOfWeek = createdOn.dayOfWeek.value, // 1=Monday, 7=Sunday
          waiverId = waiver.waiverId,
      )
    }
  }
}
