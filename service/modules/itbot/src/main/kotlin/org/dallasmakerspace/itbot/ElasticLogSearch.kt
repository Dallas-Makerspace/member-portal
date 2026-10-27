package org.dallasmakerspace.itbot

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.query_dsl.Operator
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.json.JsonData
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dallasmakerspace.core.logging.ElasticsearchClientManager

/**
 * Searches the log indices with the app's existing Elasticsearch connection. The connection's key
 * is not limited to reading, so this class only ever builds a `_search` over the two fixed indices.
 */
@Singleton
class ElasticLogSearch @Inject constructor() : LogSearch {

  override suspend fun search(query: LogQuery): LogResult =
      withContext(Dispatchers.IO) {
        val filters = mutableListOf<Query>()
        filters +=
            Query.Builder()
                .range { r ->
                  r.field("@timestamp").gte(JsonData.of(query.from)).lte(JsonData.of(query.to))
                }
                .build()
        query.app?.let { filters += term("app.keyword", it) }
        query.sessionId?.let { filters += term("sessionid.keyword", it) }
        query.minSeverity?.let { min ->
          val levels = SEVERITIES.dropWhile { it != min }
          if (levels.isNotEmpty()) {
            filters +=
                Query.Builder()
                    .terms { t ->
                      t.field("severity.keyword").terms { v -> v.value(levels.map { FieldValue.of(it) }) }
                    }
                    .build()
          }
        }
        val text = query.text
        val textField = if (query.source == LogSource.APP) "message" else "uri"
        val must =
            if (text.isNullOrBlank()) emptyList()
            else listOf(
                Query.Builder().match { m -> m.field(textField).query(text).operator(Operator.And) }.build())
        val request =
            SearchRequest.Builder()
                .index(if (query.source == LogSource.APP) APP_INDEX else REQUEST_INDEX)
                .query(Query.Builder().bool { b -> b.filter(filters).must(must) }.build())
                .sort { s -> s.field { f -> f.field("@timestamp").order(SortOrder.Desc) } }
                .size(query.size)
                .trackTotalHits { t -> t.enabled(true) }
                .build()
        val response = ElasticsearchClientManager.client.search(request, Map::class.java)
        val lines =
            response.hits().hits().mapNotNull { hit ->
              @Suppress("UNCHECKED_CAST") val src = hit.source() as? Map<String, Any?> ?: return@mapNotNull null
              val timestamp = src["@timestamp"] as? String ?: return@mapNotNull null
              val message =
                  if (query.source == LogSource.APP) src["message"] as? String ?: ""
                  else "${src["method"] ?: ""} ${src["uri"] ?: ""}".trim()
              LogLine(
                  timestamp,
                  src["severity"] as? String,
                  src["app"] as? String,
                  src["logger"] as? String,
                  src["sessionid"] as? String,
                  src["userid"] as? String,
                  message)
            }
        LogResult(response.hits().total()?.value() ?: lines.size.toLong(), lines)
      }

  private fun term(field: String, value: String): Query =
      Query.Builder().term { t -> t.field(field).value(value) }.build()

  companion object {
    const val APP_INDEX = "logs"
    const val REQUEST_INDEX = "member-portal-logs"
    val SEVERITIES = listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR")
  }
}

/** Canned log lines for local development without Elasticsearch. */
class MockLogSearch : LogSearch {
  override suspend fun search(query: LogQuery): LogResult =
      LogResult(
          1,
          listOf(
              LogLine(
                  "2026-01-01T12:00:00.000Z",
                  "ERROR",
                  "member-profile-service",
                  "ExampleService",
                  "session-1",
                  "jdoe",
                  "Example failure while handling a request")))
}
