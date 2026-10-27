package org.dallasmakerspace.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.dallasmakerspace.server.common.PostHogConfig
import org.dallasmakerspace.server.common.TrustManager
import org.dallasmakerspace.server.di.AppComponentHolder
import org.dallasmakerspace.server.plugins.HealthState
import org.dallasmakerspace.server.plugins.configureCommonModel
import org.dallasmakerspace.server.plugins.configureElasticsearch
import org.dallasmakerspace.server.plugins.configureHealth
import org.dallasmakerspace.server.plugins.configureHttp
import org.dallasmakerspace.server.plugins.configureMonitoring
import org.dallasmakerspace.server.plugins.configureRouting
import org.dallasmakerspace.server.plugins.configureSessionEnrichment
import org.dallasmakerspace.server.plugins.configureSessions
import org.dallasmakerspace.server.plugins.configureStatusPages
import org.dallasmakerspace.server.plugins.configureTemplating

fun main() {
  val appComponent = AppComponentHolder.instance
  val appConfig = appComponent.getAppConfig()
  val memberServiceClient = appComponent.getMemberServiceClient()
  val agentBotAccess = appComponent.getAgentBotAccess()
  PostHogConfig.initialize(appConfig)
  val portStr = appConfig.requireStringProperty("ktor.deployment.port")

  // Disable SSL certificate verification until we can embed our root CA cert sig
  // TODO: remove this
  TrustManager.disableSSLCertificateChecking()

  val server =
      embeddedServer(Netty, port = portStr.toInt(), host = "0.0.0.0") {
        configureHealth()
        configureCommonModel(memberServiceClient, agentBotAccess)
        configureTemplating()
        configureHttp()
        configureSessions()
        configureSessionEnrichment() // Must run after Sessions/Auth but before Monitoring
        configureMonitoring()
        configureRouting()
        configureStatusPages()
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
