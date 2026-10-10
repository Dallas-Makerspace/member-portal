package org.dallasmakerspace.adreplication

import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.IndexRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.json.JsonData
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.core.logging.ElasticsearchClientManager
import org.dallasmakerspace.winrm.WinRmReplicationPartnerStatus

private const val ES_INDEX = "member-portal-ad-replication-health"
private const val ES_MAX_HITS = 10_000

/** One AD replication sample as stored in Elasticsearch - see [AdReplicationHealthEsRepository]. */
private data class AdReplicationHealthDoc(
    @get:JsonProperty("@timestamp") val timestamp: String,
    val server: String,
    val partner: String,
    val partition: String,
    val pairKey: String,
    val lastReplicationSuccess: String?,
    val lastReplicationAttempt: String?,
    val consecutiveReplicationFailures: Int,
    val lastReplicationResult: Int,
    val latencyMinutes: Long?,
)

/** A sample as read back from Elasticsearch - see [AdReplicationHealthEsRepository]. */
data class StoredReplicationSample(
    val timestamp: Instant,
    val server: String,
    val partner: String,
    val partition: String,
    val pairKey: String,
    val lastReplicationSuccess: Instant?,
    val consecutiveReplicationFailures: Int,
    val lastReplicationResult: Int,
    val latencyMinutes: Long?,
)

/**
 * Reads and writes AD replication health samples to Elasticsearch (index [ES_INDEX], dynamic
 * mapping - one doc per (source DC, partner DC, partition) tuple per cron run). Deliberately no
 * DB table: this is periodic monitoring telemetry, not member-portal domain data, and it already
 * has a natural home in the same ES cluster the rest of the app's centralized logging uses (see
 * [ElasticsearchClientManager] and `InterlockEventsService` for the read-side precedent this
 * follows).
 */
@Singleton
class AdReplicationHealthEsRepository @Inject constructor(loggerFactory: LoggerFactory) {
  private val log = loggerFactory.create(javaClass)

  // One index() call per doc, not a bulk request - sample counts here are tiny (a handful of
  // rows per cron run), so bulk's throughput win isn't worth the deeply-nested generic builder
  // (BulkRequest -> BulkOperation -> IndexOperation<TDocument>) that Kotlin's type inference
  // struggles with three levels deep.
  @Suppress("TooGenericExceptionCaught")
  suspend fun writeSamples(sampledAt: Instant, samples: List<WinRmReplicationPartnerStatus>): Int =
      withContext(Dispatchers.IO) {
        val client = ElasticsearchClientManager.client
        samples.count { s ->
          val doc =
              AdReplicationHealthDoc(
                  timestamp = sampledAt.toString(),
                  server = s.server,
                  partner = s.partner,
                  partition = s.partition,
                  // Lowercased: WinRM/AD may report a DC's own configured hostname casing
                // inconsistently across calls (see AdReplicationHealthReport's reachability
                // check for the same issue) - without this, the trend charts could silently
                // split one physical DC pair into two separate lines/columns.
                pairKey = "${s.server.lowercase()}->${s.partner.lowercase()}",
                  lastReplicationSuccess = s.lastReplicationSuccess?.toString(),
                  lastReplicationAttempt = s.lastReplicationAttempt?.toString(),
                  consecutiveReplicationFailures = s.consecutiveReplicationFailures,
                  lastReplicationResult = s.lastReplicationResult,
                  latencyMinutes =
                      s.lastReplicationSuccess?.let { Duration.between(it, sampledAt).toMinutes() },
              )
          try {
            val request: IndexRequest<AdReplicationHealthDoc> =
                IndexRequest.of { it.index(ES_INDEX).document(doc) }
            client.index(request)
            true
          } catch (e: Exception) {
            log.warn("Index of one AD replication health doc failed: {}", e.message)
            false
          }
        }
      }

  /** Raw samples from the last [lookbackHours], newest first. */
  @Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")
  suspend fun fetchRecentSamples(lookbackHours: Long): List<StoredReplicationSample> =
      withContext(Dispatchers.IO) {
        val client = ElasticsearchClientManager.client
        val rangeQuery =
            Query.Builder()
                .range { r -> r.field("@timestamp").gte(JsonData.of("now-${lookbackHours}h")) }
                .build()
        val request =
            SearchRequest.Builder()
                .index(ES_INDEX)
                .query(rangeQuery)
                .sort { s -> s.field { f -> f.field("@timestamp").order(SortOrder.Desc) } }
                .size(ES_MAX_HITS)
                .build()
        try {
          val response = client.search(request, Map::class.java)
          response.hits().hits().mapNotNull { hit ->
            (hit.source() as? Map<String, Any?>)?.let { parseDoc(it) }
          }
        } catch (e: Exception) {
          log.warn("AD replication health search failed: {}", e.message)
          emptyList()
        }
      }

  // internal, not private, so tests can exercise the parsing logic directly with a canned ES
  // source map - same rationale as WinRmClient's internal parse functions.
  internal fun parseDoc(src: Map<String, Any?>): StoredReplicationSample? {
    val timestamp = (src["@timestamp"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val server = src["server"] as? String
    val partner = src["partner"] as? String
    if (timestamp == null || server == null || partner == null) return null
    return StoredReplicationSample(
        timestamp = timestamp,
        server = server,
        partner = partner,
        partition = src["partition"] as? String ?: "",
        pairKey = src["pairKey"] as? String ?: "$server->$partner",
        lastReplicationSuccess =
            (src["lastReplicationSuccess"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() },
        consecutiveReplicationFailures = (src["consecutiveReplicationFailures"] as? Number)?.toInt() ?: 0,
        lastReplicationResult = (src["lastReplicationResult"] as? Number)?.toInt() ?: 0,
        latencyMinutes = (src["latencyMinutes"] as? Number)?.toLong(),
    )
  }
}
