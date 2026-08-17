package org.dallasmakerspace.winrm

import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.metricshub.winrm.WinRMClient as MetricsHubWinRMClient

// Counts ESTABLISHED RDP connections rather than logon sessions - a plain, structured integer
// with no text-table parsing, and only needs standard user rights (consistent with a
// least-privilege "Remote Management Users" account, no admin required).
private const val COUNT_ESTABLISHED_RDP_CONNECTIONS_PS =
    "@(Get-NetTCPConnection -LocalPort 3389 -State Established -ErrorAction SilentlyContinue).Count"

/**
 * Detects direct (non-Guacamole) RDP sessions on a target Windows machine via WinRM.
 *
 * Uses org.metricshub:winrm-java (vendored - see service/libs/README.md), not the more commonly
 * recommended winrm4j: winrm4j wraps an old Apache CXF stack whose NTLM implementation has a
 * confirmed, unfixed upstream bug (cloudsoft/winrm4j#110) where the handshake completes
 * successfully (verified via the target's own Windows Security event log, event 4624) but the
 * server rejects the subsequent encrypted payload - surfacing as CXF's own "Authorization loop
 * detected". winrm-java removed the CXF backend specifically to fix this class of issue; verified
 * directly against both real target machines before adopting it here.
 *
 * Timeout strategy - deliberately NOT a coroutine `withTimeout`/`withTimeoutOrNull` around the
 * blocking WinRM call: this repo already learned (see CalendarRepository's handling of blocking
 * JDBC calls) that a coroutine timeout only stops *waiting* for a blocking call, it does not
 * interrupt the underlying thread. The real, hard deadline here is [Future.get] with a timeout,
 * which reliably returns control to the caller regardless of whether the worker thread is still
 * blocked - kept as a defensive backstop even though winrm-java's own `.timeout(Duration)` should
 * already enforce socket-level timeouts internally (unlike CXF, this is a from-scratch client, not
 * one more library claim to take on faith). A fast TCP pre-flight on the WinRM port catches the
 * common "not listening/unreachable" case almost for free, before ever touching the SOAP call.
 */
@Singleton
class WinRmClient @Inject constructor(private val appConfig: AppConfig, loggerFactory: LoggerFactory) :
    IWinRmClient {
  private val log = loggerFactory.create(javaClass)

  private val port by lazy { appConfig.requireIntProperty("app.winrm.port") }
  private val username by lazy { appConfig.requireStringProperty("app.winrm.username") }
  private val password by lazy { appConfig.requireStringProperty("app.winrm.password") }
  private val connectTimeoutMs by lazy { appConfig.requireIntProperty("app.winrm.connect-timeout-ms") }
  private val operationTimeoutMs by lazy {
    appConfig.requireLongProperty("app.winrm.operation-timeout-ms")
  }
  private val callTimeoutMs by lazy { appConfig.requireLongProperty("app.winrm.call-timeout-ms") }

  // Bounded + daemon so a stuck target can never block JVM shutdown or grow unbounded - it just
  // occupies one pool slot until the OS eventually reclaims the stuck connection. Created once,
  // since this class itself is @Singleton.
  private val executor by lazy {
    val poolSize = appConfig.requireIntProperty("app.winrm.executor-pool-size")
    val threadNumber = AtomicInteger(1)
    Executors.newFixedThreadPool(
        poolSize,
        ThreadFactory { runnable ->
          Thread(runnable, "winrm-client-${threadNumber.getAndIncrement()}").apply {
            isDaemon = true
          }
        })
  }

  override suspend fun getEstablishedRdpConnectionCount(hostname: String): Int? {
    if (hostname.isBlank()) return null
    if (!canReachWinRmPort(hostname)) {
      log.debug("WinRM port {} unreachable on {}, skipping session check", port, hostname)
      return null
    }
    return withContext(Dispatchers.IO) { executeCountQuery(hostname) }
  }

  private fun canReachWinRmPort(hostname: String): Boolean =
      try {
        Socket().use { socket ->
          socket.connect(InetSocketAddress(hostname, port), connectTimeoutMs)
          true
        }
      } catch (e: Exception) {
        false
      }

  private fun executeCountQuery(hostname: String): Int? {
    val future =
        executor.submit(
            Callable {
              MetricsHubWinRMClient.builder(hostname)
                  .credentials(username, password.toCharArray())
                  .port(port)
                  .http()
                  .timeout(Duration.ofMillis(operationTimeoutMs))
                  .build()
                  .use { it.powerShell(COUNT_ESTABLISHED_RDP_CONNECTIONS_PS).execute() }
            })
    return try {
      val response = future.get(callTimeoutMs, TimeUnit.MILLISECONDS)
      if (response.exitCode() != 0) {
        log.warn(
            "WinRM session count on {} exited non-zero ({}): {}",
            hostname,
            response.exitCode(),
            response.stderr().trim())
        return null
      }
      response.stdout().trim().toIntOrNull().also {
        if (it == null) {
          log.warn("Could not parse WinRM session count response from {}: '{}'", hostname, response.stdout())
        }
      }
    } catch (e: TimeoutException) {
      future.cancel(true) // best-effort - the underlying client may not honor interruption
      log.debug("WinRM session count on {} timed out after {}ms", hostname, callTimeoutMs)
      null
    } catch (e: Exception) {
      log.warn("WinRM session count check failed for {}", hostname, e)
      null
    }
  }
}
