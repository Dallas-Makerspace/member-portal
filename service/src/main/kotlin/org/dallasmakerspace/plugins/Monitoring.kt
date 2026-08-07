package org.dallasmakerspace.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import io.ktor.util.date.*
import org.dallasmakerspace.core.logging.SessionIdGenerator
import org.dallasmakerspace.di.AppComponentHolder
import org.slf4j.event.Level

fun Application.configureMonitoring() {
  val loggerFactory = AppComponentHolder.instance.getLoggerFactory()
  install(CallLogging) {
    level = Level.INFO
    logger = loggerFactory.create(Application::class.java)
    callIdMdc()

    filter { call -> call.request.path() != "/health/ready" }

    format { call ->
      val status = call.response.status()
      val httpMethod = call.request.httpMethod.value
      val ipAddress = call.request.origin.remoteAddress
      val path = call.request.path()
      val timeTaken = call.processingTimeMillis { getTimeMillis() }
      val userAgent = call.request.headers["User-Agent"]
      "IP: $ipAddress, Status: $status, HTTP: $httpMethod, URL: $path, Time: $timeTaken ms, User agent: $userAgent"
    }
  }
  install(CallId) {
    header(HttpHeaders.XRequestId)
    verify { callId: String -> callId.isNotBlank() }
    generate {
      if (it.request.headers["X-Api-Client"] == "jenkins-member-refresh") {
        SessionIdGenerator().generate()
      } else {
        "no-call-id"
      }
    }
  }
}
