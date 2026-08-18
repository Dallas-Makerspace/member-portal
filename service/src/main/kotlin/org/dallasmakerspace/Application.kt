package org.dallasmakerspace

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.dallasmakerspace.core.logging.TrustManager
import org.dallasmakerspace.di.AppComponentHolder
import org.dallasmakerspace.plugins.HealthState
import org.dallasmakerspace.plugins.configureDatabase
import org.dallasmakerspace.plugins.configureElasticsearch
import org.dallasmakerspace.plugins.configureHTTP
import org.dallasmakerspace.plugins.configureHealth
import org.dallasmakerspace.plugins.configureMonitoring
import org.dallasmakerspace.plugins.configureRouting
import org.dallasmakerspace.plugins.configureSerialization

// Named marker class so the real-backends warning below gets a clean "Application" logger name
// (main() is a top-level function, so it has no javaClass of its own to pass to LoggerFactory).
private class Application

fun main() {

  val appComponent = AppComponentHolder.instance
  val appConfig = appComponent.getAppConfig()
  val portStr = appConfig.requireStringProperty("ktor.deployment.port")
  val healthProbeActiveDirectoryService = appComponent.getActiveDirectoryService()

  // Mocks require BOTH ktor.development and app.use-mock-services true (see di/*Module.kt's
  // useMockServices()) - warn loudly whenever either is false, since that means live calls.
  val devMode = appConfig.requireBooleanProperty("ktor.development")
  val useMockServices = appConfig.requireBooleanProperty("app.use-mock-services")
  if (!devMode || !useMockServices) {
    appComponent
        .getLoggerFactory()
        .create(Application::class.java)
        .warn(
            "REAL BACKENDS ACTIVE — mocks disabled (ktor.development=$devMode, " +
                "app.use-mock-services=$useMockServices). Live calls will go to AD/LDAP, WinRM, " +
                "Guacamole, Discourse, the master DB, SmartWaiver, notifications, and/or AskAI.")
  }

  // Disable SSL certificate verification until we can embed our root CA cert sig
  // TODO: remove this
  TrustManager.disableSSLCertificateChecking()

  val server =
      embeddedServer(Netty, port = portStr.toInt(), host = "0.0.0.0") {
        configureHealth(healthProbeActiveDirectoryService)
        configureSerialization()
        configureMonitoring()
        configureHTTP()
        configureDatabase()
        configureRouting()
        configureElasticsearch()
        HealthState.markReady()
      }
  Runtime.getRuntime()
      .addShutdownHook(
          Thread {
            Thread.sleep(15_000)
            server.stop(gracePeriodMillis = 10_000, timeoutMillis = 30_000)
          })
  server.start(wait = true)
}
