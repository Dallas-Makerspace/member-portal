package org.dallasmakerspace.bench

// Diagnostic tool, not part of the app: compares CIO/OkHttp/JDK HttpClient latency against
// Keycloak's userinfo endpoint to isolate the source of prod's slowness.

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TARGET_URL =
    "https://login.dallasmakerspace.org/auth/realms/DMS/protocol/openid-connect/userinfo"
private const val BOGUS_TOKEN = "benchmark-bogus-token"
// Same shape as the real intervals: enough iterations to reveal a churn-driven trend without
// hammering a production auth endpoint unreasonably hard.
private const val INTERVAL_MS = 1000L
private const val DURATION_MINUTES = 30L

fun main(args: Array<String>) {
  val outputPath = args.getOrNull(0) ?: "engine-benchmark.csv"
  val out = File(outputPath)
  out.parentFile?.mkdirs()
  out.writeText("timestamp,engine,elapsed_ms,http_status,error\n")

  // Mirrors DMSHttpClient's exact CIO configuration.
  val cioClient =
      HttpClient(CIO) {
        engine {
          endpoint {
            keepAliveTime = 10_000
            maxConnectionsPerRoute = 100
            connectTimeout = 10_000
            connectAttempts = 2
          }
        }
        install(HttpTimeout) { requestTimeoutMillis = 120_000 }
      }

  val okHttpClient = OkHttpClient()
  val jdkClient = java.net.http.HttpClient.newHttpClient()

  val endAt = System.currentTimeMillis() + DURATION_MINUTES * 60_000
  var round = 0

  println(
      "Starting benchmark: $DURATION_MINUTES min, one call per engine every ${INTERVAL_MS}ms, target=$TARGET_URL")
  println("Writing results to ${out.absolutePath}")

  while (System.currentTimeMillis() < endAt) {
    round++

    val cioResult = callCio(cioClient)
    appendRow(out, "cio", cioResult)

    val okHttpResult = callOkHttp(okHttpClient)
    appendRow(out, "okhttp", okHttpResult)

    val jdkResult = callJdk(jdkClient)
    appendRow(out, "jdk", jdkResult)

    if (round % 30 == 0) {
      println(
          "round=$round elapsedMin=${(DURATION_MINUTES * 60_000 - (endAt - System.currentTimeMillis())) / 60_000} " +
              "cio=${cioResult.first}ms okhttp=${okHttpResult.first}ms jdk=${jdkResult.first}ms")
    }

    Thread.sleep(INTERVAL_MS)
  }

  cioClient.close()
  println("Benchmark complete: $round rounds.")
}

private fun appendRow(out: File, engine: String, result: Triple<Long, Int, String>) {
  val (elapsed, status, error) = result
  out.appendText("${Instant.now()},$engine,$elapsed,$status,\"$error\"\n")
}

private fun callCio(client: HttpClient): Triple<Long, Int, String> {
  return try {
    val t0 = System.nanoTime()
    val resp: HttpResponse = runBlocking {
      client.get(TARGET_URL) {
        headers { append("Authorization", "Bearer $BOGUS_TOKEN") }
        timeout { requestTimeoutMillis = 120_000 }
      }
    }
    runBlocking { resp.body<String>() }
    val elapsed = (System.nanoTime() - t0) / 1_000_000
    Triple(elapsed, resp.status.value, "")
  } catch (e: Exception) {
    Triple(-1L, -1, e.javaClass.simpleName + ": " + e.message)
  }
}

private fun callOkHttp(client: OkHttpClient): Triple<Long, Int, String> {
  return try {
    val request =
        Request.Builder().url(TARGET_URL).header("Authorization", "Bearer $BOGUS_TOKEN").build()
    val t0 = System.nanoTime()
    client.newCall(request).execute().use { resp ->
      resp.body?.string()
      val elapsed = (System.nanoTime() - t0) / 1_000_000
      Triple(elapsed, resp.code, "")
    }
  } catch (e: Exception) {
    Triple(-1L, -1, e.javaClass.simpleName + ": " + e.message)
  }
}

private fun callJdk(client: java.net.http.HttpClient): Triple<Long, Int, String> {
  return try {
    val request =
        HttpRequest.newBuilder()
            .uri(URI.create(TARGET_URL))
            .header("Authorization", "Bearer $BOGUS_TOKEN")
            .GET()
            .build()
    val t0 = System.nanoTime()
    val resp = client.send(request, BodyHandlers.ofString())
    val elapsed = (System.nanoTime() - t0) / 1_000_000
    Triple(elapsed, resp.statusCode(), "")
  } catch (e: Exception) {
    Triple(-1L, -1, e.javaClass.simpleName + ": " + e.message)
  }
}
