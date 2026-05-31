package org.dallasmakerspace.e2e.support

import java.net.HttpURLConnection
import java.net.URI
import org.junit.jupiter.api.Assertions.assertTrue

object E2EEnvironment {
  val baseUrl: String = System.getenv("E2E_BASE_URL") ?: "http://localhost:8000"

  fun requireServerUp() {
    assertTrue(
        isServerUp(baseUrl),
        "Member portal UI is not running at $baseUrl (GET $baseUrl/readyz failed)",
    )
  }

  private fun isServerUp(baseUrl: String): Boolean =
      try {
        val connection = URI("$baseUrl/readyz").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        connection.requestMethod = "GET"
        connection.responseCode in 200..299
      } catch (_: Exception) {
        false
      }
}
