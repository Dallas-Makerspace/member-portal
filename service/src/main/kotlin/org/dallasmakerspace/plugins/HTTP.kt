package org.dallasmakerspace.plugins

import com.ucasoft.ktor.simpleCache.SimpleCache
import com.ucasoft.ktor.simpleMemoryCache.memoryCache
import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.plugins.compression.*
import kotlin.time.Duration.Companion.seconds

fun Application.configureHTTP() {
  install(SimpleCache) {
    memoryCache {
      // Short cache for most endpoints (10 seconds)
      // Note: signed-waivers has its own 24-hour cache built-in
      invalidateAt = 10.seconds
    }
  }
  @Suppress("MagicNumber")
  install(Compression) {
    excludeContentType(ContentType.Text.EventStream)
    gzip { priority = 1.0 }
    deflate {
      priority = 10.0
      minimumSize(1024) // condition
    }
  }
}
