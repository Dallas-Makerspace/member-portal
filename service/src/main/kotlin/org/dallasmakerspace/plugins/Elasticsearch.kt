package org.dallasmakerspace.plugins

import co.elastic.clients.elasticsearch.ElasticsearchClient
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.util.pipeline.*
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.core.logging.ElasticsearchClientManager
import org.dallasmakerspace.di.AppComponentHolder
import org.slf4j.MDC

fun Application.configureElasticsearch() {
  val appConfig = AppComponentHolder.instance.getAppConfig()
  val isDevelopment = appConfig.requireBooleanProperty("ktor.development")
  if (!isDevelopment) {
    intercept(ApplicationCallPipeline.Monitoring) {
      logToElasticsearch(this.call, ElasticsearchClientManager.client)
    }
  }
}

suspend fun logToElasticsearch(call: PipelineCall, client: ElasticsearchClient) {
  val request = call.request
  val response = call.response
  // Do not log redirect responses and static files
  if (response.status() == HttpStatusCode.TemporaryRedirect ||
      request.uri.startsWith("/static") ||
      request.uri.startsWith("/favicon.ico") ||
      request.uri == "/health/ready" ||
      request.uri == "/health/live") {
    return
  }

  val principal = call.principal<ApiKeyAuthProvider.ApiKeyPrincipal>()
  val apiClient = principal?.client

  val logEntry =
      mapOf(
          "@timestamp" to Instant.now().toString(),
          "method" to request.httpMethod.value,
          "app" to "member-profile-service",
          "host" to request.host(),
          "ip" to request.origin.remoteAddress,
          "sessionid" to (MDC.get("CallId") ?: "NO_SESSION"),
          "userid" to (apiClient ?: "NO_USER"),
          "uri" to request.uri,
          "status" to response.status()?.value,
          "userAgent" to request.userAgent(),
      )

  call.application.launch(Dispatchers.IO) {
    try {
      client.index { i -> i.index("member-portal-logs").document(logEntry) }
    } catch (e: IOException) {
      ElasticsearchClientManager.log.error("Failed to log to Elasticsearch", e)
    }
  }
}
