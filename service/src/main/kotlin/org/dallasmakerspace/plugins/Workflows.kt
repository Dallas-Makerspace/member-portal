package org.dallasmakerspace.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import java.net.InetAddress
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.di.AppComponent
import org.dallasmakerspace.di.AppComponentHolder
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Workflows")

// Not tied to the Application job: the shutdown hook drains the executor while the server is
// still up, and server.stop would cancel an application-scoped loop mid-step.
private val executorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Docker stop gives ~30s; leave room for the JVM to exit. */
const val WORKFLOW_SHUTDOWN_GRACE_MILLIS = 25_000L

/** Starts the workflow executor loop (unless disabled by config) and wires stream shutdown. */
fun Application.configureWorkflows(component: AppComponent = AppComponentHolder.instance) {
  environment.monitor.subscribe(ApplicationStopPreparing) {
    component.getWorkflowStreamLifecycle().closeStreams()
  }
  val appConfig = component.getAppConfig()
  if (!appConfig.requireBooleanProperty("app.workflows.executor.enabled")) {
    log.warn("Workflow executor disabled (app.workflows.executor.enabled=false)")
    return
  }
  component.getWorkflowEngineSettings().instanceId = instanceId()
  component.getWorkflowExecutor().start(executorScope)
  log.info("Workflow executor started as ${component.getWorkflowEngineSettings().instanceId}")
}

/** JVM shutdown hook body: close SSE streams, then let the executor finish its current step. */
fun shutdownWorkflows(component: AppComponent = AppComponentHolder.instance) {
  runCatching { component.getWorkflowStreamLifecycle().closeStreams() }
  runCatching {
    runBlocking { component.getWorkflowExecutor().shutdown(WORKFLOW_SHUTDOWN_GRACE_MILLIS) }
  }
      .onFailure { log.warn("Workflow executor shutdown failed: ${it.message}") }
}

private fun instanceId(): String {
  val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("service")
  return "$host-${UUID.randomUUID().toString().substringBefore('-')}"
}
