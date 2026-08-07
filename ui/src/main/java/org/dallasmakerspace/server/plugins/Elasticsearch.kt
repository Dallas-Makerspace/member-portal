package org.dallasmakerspace.server.plugins

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.util.pipeline.*
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.dallasmakerspace.server.common.logging.ElasticsearchClientManager
import org.dallasmakerspace.server.di.AppComponentHolder
import org.slf4j.MDC

fun Application.configureElasticsearch() {
  val appConfig = AppComponentHolder.instance.getAppConfig()
  val isDevelopment = appConfig.requireBooleanProperty("ktor.development")
  if (!isDevelopment) {
    intercept(ApplicationCallPipeline.Monitoring) {
      logToElasticsearch(this.call, ElasticsearchClientManager.client)
    }

    // Background coroutine to retry queued entries
    launch(Dispatchers.IO) {
      while (isActive) {
        delay(ElasticsearchClientManager.RETRY_INTERVAL_MS)
        ElasticsearchClientManager.processRetryQueue()
      }
    }
  }
}

@Suppress("SwallowedException")
suspend fun logToElasticsearch(call: PipelineCall, client: ElasticsearchAsyncClient) {
  val request = call.request
  val response = call.response
  if (response.status() == HttpStatusCode.TemporaryRedirect ||
      request.uri.startsWith("/static") ||
      request.uri.startsWith("/favicon.ico") ||
      request.uri == "/readyz" ||
      request.uri == "/healthz" ||
      request.uri == "/sw.js" ||
      request.uri == "/manifest.json" ||
      request.uri == "/apple-touch-icon.png" ||
      request.uri == "/apple-touch-icon-precomposed.png") {
    return
  }

  val logEntry =
      mapOf(
          "@timestamp" to Instant.now().toString(),
          "method" to request.httpMethod.value,
          "app" to "member-profile-ui",
          "host" to request.host(),
          "ip" to request.origin.remoteAddress,
          "sessionid" to (MDC.get("sessionid") ?: "NO_SESSION"),
          "userid" to (MDC.get("userid") ?: "NO_USER"),
          "uri" to request.uri,
          "status" to response.status()?.value,
          "userAgent" to request.userAgent(),
      )

  call.application.launch(Dispatchers.IO) {
    try {
      client.index { i -> i.index("member-portal-logs").document(logEntry) }.await()
    } catch (e: IOException) {
      ElasticsearchClientManager.enqueueForRetry(logEntry)
    }
  }
}
