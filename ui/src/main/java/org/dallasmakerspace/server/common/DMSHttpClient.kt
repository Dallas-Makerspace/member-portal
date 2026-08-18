package org.dallasmakerspace.server.common

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.util.*
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.server.common.logging.LoggerFactory

@Singleton
class DMSHttpClient @Inject constructor(loggerFactory: LoggerFactory) {
  private val log = loggerFactory.create(javaClass)
  private val objectMapper = jacksonObjectMapper()

  companion object {
    const val DEFAULT_TIMEOUT_MS: Long = 120_000 // 120 seconds for long-running reports
    const val SHORT_TIMEOUT_MS: Long = 2_500 // 2.5 seconds for non-essential calls
  }

  // One long-lived client for the lifetime of the app, reused across every request. This used to
  // build (and .use{}-close) a brand-new HttpClient(CIO) per call, which pays full engine
  // startup + connection/TLS + shutdown cost every time — that was adding well over a second to
  // every single outbound call (Keycloak userinfo, every backend-service call, etc.), which is why
  // every authenticated page load had a multi-second floor regardless of how little work it
  // actually did. HttpTimeout lets each call still override the timeout per-request.
  private val client =
      HttpClient(CIO) {
        engine {
          endpoint {
            keepAliveTime = 10_000
            maxConnectionsPerRoute = 100
            connectTimeout = 10_000
            connectAttempts = 2
          }
        }
        install(HttpTimeout) { requestTimeoutMillis = DEFAULT_TIMEOUT_MS }
        install(ContentNegotiation) { jackson {} }
        install(Logging) {
          logger = Logger.DEFAULT
          level = LogLevel.INFO
        }
      }

  suspend fun get(
      url: String,
      customHeaders: StringValues,
      timeoutMs: Long = DEFAULT_TIMEOUT_MS,
  ): Map<String, Any> {
    val resp =
        client.get(url) {
          headers { appendAll(customHeaders) }
          timeout { requestTimeoutMillis = timeoutMs }
        }
    if (resp.status.isSuccess()) {
      return resp.body<Map<String, Any>>()
    } else {
      // If member-profile-service encounters an error, it will return json with a message attribute
      val body = resp.body<String>()
      log.error("get: HTTP request failed: $body")
      val message =
          try {
            if (body.contains("message")) {
              val json = objectMapper.readValue(body, Map::class.java)
              (json["message"] as? String) ?: "Status: ${resp.status}, Response: $body"
            } else {
              "Status: ${resp.status}, Response: $body"
            }
          } catch (e: JsonProcessingException) {
            log.warn("get: Failed to parse error response as JSON: ${e.message}")
            "Status: ${resp.status}, Response: $body"
          }
      throw HttpException(message, resp.status.value)
    }
  }

  suspend fun post(
      url: String,
      authHeaders: StringValues,
      payload: Any,
      timeoutMs: Long = DEFAULT_TIMEOUT_MS,
  ): Map<String, Any> {
    val resp =
        client.post {
          url(url)
          headers { appendAll(authHeaders) }
          contentType(ContentType.Application.Json)
          setBody(payload)
          timeout { requestTimeoutMillis = timeoutMs }
        }
    if (resp.status.isSuccess()) {
      return resp.body<Map<String, Any>>()
    } else {
      val body = resp.body<String>()
      log.error("post: HTTP request failed: $body")
      throw HttpException("Failed to post $url; Status: ${resp.status} Response: $body")
    }
  }

  suspend fun patch(
      url: String,
      authHeaders: StringValues,
      payload: Any,
      timeoutMs: Long = DEFAULT_TIMEOUT_MS,
  ): Map<String, Any> {
    val resp =
        client.patch {
          url(url)
          headers { appendAll(authHeaders) }
          contentType(ContentType.Application.Json)
          setBody(payload)
          timeout { requestTimeoutMillis = timeoutMs }
        }
    if (resp.status.isSuccess()) {
      return resp.body<Map<String, Any>>()
    } else {
      // If member-profile-service encounters an error, it will return json with a message attribute
      val body = resp.body<String>()
      log.error("patch: HTTP request failed: $body")
      val message =
          try {
            if (body.contains("message")) {
              val json = objectMapper.readValue(body, Map::class.java)
              (json["message"] as? String) ?: "Failed to patch $url; Status: ${resp.status} Response: $body"
            } else {
              "Failed to patch $url; Status: ${resp.status} Response: $body"
            }
          } catch (e: JsonProcessingException) {
            log.warn("patch: Failed to parse error response as JSON: ${e.message}")
            "Failed to patch $url; Status: ${resp.status} Response: $body"
          }
      throw HttpException(message, resp.status.value)
    }
  }

  suspend fun put(
      url: String,
      authHeaders: StringValues,
      payload: Any,
      timeoutMs: Long = DEFAULT_TIMEOUT_MS,
  ): Map<String, Any> {
    val resp =
        client.put {
          url(url)
          headers { appendAll(authHeaders) }
          contentType(ContentType.Application.Json)
          setBody(payload)
          timeout { requestTimeoutMillis = timeoutMs }
        }
    if (resp.status.isSuccess()) {
      return resp.body<Map<String, Any>>()
    } else {
      val body = resp.body<String>()
      log.error("put: HTTP request failed: $body")
      throw HttpException("Failed to put $url; Status: ${resp.status} Response: $body")
    }
  }

  suspend fun delete(
      url: String,
      authHeaders: StringValues,
      timeoutMs: Long = DEFAULT_TIMEOUT_MS,
  ): Map<String, Any> {
    val resp =
        client.delete {
          url(url)
          headers { appendAll(authHeaders) }
          timeout { requestTimeoutMillis = timeoutMs }
        }
    if (resp.status.isSuccess()) {
      return resp.body<Map<String, Any>>()
    } else {
      val body = resp.body<String>()
      log.error("delete: HTTP request failed: $body")
      throw HttpException("Failed to delete $url; Status: ${resp.status} Response: $body")
    }
  }

  suspend fun delete(
      url: String,
      authHeaders: StringValues,
      payload: Any,
      timeoutMs: Long = DEFAULT_TIMEOUT_MS,
  ) {
    val resp =
        client.delete {
          url(url)
          headers { appendAll(authHeaders) }
          contentType(ContentType.Application.Json)
          setBody(payload)
          timeout { requestTimeoutMillis = timeoutMs }
        }
    if (!resp.status.isSuccess()) {
      log.error("delete: HTTP request failed: $resp")
      throw HttpException("Failed to delete $url; Status: ${resp.status} FullResponse: $resp")
    }
  }
}
