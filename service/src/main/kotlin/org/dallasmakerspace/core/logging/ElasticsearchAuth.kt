package org.dallasmakerspace.core.logging

import com.agido.logback.elasticsearch.config.Authentication
import java.net.HttpURLConnection
import org.dallasmakerspace.core.CoreServices

class ElasticsearchAuth : Authentication {
  override fun addAuth(urlConnection: HttpURLConnection, body: String) {
    val apiKey = CoreServices.appConfig.requireStringProperty("app.logging.elastic-api-key")
    urlConnection.setRequestProperty("Authorization", "ApiKey $apiKey")
  }
}
