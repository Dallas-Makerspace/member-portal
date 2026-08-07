package org.dallasmakerspace.server.common.logging

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest_client.RestClientTransport
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import kotlinx.coroutines.future.await
import org.apache.http.HttpHost
import org.apache.http.HttpRequestInterceptor
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.apache.http.impl.nio.client.HttpAsyncClients
import org.apache.http.ssl.SSLContextBuilder
import org.dallasmakerspace.server.di.DaggerAppComponent
import org.elasticsearch.client.RestClient
import org.elasticsearch.client.RestClientBuilder

object ElasticsearchClientManager {
  const val RETRY_INTERVAL_MS = 30_000L
  private const val ERROR_THRESHOLD = 10

  private val retryQueue = ConcurrentLinkedQueue<Map<String, Any?>>()

  fun enqueueForRetry(entry: Map<String, Any?>) {
    retryQueue.offer(entry)
  }

  @Suppress("TooGenericExceptionCaught")
  suspend fun processRetryQueue() {
    if (retryQueue.isEmpty()) return

    val toRetry = mutableListOf<Map<String, Any?>>()
    while (true) {
      toRetry.add(retryQueue.poll() ?: break)
    }

    var failed = 0
    for (entry in toRetry) {
      try {
        client.index { i -> i.index("member-portal-logs").document(entry) }.await()
      } catch (e: IOException) {
        failed++
        retryQueue.offer(entry)
      }
    }

    if (failed == 0) {
      log.info(
          "Elasticsearch retry flushed ${toRetry.size} queued entr${if (toRetry.size == 1) "y" else "ies"}")
    } else if (retryQueue.size >= ERROR_THRESHOLD) {
      log.error(
          "Elasticsearch retry: $failed/${toRetry.size} entries still failing, ${retryQueue.size} total queued")
    } else {
      log.warn(
          "Elasticsearch retry: $failed/${toRetry.size} entries still failing, retrying next cycle")
    }
  }

  // We can't use Dagger to inject dependencies since this class is called from a library
  private val appConfig = DaggerAppComponent.create().getAppConfig()
  private val esApiKey = appConfig.requireStringProperty("app.logging.elastic-api-key")
  private val esHost = appConfig.requireStringProperty("app.logging.elastic-host")
  private val loggerFactory = DaggerAppComponent.create().getLoggerFactory()
  val log = loggerFactory.create(ElasticsearchClientManager::class.java)

  // Async client: index() returns a CompletableFuture instead of blocking the calling thread for
  // the full ES round trip. The underlying Apache transport is already async (HttpAsyncClients
  // below) — the old sync ElasticsearchClient wrapper was needlessly blocking a Dispatchers.IO
  // worker thread waiting on that async operation for every request/tracked action, which under
  // load starved other coroutines (including unrelated ones, like the Keycloak userinfo call)
  // sharing that same limited thread pool.
  val client: ElasticsearchAsyncClient by lazy {
    // Create an SSLContext that ignores self-signed certificates
    // TODO: verify the server's certificate
    val sslContext: SSLContext =
        SSLContextBuilder.create()
            .loadTrustMaterial { _: Array<X509Certificate>, _: String -> true }
            .build()

    // Create a HostnameVerifier
    val hostnameVerifier = HostnameVerifier { hostname: String, _: SSLSession ->
      hostname == esHost
    }

    val httpClientBuilder: HttpAsyncClientBuilder =
        HttpAsyncClients.custom()
            .setSSLContext(sslContext)
            .setSSLHostnameVerifier(hostnameVerifier)
            .addInterceptorLast(
                HttpRequestInterceptor { request, _ ->
                  request.addHeader("Authorization", "ApiKey $esApiKey")
                })

    @Suppress("MagicNumber")
    val restClientBuilder: RestClientBuilder =
        RestClient.builder(HttpHost(esHost, 9200, "https"))
            .setHttpClientConfigCallback { httpClientBuilder }
            .setRequestConfigCallback { requestConfigBuilder ->
              requestConfigBuilder
                  .setConnectTimeout(3000)
                  .setSocketTimeout(10_000)
                  .setConnectionRequestTimeout(3000)
            }

    val restClient = restClientBuilder.build()
    val transport = RestClientTransport(restClient, JacksonJsonpMapper())
    ElasticsearchAsyncClient(transport)
  }
}
