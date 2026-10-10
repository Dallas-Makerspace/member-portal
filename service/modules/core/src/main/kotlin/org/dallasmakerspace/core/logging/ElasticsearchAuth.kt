package org.dallasmakerspace.core.logging

import com.agido.logback.elasticsearch.config.Authentication
import java.net.HttpURLConnection
import org.dallasmakerspace.core.CoreServices

class ElasticsearchAuth : Authentication {
  override fun addAuth(urlConnection: HttpURLConnection, body: String) {
    if (!CoreServices.isInstalled) return // only unit-test JVMs, where nothing sends to ES
    val apiKey = CoreServices.appConfig.requireStringProperty("app.logging.elastic-api-key")
    urlConnection.setRequestProperty("Authorization", "ApiKey $apiKey")
  }
}
