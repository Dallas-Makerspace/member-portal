package org.dallasmakerspace.dataviz.reports.interlock

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.json.JsonData
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.logging.ElasticsearchClientManager

private const val RESCAN_DEBOUNCE_SECONDS = 30L
private const val ES_INDEX = "not-john-index"
private const val ES_MAX_HITS = 10_000
private const val ES_LAST_EVENT_LOOKBACK_DAYS = 365
private const val AVATAR_SIZE = "144"
private const val MIN_BADGE_LEN_FOR_PARTIAL_MASK = 4

data class EsEvent(val host: String, val timestamp: String, val message: String)

data class SessionInfo(
    val host: String,
    val startTime: String,
    val badge: String,
    val authorized: Boolean,
    var shutdownType: String?,
    var shutdownTime: String?,
)

@Singleton
class InterlockEventsService
@Inject
constructor(
    private val activeDirectoryService: IActiveDirectoryService,
    private val appConfig: AppConfig,
) {

  // ---------------------------------------------------------------------------
  // Kibana Discover URL builder
  // ---------------------------------------------------------------------------

  /** Builds a KQL host filter for the given interlock tags. */
  fun kibanaQueryForTags(tags: List<String>): String =
      when {
        tags.isEmpty() -> ""
        tags.size == 1 -> "host.keyword : \"${tags.first()}\""
        else -> "host.keyword : (" + tags.joinToString(" or ") { "\"$it\"" } + ")"
      }

  /** Host filter + authorized user-swipe events (matches `'authorized': True` in the message). */
  fun kibanaQueryAuthorizedSwipes(tags: List<String>): String =
      kibanaQueryForTags(tags) + " and message : \"authorized\" and message : \"True\""

  /** Host filter + denied user-swipe events (matches `'authorized': False` in the message). */
  fun kibanaQueryDeniedSwipes(tags: List<String>): String =
      kibanaQueryForTags(tags) + " and message : \"authorized\" and message : \"False\""

  /**
   * Builds a Kibana Discover URL for the given KQL query and time window. Returns null if
   * `app.kibana.base-url` is not configured.
   */
  fun buildKibanaDiscoverUrl(kql: String, days: Int): String? {
    val baseUrl = appConfig.getStringProperty("app.kibana.base-url", "").trimEnd('/')
    if (baseUrl.isBlank()) return null
    val space = appConfig.getStringProperty("app.kibana.space", "").ifBlank { "default" }
    val dataView = appConfig.getStringProperty("app.kibana.data-view", "").ifBlank { "logs-*" }
    // Selective encoding: leave RISON syntax chars intact; escape only space and double-quote.
    val encodedKql = kql.replace(" ", "%20").replace("\"", "%22")
    return "$baseUrl/s/$space/app/discover#/" +
        "?_g=(time:(from:now-${days}d,to:now))" +
        "&_a=(index:'$dataView',query:(language:kuery,query:'$encodedKql'))"
  }

  // ---------------------------------------------------------------------------
  // ES fetch — multi-host
  // ---------------------------------------------------------------------------

  @Suppress("TooGenericExceptionCaught")
  suspend fun fetchEvents(interlockTags: List<String>, days: Int): List<EsEvent> {
    if (interlockTags.isEmpty()) return emptyList()
    return withContext(Dispatchers.IO) {
      val client = ElasticsearchClientManager.client

      val termsQuery =
          Query.Builder()
              .terms { t ->
                t.field("host.keyword").terms { tv ->
                  tv.value(interlockTags.map { FieldValue.of(it) })
                }
              }
              .build()

      val rangeQuery =
          Query.Builder()
              .range { r -> r.field("@timestamp").gte(JsonData.of("now-${days}d")) }
              .build()

      val boolQuery = Query.Builder().bool { b -> b.must(listOf(termsQuery, rangeQuery)) }.build()

      val request =
          SearchRequest.Builder()
              .index(ES_INDEX)
              .query(boolQuery)
              .sort { s -> s.field { f -> f.field("@timestamp").order(SortOrder.Asc) } }
              .size(ES_MAX_HITS)
              .source { src -> src.filter { f -> f.includes("@timestamp", "message", "host") } }
              .build()

      try {
        val response = client.search(request, Map::class.java)
        response.hits().hits().mapNotNull { hit ->
          val source = hit.source() ?: return@mapNotNull null
          @Suppress("UNCHECKED_CAST") val src = source as Map<String, Any?>
          val timestamp = src["@timestamp"] as? String ?: return@mapNotNull null
          val message = src["message"] as? String ?: return@mapNotNull null
          val host = src["host"] as? String ?: ""
          EsEvent(host = host, timestamp = timestamp, message = message)
        }
      } catch (e: Exception) {
        ElasticsearchClientManager.log.warn(
            "InterlockEventsService: ES query failed: ${e.message}")
        emptyList()
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Session reconstruction — grouped by host
  // ---------------------------------------------------------------------------

  fun reconstructSessions(events: List<EsEvent>): List<SessionInfo> =
      events.groupBy { it.host }.flatMap { (host, hostEvents) ->
        reconstructForHost(host, hostEvents)
      }

  @Suppress("TooGenericExceptionCaught", "NestedBlockDepth", "ComplexMethod", "LongMethod")
  private fun reconstructForHost(host: String, events: List<EsEvent>): List<SessionInfo> {
    val sessions = mutableListOf<SessionInfo>()
    var current: SessionInfo? = null

    for (doc in events) {
      // Messages are prefixed "YYYY-MM-DD HH:MM:SS <content>" — strip both date and time tokens.
      val msg = doc.message.substringAfter(" ").substringAfter(" ").ifBlank { continue }

      when {
        msg.startsWith("User:") -> {
          current?.let {
            // Only emit the interrupted session if it had meaningful duration.
            // Short gaps (< 30s) are re-scan noise from the same user trying again.
            val elapsedSeconds =
                try {
                  Instant.parse(doc.timestamp).epochSecond -
                      Instant.parse(it.startTime).epochSecond
                } catch (e: Exception) {
                  Long.MAX_VALUE
                }
            if (elapsedSeconds >= RESCAN_DEBOUNCE_SECONDS) sessions.add(it)
          }
          val authorized = msg.contains("'authorized': True")
          val badge = Regex("'id': '(\\d+)'").find(msg)?.groupValues?.get(1) ?: continue
          current = SessionInfo(host, doc.timestamp, badge, authorized, null, null)
          if (!authorized) {
            sessions.add(current!!)
            current = null
          }
        }
        msg.contains("Idle timeout") -> {
          current?.let {
            it.shutdownType = "IDLE_TIMEOUT"
            it.shutdownTime = doc.timestamp
            sessions.add(it)
            current = null
          }
        }
        msg.contains("Startup Current Detected") -> {
          current?.let {
            it.shutdownType = "STARTUP_CURRENT"
            it.shutdownTime = doc.timestamp
            sessions.add(it)
            current = null
          }
        }
        msg.contains("Badged out while awaiting timeout") -> {
          current?.let {
            it.shutdownType = "MANUAL_BADGE_OUT"
            it.shutdownTime = doc.timestamp
            sessions.add(it)
            current = null
          }
        }
      }
    }
    current?.let { sessions.add(it) } // emit still-running session

    return sessions
  }

  // ---------------------------------------------------------------------------
  // Last event timestamp per tool — terms aggregation + max(@timestamp)
  // ---------------------------------------------------------------------------

  @Suppress("TooGenericExceptionCaught", "MagicNumber")
  suspend fun fetchLastEventPerTool(interlockTags: List<String>): Map<String, Instant?> {
    if (interlockTags.isEmpty()) return emptyMap()
    val emptyResult = interlockTags.associateWith { null as Instant? }
    return withContext(Dispatchers.IO) {
      val client = ElasticsearchClientManager.client

      val termsQuery =
          Query.Builder()
              .terms { t ->
                t.field("host.keyword").terms { tv ->
                  tv.value(interlockTags.map { FieldValue.of(it) })
                }
              }
              .build()

      val rangeQuery =
          Query.Builder()
              .range { r ->
                r.field("@timestamp").gte(JsonData.of("now-${ES_LAST_EVENT_LOOKBACK_DAYS}d"))
              }
              .build()

      val boolQuery = Query.Builder().bool { b -> b.must(listOf(termsQuery, rangeQuery)) }.build()

      val request =
          SearchRequest.Builder()
              .index(ES_INDEX)
              .size(0)
              .query(boolQuery)
              .aggregations("by_host") { aggBuilder ->
                aggBuilder
                    .terms { t -> t.field("host.keyword").size(interlockTags.size) }
                    .aggregations("last_seen") { sub ->
                      sub.max { m -> m.field("@timestamp") }
                    }
              }
              .build()

      try {
        val response = client.search(request, Map::class.java)
        val byHostAgg = response.aggregations()["by_host"]?.sterms() ?: return@withContext emptyResult
        val result = emptyResult.toMutableMap()
        for (bucket in byHostAgg.buckets().array()) {
          val key = bucket.key().stringValue()
          val maxAgg = bucket.aggregations()["last_seen"]?.max()
          val millis = maxAgg?.value()
          if (millis != null && !millis.isNaN()) {
            result[key] = Instant.ofEpochMilli(millis.toLong())
          }
        }
        result
      } catch (e: Exception) {
        ElasticsearchClientManager.log.warn(
            "InterlockEventsService: last-event aggregation failed: ${e.message}")
        emptyMap()
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Badge → username lookup via Active Directory
  // ---------------------------------------------------------------------------

  @Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")
  suspend fun fetchBadgeToUsername(badgeNumbers: Set<String>): Map<String, String> {
    if (badgeNumbers.isEmpty()) return emptyMap()
    return withContext(Dispatchers.IO) {
      try {
        val adResult =
            activeDirectoryService.getMembersByBadgeNumberList(badgeNumbers.toList())
                as? Map<String, ADUser?> ?: return@withContext emptyMap()
        adResult
            .mapNotNull { (badge, adUser) ->
              val username =
                  adUser?.sAMAccountName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
              badge to username
            }
            .toMap()
      } catch (e: Exception) {
        ElasticsearchClientManager.log.warn(
            "InterlockEventsService: AD badge lookup failed: ${e.message}")
        emptyMap()
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Duration formatting
  // ---------------------------------------------------------------------------

  @Suppress("TooGenericExceptionCaught", "MagicNumber")
  fun formatDuration(start: String, end: String?): String {
    end ?: return "—"
    return try {
      val s = Instant.parse(start)
      val e = Instant.parse(end)
      val totalSeconds = e.epochSecond - s.epochSecond
      if (totalSeconds < 0) return "—"
      val minutes = totalSeconds / 60
      val seconds = totalSeconds % 60
      "${minutes}m ${seconds}s"
    } catch (ex: Exception) {
      "—"
    }
  }

  // ---------------------------------------------------------------------------
  // Badge masking
  // ---------------------------------------------------------------------------

  fun maskBadge(badge: String): String {
    if (badge.length <= MIN_BADGE_LEN_FOR_PARTIAL_MASK) return "*".repeat(badge.length)
    return badge.take(2) + "*".repeat(badge.length - 4) + badge.takeLast(2)
  }

  // ---------------------------------------------------------------------------
  // Avatar URL helper
  // ---------------------------------------------------------------------------

  fun getAvatarUrl(discourseAvatarUrl: String?): String {
    return discourseAvatarUrl
        ?.takeIf { it.isNotEmpty() }
        ?.let { url ->
          when {
            url.startsWith("//") -> "https:$url"
            else -> "https://talk.dallasmakerspace.org$url"
          }.replace("{size}", AVATAR_SIZE)
        } ?: ""
  }
}
