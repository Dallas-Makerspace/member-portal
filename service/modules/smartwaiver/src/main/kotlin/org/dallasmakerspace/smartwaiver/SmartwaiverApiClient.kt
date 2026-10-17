package org.dallasmakerspace.smartwaiver

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong
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
private const val REQUEST_TIMEOUT_MS = 120000L // 120 seconds
private const val MAX_PAGES = 20 // Upper bound: fetch max 2000 waivers (20 pages * 100)
private const val CHECKINS_PAGE_SIZE = 100 // the API's maximum
private const val MAX_RATE_LIMIT_ATTEMPTS = 5
private const val MS_PER_SECOND = 1000L

private val WAIVER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

/** Waiver ids come from webhook bodies and end up in request paths, so reject anything odd. */
private fun requireValidWaiverId(waiverId: String) {
  if (!WAIVER_ID_PATTERN.matches(waiverId)) throw SmartwaiverApiException("Invalid waiver id")
}

private fun createHttpClient() =
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

@Singleton
class SmartwaiverApiClient
internal constructor(
    private val appConfig: AppConfig,
    loggerFactory: LoggerFactory,
    private val client: HttpClient,
    private val throttle: RequestThrottle,
) : ISmartwaiverApiClient {
  @Inject
  constructor(
      appConfig: AppConfig,
      loggerFactory: LoggerFactory,
  ) : this(appConfig, loggerFactory, createHttpClient(), RequestThrottle.fromConfig(appConfig))

  private val log = loggerFactory.create(javaClass)

  private val requestCount = AtomicLong()
  private val rateLimitCount = AtomicLong()

  override fun stats() = ApiClientStats(requestCount.get(), rateLimitCount.get())

  override suspend fun countWaivers(fromDate: LocalDate, toDate: LocalDate): Int =
      try {
        initiateSearch(fromDate, toDate).count ?: 0
      } catch (e: IOException) {
        throw SmartwaiverApiException("Failed to count waivers", e)
      } catch (e: kotlinx.serialization.SerializationException) {
        throw SmartwaiverApiException("Failed to parse waiver count response", e)
      }

  private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
  }

  // Smartwaiver API returns dates in format: "2025-09-19 00:04:02"
  private val smartwaiverDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

  /**
   * GETs [url] through the shared throttle. On 429 every caller pauses for `retryAfter`, then the
   * request is retried; other statuses are returned for the caller to interpret.
   */
  private suspend fun send(
      category: RequestCategory,
      url: String,
      configure: HttpRequestBuilder.() -> Unit = {},
  ): HttpResponse {
    val apiKey = appConfig.requireStringProperty("app.smartwaiver.apiKey")
    repeat(MAX_RATE_LIMIT_ATTEMPTS) { attempt ->
      throttle.acquire(category)
      requestCount.incrementAndGet()
      val response =
          client.request(url) {
            method = HttpMethod.Get
            header("sw-api-key", apiKey)
            configure()
          }
      if (response.status != HttpStatusCode.TooManyRequests) return response
      rateLimitCount.incrementAndGet()
      val seconds =
          parseRetryAfterSeconds(response.headers["Retry-After"], response.bodyAsText())
      log.warn(
          "Smartwaiver rate limit hit (attempt ${attempt + 1}/$MAX_RATE_LIMIT_ATTEMPTS), " +
              "pausing ${seconds}s")
      throttle.pauseFor(seconds * MS_PER_SECOND)
    }
    throw SmartwaiverApiException("Rate limit exceeded")
  }

  override suspend fun getWaivers(fromDate: LocalDate, toDate: LocalDate): List<WaiverSigningData> {
    val allWaivers = mutableListOf<WaiverSigningData>()
    val overallStartTime = System.currentTimeMillis()

    try {
      // Step 1: Initiate search to get GUID and metadata
      val searchMetadata = initiateSearch(fromDate, toDate)
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

      // Fetch all pages in parallel; the throttle spaces the requests out
      log.info("Fetching $pagesToFetch pages in parallel...")
      val pageResults = coroutineScope {
        (0 until pagesToFetch)
            .map { pageNum ->
              async {
                log.info("Starting fetch for page ${pageNum + 1} of $pagesToFetch")
                fetchSearchResults(searchMetadata.guid, pageNum)
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
      fromDate: LocalDate,
      toDate: LocalDate,
  ): SmartwaiverSearchMetadata {
    val startTime = System.currentTimeMillis()

    val response =
        send(RequestCategory.SEARCH, "$SMARTWAIVER_BASE_URL/search") {
          parameter("fromDts", fromDate.atStartOfDay().format(DateTimeFormatter.ISO_DATE_TIME))
          parameter(
              "toDts",
              toDate
                  .atTime(END_OF_DAY_HOUR, END_OF_DAY_MINUTE, END_OF_DAY_SECOND)
                  .format(DateTimeFormatter.ISO_DATE_TIME),
          )
          parameter("sort", "desc") // Get newest waivers first
        }

    val timeTaken = System.currentTimeMillis() - startTime

    return when (response.status) {
      HttpStatusCode.OK -> {
        // The body lists signers' names and emails, so only the metadata is logged.
        val searchResponse = json.decodeFromString<SmartwaiverSearchResponse>(response.bodyAsText())
        val searchMetadata =
            searchResponse.search
                ?: throw SmartwaiverApiException("Search response missing search metadata")

        log.info(
            "Search initiated - GUID: ${searchMetadata.guid}, Count: ${searchMetadata.count}, Pages: ${searchMetadata.pages}, Time: ${timeTaken}ms")

        searchMetadata
      }
      HttpStatusCode.Unauthorized -> throw SmartwaiverApiException("Unauthorized: Invalid API key")
      else ->
          throw SmartwaiverApiException(
              "Failed to initiate search: ${response.status} - ${response.bodyAsText()}")
    }
  }

  private suspend fun fetchSearchResults(
      guid: String,
      page: Int,
  ): SmartwaiverSearchResultsResponse {
    val startTime = System.currentTimeMillis()

    val response =
        send(RequestCategory.SEARCH, "$SMARTWAIVER_BASE_URL/search/$guid/results") {
          parameter("page", page)
        }

    val timeTaken = System.currentTimeMillis() - startTime

    return when (response.status) {
      HttpStatusCode.OK -> {
        val responseBody = response.bodyAsText()
        log.info("Fetched page $page - Time: ${timeTaken}ms, Size: ${responseBody.length} bytes")

        json.decodeFromString<SmartwaiverSearchResultsResponse>(responseBody)
      }
      HttpStatusCode.Unauthorized -> throw SmartwaiverApiException("Unauthorized: Invalid API key")
      else ->
          throw SmartwaiverApiException(
              "Failed to fetch search results: ${response.status} - ${response.bodyAsText()}")
    }
  }

  override suspend fun getWaiverDetails(
      fromDate: LocalDate,
      toDate: LocalDate
  ): List<SmartwaiverSummary> {
    try {
      return searchSummaries(fromDate, toDate)
    } catch (e: IOException) {
      throw SmartwaiverApiException("Failed to fetch waiver details", e)
    } catch (e: kotlinx.serialization.SerializationException) {
      throw SmartwaiverApiException("Failed to parse waiver details response", e)
    }
  }

  /** Halves a window that has more pages than [MAX_PAGES] instead of silently dropping the rest. */
  private suspend fun searchSummaries(from: LocalDate, to: LocalDate): List<SmartwaiverSummary> {
    val searchMetadata = initiateSearch(from, to)
    val totalPages = searchMetadata.pages ?: 0
    if (totalPages > MAX_PAGES && from.isBefore(to)) {
      val mid = from.plusDays(ChronoUnit.DAYS.between(from, to) / 2)
      return searchSummaries(from, mid) + searchSummaries(mid.plusDays(1), to)
    }
    if (totalPages > MAX_PAGES) {
      log.warn("Single day $from has $totalPages pages; only the first $MAX_PAGES are fetched")
    }
    val pageResults = coroutineScope {
      (0 until minOf(totalPages, MAX_PAGES))
          .map { async { fetchSearchResults(searchMetadata.guid, it) } }
          .awaitAll()
    }
    return pageResults.flatMap { it.searchResults ?: emptyList() }
  }

  override suspend fun getCheckins(from: LocalDateTime, to: LocalDateTime): SmartwaiverCheckinPage {
    val response =
        send(RequestCategory.OTHER, "$SMARTWAIVER_BASE_URL/checkins") {
          parameter("fromDts", from.format(DateTimeFormatter.ISO_DATE_TIME))
          parameter("toDts", to.format(DateTimeFormatter.ISO_DATE_TIME))
          parameter("limit", CHECKINS_PAGE_SIZE)
        }
    return try {
      when (response.status) {
        HttpStatusCode.OK ->
            json.decodeFromString<SmartwaiverCheckinsResponse>(response.bodyAsText()).checkins
                ?: SmartwaiverCheckinPage()
        HttpStatusCode.Unauthorized ->
            throw SmartwaiverApiException("Unauthorized: Invalid API key")
        else ->
            throw SmartwaiverApiException(
                "Failed to fetch check-ins: ${response.status} - ${response.bodyAsText()}")
      }
    } catch (e: kotlinx.serialization.SerializationException) {
      throw SmartwaiverApiException("Failed to parse check-ins response", e)
    }
  }

  override suspend fun getWaiver(waiverId: String): SmartwaiverFullWaiver {
    requireValidWaiverId(waiverId)
    val response = send(RequestCategory.OTHER, "$SMARTWAIVER_BASE_URL/waivers/$waiverId")

    return when (response.status) {
      HttpStatusCode.OK -> {
        val waiverResponse =
            json.decodeFromString<SmartwaiverFullWaiverResponse>(response.bodyAsText())
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
    try {
      val searchMetadata = initiateSearchByName(firstName, lastName)
      if ((searchMetadata.count ?: 0) == 0) return emptyList()
      val firstPage = fetchSearchResults(searchMetadata.guid, 0)
      return firstPage.searchResults ?: emptyList()
    } catch (e: IOException) {
      throw SmartwaiverApiException("Failed to fetch waivers by name", e)
    } catch (e: kotlinx.serialization.SerializationException) {
      throw SmartwaiverApiException("Failed to parse waiver response", e)
    }
  }

  private suspend fun initiateSearchByName(
      firstName: String,
      lastName: String,
  ): SmartwaiverSearchMetadata {
    val response =
        send(RequestCategory.SEARCH, "$SMARTWAIVER_BASE_URL/search") {
          parameter("firstName", firstName)
          parameter("lastName", lastName)
          parameter("sort", "desc") // Get newest waivers first
        }

    return when (response.status) {
      HttpStatusCode.OK -> {
        // Not logging the response body here — it's a search scoped to one identifiable person.
        val searchResponse = json.decodeFromString<SmartwaiverSearchResponse>(response.bodyAsText())
        searchResponse.search
            ?: throw SmartwaiverApiException("Name search response missing search metadata")
      }
      HttpStatusCode.Unauthorized -> throw SmartwaiverApiException("Unauthorized: Invalid API key")
      else ->
          throw SmartwaiverApiException(
              "Failed to initiate name search: ${response.status} - ${response.bodyAsText()}")
    }
  }

  override suspend fun getWaiverPhotos(waiverId: String): List<SmartwaiverPhotoEntry> {
    requireValidWaiverId(waiverId)
    val response = send(RequestCategory.OTHER, "$SMARTWAIVER_BASE_URL/waivers/$waiverId/photos")

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
